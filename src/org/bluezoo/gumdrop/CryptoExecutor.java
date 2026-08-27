/*
 * CryptoExecutor.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A bounded, shared thread pool for <em>CPU-bound</em> cryptographic
 * operations that must not execute on a {@link SelectorLoop} thread.
 *
 * <p>Gumdrop's reactor pins each connection to a single {@code SelectorLoop}
 * thread, and protocol handlers must never block it (blocking stalls every
 * other connection multiplexed on that loop). TLS handshake delegated tasks
 * ({@code SSLEngine} {@code NEED_TASK} status -- the actual RSA/ECDHE
 * key-exchange math and certificate-chain validation) have no non-blocking
 * JDK API and can take long enough to matter under load, so a burst of new
 * TLS connections without session resumption can otherwise stall every other
 * connection sharing that loop for the duration.
 *
 * <p>This class is the CPU-bound sibling of {@link StorageExecutor}, which
 * exists for the same reason but for blocking storage/disk I/O. The two are
 * deliberately separate pools: mixing crypto and disk work in one pool would
 * let a disk I/O burst starve TLS handshakes (or vice versa), and would skew
 * capacity planning for either. Sizing also differs -- {@code StorageExecutor}
 * is tuned for I/O-bound work (more threads than cores helps, since threads
 * mostly block waiting on I/O); this pool is tuned for CPU-bound work (more
 * threads than cores just adds context-switch overhead without throughput
 * gain).
 *
 * <p>A caller submits a {@link Callable} together with the {@link Endpoint}
 * that should receive the outcome. The work runs on a pool thread; the
 * success/failure {@link Callback} is then invoked <strong>on the endpoint's
 * SelectorLoop thread</strong> (via {@link Endpoint#execute}), so callbacks
 * may safely touch per-connection state. This preserves the
 * single-threaded-per-connection invariant.
 *
 * <p>The pool is bounded (fixed thread count and a bounded queue) so that a
 * burst of concurrent handshakes applies backpressure rather than spawning
 * unbounded work. When the queue is full, submission is rejected and the
 * {@code Callback}'s {@link Callback#failed failed} method is invoked (on the
 * loop) with a {@link RejectedExecutionException} -- a harsher outcome than a
 * rejected storage operation (it fails an in-progress TLS handshake rather
 * than just "retry the command"), but still preferable to an unbounded queue
 * backing up under a handshake flood; callers should close the connection
 * rather than retry.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint#execute(Runnable)
 * @see StorageExecutor
 */
final class CryptoExecutor {

    private static final Logger LOGGER =
            Logger.getLogger(CryptoExecutor.class.getName());

    /**
     * Test-only observer invoked on a crypto worker thread immediately before
     * each submitted operation runs. Production code must leave this
     * {@code null}.
     *
     * <p>Used by boundary tests to assert CPU-bound work runs on a
     * {@code gumdrop-crypto-*} thread rather than a SelectorLoop or caller.
     */
    interface WorkThreadObserver {
        /**
         * @param worker the crypto pool thread about to run an operation
         */
        void observed(Thread worker);
    }

    /**
     * Test-only: if non-null, invoked on the crypto worker before each
     * operation runs. Production code must leave this {@code null}.
     *
     * <p>Used by boundary tests to assert CPU-bound work runs on a
     * {@code gumdrop-crypto-*} thread rather than a SelectorLoop or caller.
     */
    static volatile WorkThreadObserver workThreadObserver;

    /**
     * Default number of crypto worker threads when {@code gumdrop.cryptoThreads}
     * is not set. CPU-bound work shouldn't oversubscribe cores, but a small
     * floor keeps low-core CI/containers from collapsing to a single thread
     * that trivially saturates under concurrent handshakes.
     */
    static final int DEFAULT_THREADS =
            Math.max(2, Runtime.getRuntime().availableProcessors());

    /**
     * Default bounded-queue capacity when {@code gumdrop.cryptoQueue} is not
     * set. Beyond this, submissions are rejected (fail-fast backpressure).
     */
    static final int DEFAULT_QUEUE_CAPACITY = 4096;

    /**
     * Outcome callback for a submitted crypto operation. Exactly one of the
     * two methods is invoked, always on the submitting endpoint's SelectorLoop
     * thread.
     *
     * @param <T> the result type of the crypto operation
     */
    interface Callback<T> {

        /**
         * Invoked (on the loop thread) when the operation completed normally.
         *
         * @param result the value returned by the operation
         */
        void completed(T result);

        /**
         * Invoked (on the loop thread) when the operation threw, or when the
         * pool rejected the submission because it was saturated.
         *
         * @param error the thrown exception, or a
         *        {@link RejectedExecutionException} on saturation
         */
        void failed(Throwable error);
    }

    private final ThreadPoolExecutor executor;

