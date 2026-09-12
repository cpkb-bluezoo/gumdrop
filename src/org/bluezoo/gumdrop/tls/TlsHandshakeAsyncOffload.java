/*
 * TlsHandshakeAsyncOffload.java
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

package org.bluezoo.gumdrop.tls;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.CryptoExecutor;
import org.bluezoo.gumdrop.Gumdrop;

/**
 * Runs {@link HandshakeEngine}'s handshake start and message processing --
 * key exchange, certificate validation, signatures, HKDF -- off a connection's
 * {@code SelectorLoop} thread on {@link CryptoExecutor}. Record and packet AEAD
 * stay on the loop; only the handshake state machine moves.
 *
 * <p>{@link HandshakeEngine} has no delegated-task API: a single call to
 * {@link HandshakeEngine#start} or {@link HandshakeEngine#processMessage}
 * synchronously performs all of a step's crypto work and may invoke
 * {@link TlsEventSink} callbacks from inside that same call. Those callbacks
 * must run on the connection's loop thread, so {@link #submit} routes
 * {@link #dispatch} to an in-memory queue while the batch runs on a crypto
 * thread, then replays that queue on the loop thread once the batch completes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TlsHandshakeAsyncOffload {

    private static final Logger LOGGER = Logger.getLogger(TlsHandshakeAsyncOffload.class.getName());

    /**
     * Handshake work to run on a crypto thread. Failures are reported through
     * the supplied {@link TlsEventSink}, not by throwing.
     */
    public interface BatchProcessor {
        void process();
    }

    /**
     * Invoked on the loop thread once a batch and its deferred callbacks have
     * finished. Returning true means a follow-up batch was submitted
     * synchronously and the busy state must be preserved.
     */
    public interface CompletionHandler {
        boolean onBatchDone();
    }

    /** Invoked on the loop thread when delegated processing fails unexpectedly. */
    public interface FailureHandler {
        void failed(Throwable error);
    }

    private final Executor loopExecutor;

    private boolean taskInFlight;
    private boolean deferring;
    private List<Runnable> deferredCallbacks;
    private final ArrayList<Runnable> deferredCallbackBuffer = new ArrayList<Runnable>(16);

    private final Object lock = new Object();

    /**
     * @param loopExecutor marshals deferred callbacks onto the owning loop thread
     */
    public TlsHandshakeAsyncOffload(Executor loopExecutor) {
        if (loopExecutor == null) {
            throw new NullPointerException();
        }
        this.loopExecutor = loopExecutor;
    }

    /**
     * Lock callers must hold around any decision that depends on {@link #isBusy}
     * for as long as that decision has an effect a concurrently completing batch
     * could race against.
     *
     * @return the lock object
     */
    public Object lock() {
        return lock;
    }

    /**
     * @return true if a batch is currently running on a crypto thread
     */
    public boolean isBusy() {
        synchronized (lock) {
            return taskInFlight;
        }
    }

    /**
     * @return true while a submitted batch is running on a crypto thread and
     *         {@link #dispatch} is queueing rather than running immediately
     */
    public boolean isDeferring() {
        return deferring;
    }

    /**
     * Runs immediately on the loop thread, or queues for replay once the current
     * batch completes if called from inside {@link BatchProcessor#process}.
     *
     * @param call the callback to run or defer
     */
    public void dispatch(Runnable call) {
        if (deferring) {
            deferredCallbacks.add(call);
        } else {
            call.run();
        }
    }

    /**
     * Runs {@code processor} on {@link CryptoExecutor}, deferring any event-sink
     * callbacks it triggers until it completes, then replays them on the loop
     * thread and runs {@code onDone}. Falls back to synchronous execution when
     * no {@code CryptoExecutor} is available (e.g. unit tests).
     *
     * @param processor the handshake work to run
     * @param onDone invoked on the loop thread once the batch finishes
     * @param onFailure invoked on the loop thread if processing fails unexpectedly
     */
    public void submit(final BatchProcessor processor, final CompletionHandler onDone,
            final FailureHandler onFailure) {
        synchronized (lock) {
            taskInFlight = true;
        }
        final Callable<List<Runnable>> op = new Callable<List<Runnable>>() {
            @Override
            public List<Runnable> call() {
                deferredCallbackBuffer.clear();
                deferredCallbacks = deferredCallbackBuffer;
                deferring = true;
                try {
                    processor.process();
                } finally {
                    deferring = false;
                    deferredCallbacks = null;
                }
                return deferredCallbackBuffer;
            }
        };
        CryptoExecutor.Callback<List<Runnable>> callback = new CryptoExecutor.Callback<List<Runnable>>() {
            @Override
            public void completed(List<Runnable> callbacks) {
                for (Runnable r : callbacks) {
                    r.run();
                }
                synchronized (lock) {
                    if (!onDone.onBatchDone()) {
                        taskInFlight = false;
                    }
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, "TLS handshake delegated processing failed", error);
                onFailure.failed(error);
                synchronized (lock) {
                    if (!onDone.onBatchDone()) {
                        taskInFlight = false;
                    }
                }
            }
        };
        Gumdrop gumdrop = Gumdrop.getInstance();
        CryptoExecutor exec = gumdrop.isStarted() ? gumdrop.getCryptoExecutor() : null;
        if (exec == null) {
            List<Runnable> callbacks;
            try {
                callbacks = op.call();
            } catch (Exception e) {
                callback.failed(e);
                return;
            }
            callback.completed(callbacks);
            return;
        }
        exec.submit(loopExecutor, op, callback);
    }
}
