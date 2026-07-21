/*
 * StorageExecutor.java
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
 * A bounded, shared thread pool for <em>blocking</em> storage operations that
 * must not execute on a {@link SelectorLoop} thread.
 *
 * <p>Gumdrop's reactor pins each connection to a single {@code SelectorLoop}
 * thread, and protocol handlers must never block it (blocking stalls every
 * other connection multiplexed on that loop). Network waits have a
 * non-blocking answer — the selector readiness model — but many filesystem
 * operations do not: {@code stat}/{@code readdir}/{@code open}/{@code rename}/
 * {@code mkdir}/{@code delete}, mailbox scans, IMAP {@code SEARCH}, and expunge
 * have no non-blocking JDK API and regular files are not
 * {@link java.nio.channels.SelectableChannel selectable}, so a selector or a
 * re-poll timer cannot make them non-blocking. The only way to keep the loop
 * free of that work is to run it on a separate thread and hand the result back
 * to the owning loop.
 *
 * <p>That is exactly what this class provides. A caller submits a blocking
 * {@link Callable} together with the {@link Endpoint} that should receive the
 * outcome. The blocking work runs on a pool thread; the success/failure
 * {@link Callback} is then invoked <strong>on the endpoint's SelectorLoop
 * thread</strong> (via {@link Endpoint#execute}), so callbacks may safely
 * touch per-connection state and call {@link Endpoint#send}. This preserves the
 * single-threaded-per-connection invariant.
 *
 * <p>Byte streaming of open files uses
 * {@link java.nio.channels.AsynchronousFileChannel}, whose read/write
 * completions run on the JDK async-file thread group and re-enter the loop
 * via {@link Endpoint#execute}. Note that
 * {@code AsynchronousFileChannel.open} itself is a <em>blocking</em>
 * syscall and <strong>must</strong> be performed on this pool (or another
 * non-loop thread), never on a {@code SelectorLoop}.
 *
 * <p>The pool is bounded (fixed thread count and a bounded queue) so that a
 * slow or stuck disk applies backpressure rather than spawning unbounded work.
 * When the queue is full, submission is rejected and the {@code Callback}'s
 * {@link Callback#failed failed} method is invoked (on the loop) with a
 * {@link RejectedExecutionException}, letting the handler emit a
 * &quot;try again later&quot; response instead of blocking.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint#execute(Runnable)
 */
public final class StorageExecutor {

    private static final Logger LOGGER =
            Logger.getLogger(StorageExecutor.class.getName());

    /**
     * Test-only observer invoked on a storage worker thread immediately before
     * each submitted operation runs. Production code must leave this
     * {@code null}.
     *
     * <p>Used by boundary tests to assert blocking work runs on a
     * {@code gumdrop-storage-*} thread rather than a SelectorLoop or caller.
     */
    public interface WorkThreadObserver {
        /**
         * @param worker the storage pool thread about to run an operation
         */
        void observed(Thread worker);
    }

    /**
     * Test-only: if non-null, invoked on the storage worker before each
     * operation runs. Production code must leave this {@code null}.
     *
     * <p>Used by boundary tests to assert blocking work runs on a
     * {@code gumdrop-storage-*} thread rather than a SelectorLoop or caller.
     */
    static volatile WorkThreadObserver workThreadObserver;

    /**
     * Default number of storage worker threads when
     * {@code gumdrop.storageThreads} is not set. Disk throughput saturates
     * with a small amount of concurrency, so this is deliberately modest.
     */
    static final int DEFAULT_THREADS =
            Math.max(4, Runtime.getRuntime().availableProcessors());

    /**
     * Default bounded-queue capacity when {@code gumdrop.storageQueue} is not
     * set. Beyond this, submissions are rejected (fail-fast backpressure).
     */
    static final int DEFAULT_QUEUE_CAPACITY = 4096;

    /**
     * Outcome callback for a submitted storage operation. Exactly one of the
     * two methods is invoked, always on the submitting endpoint's SelectorLoop
     * thread.
     *
     * @param <T> the result type of the storage operation
     */
    public interface Callback<T> {

        /**
         * Invoked (on the loop thread) when the operation completed normally.
         *
         * @param result the value returned by the blocking operation
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

    StorageExecutor(int threads, int queueCapacity) {
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
                        "gumdrop-storage-" + counter.incrementAndGet());
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
     * Creates a pool sized from the {@code gumdrop.storageThreads} and
     * {@code gumdrop.storageQueue} system properties, falling back to
     * {@link #DEFAULT_THREADS} / {@link #DEFAULT_QUEUE_CAPACITY}.
     *
     * @return a new storage executor
     */
    static StorageExecutor createDefault() {
        int threads = Integer.getInteger("gumdrop.storageThreads",
                DEFAULT_THREADS);
        int queue = Integer.getInteger("gumdrop.storageQueue",
                DEFAULT_QUEUE_CAPACITY);
        return new StorageExecutor(threads, queue);
    }

    /**
     * Runs a blocking storage operation off the SelectorLoop thread and
     * delivers the outcome back on the endpoint's loop thread.
     *
     * <p>The {@code callback} always runs on {@code endpoint}'s loop thread, so
     * it may touch connection state and send data. Because the connection may
     * have closed while the operation was in flight, callbacks should tolerate
     * a closed endpoint (e.g. guard with {@link Endpoint#isOpen()}).
     *
     * @param <T> the result type
     * @param endpoint the endpoint whose loop thread receives the callback
     * @param operation the blocking work to run on a storage thread
     * @param callback the outcome callback, invoked on the loop thread
     */
    public <T> void submit(final Endpoint endpoint,
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
     * Runs a blocking storage operation off the SelectorLoop thread and
     * delivers the outcome via an arbitrary loop dispatcher.
     *
     * <p>This is the transport-agnostic form of
     * {@link #submit(Endpoint, Callable, Callback)}. The {@code loopDispatcher}
     * must marshal the supplied {@link Runnable} onto the thread that owns the
     * caller's connection state (its SelectorLoop thread) — for HTTP this is
     * {@link org.bluezoo.gumdrop.http.HTTPResponseState#execute
     * HTTPResponseState.execute}. The success/failure {@link Callback} is always
     * invoked through that dispatcher, so it may safely touch connection state.
     *
     * <p>If the connection has closed while the operation was in flight, the
     * dispatcher may throw or silently drop the task; either way the result is
     * discarded (a {@link Level#FINE} log is emitted on throw).
     *
     * @param <T> the result type
     * @param loopDispatcher marshals the callback onto the owning loop thread
     * @param operation the blocking work to run on a storage thread
     * @param callback the outcome callback, invoked via {@code loopDispatcher}
     */
    public <T> void submit(final Executor loopDispatcher,
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
                                "Storage result could not be dispatched back "
                                + "to a closed connection", dispatchError);
                    }
                }
            }
        };
        try {
            executor.execute(task);
        } catch (final RejectedExecutionException rejected) {
            // Pool saturated: report failure on the loop so the handler can
            // respond gracefully. Never run the blocking work on the loop.
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
                            "Storage rejection could not be dispatched back "
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
     * Shuts the pool down, interrupting in-flight operations. Any pending
     * results are dropped.
     */
    void shutdown() {
        executor.shutdownNow();
    }
}