    CryptoExecutor(int threads, int queueCapacity) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r,
                        "gumdrop-crypto-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        // Fixed-size pool with a bounded queue. AbortPolicy so a saturated
        // pool rejects rather than running the task on the calling (loop!)
        // thread; the rejection is turned into a failed() callback below.
        this.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Creates a pool sized from the {@code gumdrop.cryptoThreads} and
     * {@code gumdrop.cryptoQueue} system properties, falling back to
     * {@link #DEFAULT_THREADS} / {@link #DEFAULT_QUEUE_CAPACITY}.
     *
     * @return a new crypto executor
     */
    static CryptoExecutor createDefault() {
        int threads = Integer.getInteger("gumdrop.cryptoThreads",
                DEFAULT_THREADS);
        int queue = Integer.getInteger("gumdrop.cryptoQueue",
                DEFAULT_QUEUE_CAPACITY);
        return new CryptoExecutor(threads, queue);
    }

    /**
     * Runs a CPU-bound crypto operation off the SelectorLoop thread and
     * delivers the outcome back on the endpoint's loop thread.
     *
     * <p>The {@code callback} always runs on {@code endpoint}'s loop thread, so
     * it may touch connection state and send data. Because the connection may
     * have closed while the operation was in flight, callbacks should tolerate
     * a closed endpoint (e.g. guard with {@link Endpoint#isOpen()}).
     *
     * @param <T> the result type
     * @param endpoint the endpoint whose loop thread receives the callback
     * @param operation the work to run on a crypto thread
     * @param callback the outcome callback, invoked on the loop thread
     */
    <T> void submit(final Endpoint endpoint,
            final Callable<T> operation, final Callback<T> callback) {
        if (endpoint == null) {
            throw new NullPointerException();
        }
        // An Endpoint marshals a Runnable onto its own SelectorLoop thread via
        // execute(), which is exactly the loop-dispatch contract below.
        submit(new Executor() {
            @Override
            public void execute(Runnable command) {
                endpoint.execute(command);
            }
        }, operation, callback);
    }

    /**
     * Runs a CPU-bound crypto operation off the SelectorLoop thread and
     * delivers the outcome via an arbitrary loop dispatcher.
     *
     * <p>This is the transport-agnostic form of
     * {@link #submit(Endpoint, Callable, Callback)}. The {@code loopDispatcher}
     * must marshal the supplied {@link Runnable} onto the thread that owns the
     * caller's connection state (its SelectorLoop thread). The success/failure
     * {@link Callback} is always invoked through that dispatcher, so it may
     * safely touch connection state.
     *
     * <p>If the connection has closed while the operation was in flight, the
     * dispatcher may throw or silently drop the task; either way the result is
     * discarded (a {@link Level#FINE} log is emitted on throw).
     *
     * @param <T> the result type
     * @param loopDispatcher marshals the callback onto the owning loop thread
     * @param operation the work to run on a crypto thread
     * @param callback the outcome callback, invoked via {@code loopDispatcher}
     */
    <T> void submit(final Executor loopDispatcher,
            final Callable<T> operation, final Callback<T> callback) {
        if (loopDispatcher == null || operation == null || callback == null) {
            throw new NullPointerException();
        }
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                WorkThreadObserver observer = workThreadObserver;
                if (observer != null) {
                    observer.observed(Thread.currentThread());
                }
                T result = null;
                Throwable error = null;
                try {
                    result = operation.call();
                } catch (Throwable t) {
                    error = t;
                }
                final T finalResult = result;
                final Throwable finalError = error;
                try {
                    loopDispatcher.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (finalError != null) {
                                callback.failed(finalError);
                            } else {
                                callback.completed(finalResult);
                            }
                        }
                    });
                } catch (Throwable dispatchError) {
                    // The owning loop is gone; the connection is dead and
                    // there is nowhere to deliver the result. Drop it.
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE,
                                "Crypto result could not be dispatched back "
                                + "to a closed connection", dispatchError);
                    }
                }
            }
        };
        try {
            executor.execute(task);
        } catch (final RejectedExecutionException rejected) {
            // Pool saturated: report failure on the loop so the handler can
            // respond gracefully. Never run the crypto work on the loop.
            try {
                loopDispatcher.execute(new Runnable() {
                    @Override
                    public void run() {
                        callback.failed(rejected);
                    }
                });
            } catch (Throwable dispatchError) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE,
                            "Crypto rejection could not be dispatched back "
                            + "to a closed connection", dispatchError);
                }
            }
        }
    }

    /**
     * Returns the approximate number of tasks currently queued or running.
     * Intended for diagnostics and tests.
     *
     * @return queued task count plus active task count
     */
    int pendingCount() {
        return executor.getQueue().size() + executor.getActiveCount();
    }

    /**
     * How long {@link #shutdown()} waits for in-flight operations to
     * actually stop before giving up.
     */
    private static final long SHUTDOWN_AWAIT_MS = 5000L;

    /**
     * Shuts the pool down, interrupting in-flight operations, and waits
     * (up to {@link #SHUTDOWN_AWAIT_MS}) for worker threads to actually
     * terminate before returning.
     */
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
