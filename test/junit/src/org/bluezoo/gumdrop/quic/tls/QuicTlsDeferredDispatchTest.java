/*
 * QuicTlsDeferredDispatchTest.java
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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.tls.AlertDescription;
import org.bluezoo.gumdrop.tls.SessionTicket;
import org.bluezoo.gumdrop.tls.TlsProtocolError;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QuicTlsDeferredDispatch} and the failure reporting
 * of {@link QuicHandshakeAsyncOffload}: events raised outside a handshake
 * batch are delivered immediately, events raised inside one are deferred
 * until the batch completes, and exceeding a slot pool fails the batch.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicTlsDeferredDispatchTest {

    private static final class Recorder implements QuicTlsDeferredDispatch.Target, QuicTlsEngineListener {
        final List<String> events = new ArrayList<String>();
        final List<Throwable> failures = new ArrayList<Throwable>();

        @Override
        public void deliverCryptoData(EncryptionLevel level, long offset, byte[] data) {
            events.add("crypto:" + level + ":" + offset + ":" + data.length);
        }

        @Override
        public void deliverHandshakeSecretsAvailable() {
            events.add("secrets");
        }

        @Override
        public void deliverHandshakeFinished() {
            events.add("finished");
        }

        @Override
        public void deliverTransportParameters(TransportParameters parameters) {
            events.add("tp");
        }

        @Override
        public void deliverCryptoProcessingFailed(EncryptionLevel level, Throwable cause) {
            events.add("failed:" + level);
        }

        @Override
        public void deliverEarlySecretsAvailable() {
            events.add("early");
        }

        @Override
        public void deliverEarlyDataOutcomeKnown(boolean accepted) {
            events.add("outcome:" + accepted);
        }

        @Override
        public void deliverNewSessionTicketReceived(SessionTicket ticket) {
            events.add("ticket");
        }

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
        public void newSessionTicketReceived(SessionTicket ticket) {
        }

        @Override
        public void earlyDataOutcomeKnown(boolean accepted) {
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public void cryptoProcessingFailed(EncryptionLevel level, Throwable cause) {
            failures.add(cause);
        }
    }

    private final Recorder recorder = new Recorder();
    private final QuicHandshakeAsyncOffload offload = new QuicHandshakeAsyncOffload(recorder);
    private final QuicTlsDeferredDispatch dispatch = new QuicTlsDeferredDispatch(offload, recorder);

    private void runInBatch(final Runnable body) {
        offload.submit(EncryptionLevel.INITIAL, new QuicHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
                body.run();
            }
        }, new QuicHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                return false;
            }
        });
    }

    private TlsProtocolError anError() {
        return new TlsProtocolError(AlertDescription.UNEXPECTED_MESSAGE, "bad");
    }

    @Test
    public void testEventsOutsideBatchAreImmediate() {
        assertFalse(offload.isDeferring());
        dispatch.initialCryptoData(0, new byte[3]);
        dispatch.handshakeCryptoData(3, new byte[2]);
        dispatch.handshakeSecretsAvailable();
        dispatch.handshakeFinished();
        dispatch.transportParameters(new TransportParameters());
        dispatch.protocolError(EncryptionLevel.HANDSHAKE, anError());
        dispatch.earlySecretsAvailable();
        dispatch.earlyDataOutcomeKnown(true);
        dispatch.newSessionTicketReceived(null);
        List<String> expected = new ArrayList<String>();
        expected.add("crypto:INITIAL:0:3");
        expected.add("crypto:HANDSHAKE:3:2");
        expected.add("secrets");
        expected.add("finished");
        expected.add("tp");
        expected.add("failed:HANDSHAKE");
        expected.add("early");
        expected.add("outcome:true");
        expected.add("ticket");
        assertEquals(expected, recorder.events);
    }

    @Test
    public void testEventsInsideBatchAreDeferredUntilItCompletes() {
        final List<Integer> eventCountDuringBatch = new ArrayList<Integer>();
        runInBatch(new Runnable() {
            @Override
            public void run() {
                dispatch.resetSlots();
                dispatch.initialCryptoData(0, new byte[1]);
                dispatch.handshakeCryptoData(0, new byte[1]);
                dispatch.handshakeSecretsAvailable();
                dispatch.handshakeFinished();
                dispatch.transportParameters(new TransportParameters());
                dispatch.protocolError(EncryptionLevel.INITIAL, anError());
                dispatch.earlySecretsAvailable();
                dispatch.earlyDataOutcomeKnown(false);
                dispatch.newSessionTicketReceived(null);
                eventCountDuringBatch.add(Integer.valueOf(recorder.events.size()));
            }
        });
        assertEquals(0, eventCountDuringBatch.get(0).intValue());
        assertEquals(9, recorder.events.size());
        assertEquals("crypto:INITIAL:0:1", recorder.events.get(0));
        assertEquals("outcome:false", recorder.events.get(7));
        assertTrue(recorder.failures.isEmpty());
    }

    @Test
    public void testSlotsReusedAfterReset() {
        runInBatch(new Runnable() {
            @Override
            public void run() {
                for (int round = 0; round < 3; round++) {
                    dispatch.resetSlots();
                    for (int i = 0; i < 12; i++) {
                        dispatch.initialCryptoData(i, new byte[1]);
                        dispatch.handshakeCryptoData(i, new byte[1]);
                    }
                    for (int i = 0; i < 2; i++) {
                        dispatch.transportParameters(new TransportParameters());
                        dispatch.protocolError(EncryptionLevel.HANDSHAKE, anError());
                    }
                    for (int i = 0; i < 4; i++) {
                        dispatch.newSessionTicketReceived(null);
                    }
                }
            }
        });
        assertTrue(recorder.failures.isEmpty());
        assertEquals(3 * (24 + 4 + 4), recorder.events.size());
    }

    private void assertOverflowFails(final Runnable overflow) {
        runInBatch(new Runnable() {
            @Override
            public void run() {
                dispatch.resetSlots();
                overflow.run();
            }
        });
        assertEquals(1, recorder.failures.size());
        assertTrue(recorder.failures.get(0) instanceof IllegalStateException);
    }

    @Test
    public void testInitialCryptoSlotOverflowFailsBatch() {
        assertOverflowFails(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 13; i++) {
                    dispatch.initialCryptoData(i, new byte[1]);
                }
            }
        });
    }

    @Test
    public void testHandshakeCryptoSlotOverflowFailsBatch() {
        assertOverflowFails(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 13; i++) {
                    dispatch.handshakeCryptoData(i, new byte[1]);
                }
            }
        });
    }

    @Test
    public void testTransportParametersSlotOverflowFailsBatch() {
        assertOverflowFails(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 3; i++) {
                    dispatch.transportParameters(new TransportParameters());
                }
            }
        });
    }

    @Test
    public void testProtocolErrorSlotOverflowFailsBatch() {
        assertOverflowFails(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 3; i++) {
                    dispatch.protocolError(EncryptionLevel.HANDSHAKE, anError());
                }
            }
        });
    }

    @Test
    public void testSessionTicketSlotOverflowFailsBatch() {
        assertOverflowFails(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 5; i++) {
                    dispatch.newSessionTicketReceived(null);
                }
            }
        });
    }

    @Test
    public void testEarlyDataOutcomeTwiceInOneStepFailsBatch() {
        assertOverflowFails(new Runnable() {
            @Override
            public void run() {
                dispatch.earlyDataOutcomeKnown(true);
                dispatch.earlyDataOutcomeKnown(false);
            }
        });
    }

}
