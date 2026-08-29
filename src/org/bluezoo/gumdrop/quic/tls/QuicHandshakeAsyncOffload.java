/*
 * QuicHandshakeAsyncOffload.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import tech.kwik.agent15.TlsProtocolException;

import org.bluezoo.gumdrop.CryptoExecutor;
import org.bluezoo.gumdrop.Gumdrop;

/**
 * Runs Agent15's handshake message processing -- the actual ECDHE
 * key-exchange math and certificate-chain validation/signing that happen
 * inside {@code TlsMessageParser.parseAndProcessHandshakeMessage} -- off
 * the QUIC connection's {@code SelectorLoop} thread, on {@link
 * CryptoExecutor}, mirroring the equivalent offload already done for
 * TCP/TLS ({@code SSLState}) and DTLS ({@code DTLSSession}).
 *
 * <p>Unlike {@code SSLEngine}, Agent15 has no delegated-task API: a single
 * call to {@code parseAndProcessHandshakeMessage} synchronously performs
 * all of a handshake message's crypto work and, from inside that same
 * call, may invoke callbacks back out onto whichever {@link
 * QuicTlsEngineListener} was supplied at construction ({@code
 * cryptoDataReady}, {@code handshakeSecretsAvailable}, etc.) -- callbacks
 * that are only safe to run on the connection's loop thread. Since the
 * whole call is what needs to move off-loop, not a sub-task, those
 * callbacks-out cannot simply be forwarded to the listener as they occur
 * on the crypto thread. Instead, {@link #submit} runs the batch with
 * {@link #dispatch} routed to an in-memory queue rather than the listener
 * directly, and replays that queue, in order, back on the loop thread
 * once the batch completes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsServerEngine
 * @see QuicTlsClientEngine
 */
final class QuicHandshakeAsyncOffload {

    private static final Logger LOGGER = Logger.getLogger(QuicHandshakeAsyncOffload.class.getName());

    /**
     * A batch of Agent15 handshake message processing to run on a crypto
     * thread.
     */
    interface BatchProcessor {
        void process() throws TlsProtocolException, IOException;
    }

    /**
     * Invoked once a batch (and any deferred listener callbacks it
     * triggered) has finished, on the loop thread -- the caller's chance
     * to immediately start a follow-up batch (e.g. dispatching a CRYPTO
     * frame that queued up while this one was running) before {@link
     * #isBusy} can ever be observed reporting false. Returning true means
     * a follow-up batch was submitted synchronously from within this
     * call, so the busy state must be preserved; returning false means
     * there is nothing further to do right now, so it may clear.
     */
    interface CompletionHandler {
        boolean onBatchDone();
    }

    private final QuicTlsEngineListener listener;

    // Read by the loop thread (isBusy(), from the concrete QuicTlsEngine
    // and, in tests, QuicTestPeer's synchronization poll) and written from
    // inside the completed()/failed() callback, which itself always runs
    // via listener.execute(...) -- ordinarily the loop thread, but a test
    // driving these engines directly without a live Gumdrop can have that
    // callback delivered inline on whatever thread called submit(), so
    // this is volatile rather than relying on same-thread confinement.
    //
    // Only ever cleared after onDone (the CompletionHandler) has had its
    // chance to synchronously start a follow-up batch and said it did not
    // (issue #351) -- never unconditionally before calling onDone. A
    // window where this reads false while onDone is about to resubmit
    // would let a concurrent poller (e.g. QuicTestPeer's
    // awaitHandshakeProcessingIdle) observe "idle" and act on
    // not-yet-installed handshake state (e.g. PacketProtectionKeys a
    // follow-up batch was about to derive).
    private volatile boolean taskInFlight;
    private boolean deferring;
    private List<Runnable> deferredCallbacks;

    QuicHandshakeAsyncOffload(QuicTlsEngineListener listener) {
        this.listener = listener;
    }

    /**
     * Whether a batch is currently running on a crypto thread. Callers
     * (the concrete {@code QuicTlsEngine}) must queue further received
     * CRYPTO data rather than starting a second concurrent batch while
     * this is true -- Agent15's engines are not safe for concurrent use.
     *
     * @return true if a batch is in flight
     */
    boolean isBusy() {
        return taskInFlight;
    }

    /**
     * Routes a {@code QuicTlsEngineListener} callback: run immediately if
     * called from outside an in-flight batch (the ordinary loop-thread
     * case), or queued for replay-in-order on the loop thread once the
     * current batch completes, if called from inside {@link
     * BatchProcessor#process} (i.e. from the crypto thread).
     *
     * @param call the listener callback to run or defer
     */
    void dispatch(Runnable call) {
        if (deferring) {
            deferredCallbacks.add(call);
        } else {
            call.run();
        }
    }

    /**
     * Runs {@code processor} on {@link CryptoExecutor}, deferring any
     * listener callbacks it triggers until it completes, then replays
     * them in order and runs {@code onDone} -- all back on the loop
     * thread. Falls back to running {@code processor} synchronously, on
     * the calling thread, if no {@code CryptoExecutor} is available
     * (e.g. {@code Gumdrop} has not been started, as in unit tests that
     * drive these engines directly).
     *
     * @param level the encryption level this batch is processing, for
     *              error reporting
     * @param processor the Agent15 processing to run
     * @param onDone invoked (on the loop thread) once the batch --
     *               including replay of its deferred callbacks -- has
     *               finished, successfully or not
     */
    void submit(final EncryptionLevel level, final BatchProcessor processor, final CompletionHandler onDone) {
        taskInFlight = true;
        final Callable<List<Runnable>> op = new Callable<List<Runnable>>() {
            @Override
            public List<Runnable> call() {
                List<Runnable> callbacks = new ArrayList<Runnable>();
                deferredCallbacks = callbacks;
                deferring = true;
                try {
                    processor.process();
                } catch (final TlsProtocolException | IOException e) {
                    callbacks.add(new Runnable() {
                        @Override
                        public void run() {
                            listener.cryptoProcessingFailed(level, e);
                        }
                    });
                } finally {
                    deferring = false;
                    deferredCallbacks = null;
                }
                return callbacks;
            }
        };
        CryptoExecutor.Callback<List<Runnable>> callback = new CryptoExecutor.Callback<List<Runnable>>() {
            @Override
            public void completed(List<Runnable> callbacks) {
                // Deferred callbacks (cryptoDataReady, handshakeFinished,
                // etc.) must have applied their effects before taskInFlight
                // can be observed false, and -- issue #351 -- so must any
                // follow-up batch onDone starts synchronously (e.g.
                // QuicTlsClientEngine/ServerEngine's drainPendingFrames
                // dispatching a queued CRYPTO frame): only clear the flag
                // once onDone itself confirms nothing new started, rather
                // than clearing it first and correcting afterward, or a
                // concurrent poller (a test synchronizing on isBusy(), or
                // any real caller) could observe "idle" in between and act
                // on handshake state the follow-up batch was still about
                // to produce.
                for (Runnable r : callbacks) {
                    r.run();
                }
                if (!onDone.onBatchDone()) {
                    taskInFlight = false;
                }
            }

            @Override
            public void failed(Throwable error) {
                LOGGER.log(Level.SEVERE, "QUIC handshake delegated processing failed", error);
                listener.cryptoProcessingFailed(level, error);
                if (!onDone.onBatchDone()) {
                    taskInFlight = false;
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
        exec.submit(new Executor() {
            @Override
            public void execute(Runnable command) {
                listener.execute(command);
            }
        }, op, callback);
    }
}
