/*
 * QuicHandshakeAsyncOffloadTest.java
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import tech.kwik.agent15.NewSessionTicket;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for issue #351: a concurrent poller of {@link
 * QuicHandshakeAsyncOffload#isBusy} (e.g. {@code QuicTestPeer}'s
 * {@code awaitHandshakeProcessingIdle}) must never be able to observe
 * "idle" while a batch's completion handler is synchronously starting a
 * follow-up batch -- otherwise a caller can act on handshake state (such
 * as a {@code PacketProtectionKeys} the follow-up batch was about to
 * derive) before it actually exists.
 *
 * <p>The bug: the completion callback used to clear the busy flag
 * unconditionally, then call the completion handler, which for
 * {@code QuicTlsClientEngine}/{@code QuicTlsServerEngine} may itself
 * immediately submit another batch for a queued CRYPTO frame
 * ({@code drainPendingFrames}) -- leaving a real window where the flag
 * read false despite a follow-up batch being about to start. This exactly
 * matches the intermittent NPE in {@code QuicHandshakeAsyncOffloadTest}
 * (the higher-level end-to-end test in the {@code quic} package) where
 * {@code QuicTestPeer} built a packet with not-yet-installed keys.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicHandshakeAsyncOffloadTest {

    private Gumdrop gumdrop;

    @Before
    public void setUp() {
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
    }

    @After
    public void tearDown() {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    @Test(timeout = 10000)
    public void testIsBusyStaysTrueWhileCompletionHandlerStartsAFollowUpBatch() throws Exception {
        final QuicHandshakeAsyncOffload offload = new QuicHandshakeAsyncOffload(new NoopListener());
        final CountDownLatch completionRan = new CountDownLatch(1);
        final AtomicBoolean busyWhenFollowUpDecided = new AtomicBoolean();

        QuicHandshakeAsyncOffload.BatchProcessor noopBatch = new QuicHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
            }
        };

        offload.submit(EncryptionLevel.INITIAL, noopBatch, new QuicHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                // Mirrors QuicTlsClientEngine/ServerEngine's
                // dispatchFrame -> drainPendingFrames pattern: a queued
                // frame is dispatched immediately as a follow-up batch.
                // isBusy() must still read true right here, before this
                // method has even decided to resubmit -- a continuation
                // is about to start.
                busyWhenFollowUpDecided.set(offload.isBusy());
                offload.submit(EncryptionLevel.HANDSHAKE, noopBatch,
                        new QuicHandshakeAsyncOffload.CompletionHandler() {
                            @Override
                            public boolean onBatchDone() {
                                return false;
                            }
                        });
                completionRan.countDown();
                return true;
            }
        });

        assertTrue("completion handler should have run", completionRan.await(5, TimeUnit.SECONDS));
        assertTrue("isBusy() must still report true while the completion handler is "
                + "synchronously starting a follow-up batch -- a concurrent poller "
                + "(e.g. QuicTestPeer.awaitHandshakeProcessingIdle) must never be able "
                + "to observe idle in this window", busyWhenFollowUpDecided.get());
    }

    @Test(timeout = 10000)
    public void testIsBusyClearsOnceNoFollowUpBatchIsSubmitted() throws Exception {
        final QuicHandshakeAsyncOffload offload = new QuicHandshakeAsyncOffload(new NoopListener());
        final CountDownLatch completionRan = new CountDownLatch(1);

        QuicHandshakeAsyncOffload.BatchProcessor noopBatch = new QuicHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
            }
        };

        offload.submit(EncryptionLevel.INITIAL, noopBatch, new QuicHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                completionRan.countDown();
                return false;
            }
        });

        assertTrue("completion handler should have run", completionRan.await(5, TimeUnit.SECONDS));
        assertFalse("isBusy() must clear once the completion handler reports no follow-up batch",
                offload.isBusy());
    }

    private static final class NoopListener implements QuicTlsEngineListener {
        @Override
        public void cryptoDataReady(EncryptionLevel level, long offset, byte[] data) {
        }

        @Override
        public void handshakeSecretsAvailable() {
        }

        @Override
        public void handshakeFinished() {
        }

        @Override
        public void transportParametersReceived(TransportParameters transportParameters) {
        }

        @Override
        public void earlySecretsAvailable() {
        }

        @Override
        public void newSessionTicketReceived(NewSessionTicket ticket) {
        }

        @Override
        public void earlyDataOutcomeKnown(boolean accepted) {
        }

        @Override
        public void execute(Runnable task) {
            // Matches QuicTestPeer's own listener: no real event loop, so
            // the CryptoExecutor callback (delivered from a pool thread)
            // runs inline right there -- exactly the shape that exposes
            // the race this test targets.
            task.run();
        }

        @Override
        public void cryptoProcessingFailed(EncryptionLevel level, Throwable cause) {
        }
    }
}
