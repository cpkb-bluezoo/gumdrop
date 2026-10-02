/*
 * HandshakeAsyncSchedulerTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link HandshakeAsyncScheduler} and {@link HandshakeInput}
 * against a manually-driven {@link HandshakeAsyncOffload}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HandshakeAsyncSchedulerTest {

    /** An offload whose batches run only when the test says so. */
    private static final class ManualOffload implements HandshakeAsyncOffload {
        HandshakeAsyncOffload.BatchProcessor processor;
        HandshakeAsyncOffload.CompletionHandler done;
        boolean deferring;
        final List<Runnable> dispatched = new ArrayList<Runnable>();
        Runnable idleListener;

        @Override
        public Object lock() {
            return this;
        }

        @Override
        public boolean isBusy() {
            return processor != null;
        }

        @Override
        public boolean isDeferring() {
            return deferring;
        }

        @Override
        public void dispatch(Runnable call) {
            dispatched.add(call);
        }

        @Override
        public void submit(HandshakeAsyncOffload.BatchProcessor p,
                HandshakeAsyncOffload.CompletionHandler d, HandshakeAsyncOffload.FailureHandler f) {
            processor = p;
            done = d;
        }

        @Override
        public void setIdleListener(Runnable listener) {
            idleListener = listener;
        }

        /** Runs the in-flight batch and any batches its completion handler chains. */
        void runAll() {
            while (processor != null) {
                HandshakeAsyncOffload.BatchProcessor p = processor;
                HandshakeAsyncOffload.CompletionHandler d = done;
                processor = null;
                p.process();
                if (d.onBatchDone()) {
                    continue;
                }
            }
        }
    }

    private static final class RecordingRunner implements HandshakeAsyncScheduler.Runner {
        int starts;
        final List<List<HandshakeInput>> batches = new ArrayList<List<HandshakeInput>>();

        @Override
        public void runStart() {
            starts++;
        }

        @Override
        public void runInputs(List<HandshakeInput> inputs) {
            batches.add(new ArrayList<HandshakeInput>(inputs));
        }
    }

    private static final HandshakeAsyncOffload.FailureHandler IGNORE =
            new HandshakeAsyncOffload.FailureHandler() {
                @Override
                public void failed(Throwable error) {
                }
            };

    @Test
    public void withoutOffloadEverythingRunsInline() {
        RecordingRunner runner = new RecordingRunner();
        HandshakeAsyncScheduler s = new HandshakeAsyncScheduler(null, runner, IGNORE);
        assertFalse(s.isEnabled());
        assertFalse(s.isDeferring());
        assertFalse(s.isBusy());
        assertNotNull(s.lock());
        s.scheduleStart();
        assertEquals(1, runner.starts);
        s.scheduleMessage(new byte[] { 1 });
        List<byte[]> two = new ArrayList<byte[]>();
        two.add(new byte[] { 2 });
        two.add(new byte[] { 3 });
        s.scheduleMessages(two);
        assertEquals(2, runner.batches.size());
        assertEquals(2, runner.batches.get(1).size());
        final int[] ran = new int[1];
        s.dispatch(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertEquals(1, ran[0]);
        s.setOnIdle(new Runnable() {
            @Override
            public void run() {
                ran[0] += 10;
            }
        });
        s.notifyIdle();
        assertEquals(11, ran[0]);
    }

    @Test
    public void queuesWorkWhileBusyAndDrainsInOneBatch() {
        ManualOffload offload = new ManualOffload();
        RecordingRunner runner = new RecordingRunner();
        HandshakeAsyncScheduler s = new HandshakeAsyncScheduler(offload, runner, IGNORE);
        assertTrue(s.isEnabled());
        assertSame(offload, s.lock());
        s.scheduleMessage(new byte[] { 1 });
        assertTrue(s.isBusy());
        s.scheduleMessage(new byte[] { 2 });
        List<byte[]> more = new ArrayList<byte[]>();
        more.add(new byte[] { 3 });
        more.add(new byte[] { 4 });
        s.scheduleMessages(more);
        s.scheduleStart();
        assertEquals(0, runner.batches.size());
        offload.runAll();
        assertEquals(1, runner.starts);
        assertEquals(2, runner.batches.size());
        assertEquals(1, runner.batches.get(0).size());
        assertEquals(3, runner.batches.get(1).size());
        assertFalse(s.isBusy());
    }

    @Test
    public void startWhileIdleSubmitsStartBatchAndPendingStartRunsAfterBusy() {
        ManualOffload offload = new ManualOffload();
        RecordingRunner runner = new RecordingRunner();
        HandshakeAsyncScheduler s = new HandshakeAsyncScheduler(offload, runner, IGNORE);
        s.scheduleStart();
        assertEquals(0, runner.starts);
        offload.runAll();
        assertEquals(1, runner.starts);
        s.scheduleMessage(new byte[] { 9 });
        s.scheduleStart();
        offload.runAll();
        assertEquals(2, runner.starts);
        assertEquals(1, runner.batches.size());
    }

    @Test
    public void idleNotificationOnlyWhenNotBusy() {
        ManualOffload offload = new ManualOffload();
        RecordingRunner runner = new RecordingRunner();
        HandshakeAsyncScheduler s = new HandshakeAsyncScheduler(offload, runner, IGNORE);
        final int[] idle = new int[1];
        s.setOnIdle(new Runnable() {
            @Override
            public void run() {
                idle[0]++;
            }
        });
        s.scheduleMessage(new byte[] { 1 });
        s.notifyIdle();
        assertEquals(0, idle[0]);
        offload.runAll();
        s.notifyIdle();
        assertEquals(1, idle[0]);
        offload.deferring = true;
        assertTrue(s.isDeferring());
        final int[] ran = new int[1];
        s.dispatch(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertEquals(1, offload.dispatched.size());
    }

    @Test
    public void handshakeInputDispatchesEachKindToTheEngine() throws Exception {
        HandshakeConfig cfg = new HandshakeConfig(HandshakeRole.SERVER);
        HandshakeEngine engine = new HandshakeEngine(cfg);
        final List<String> errors = new ArrayList<String>();
        TlsEventSink sink = new TlsEventSink() {
            @Override
            public void handshakeDataReady(byte[] data) {
            }

            @Override
            public void handshakeSecretsReady() {
            }

            @Override
            public void applicationSecretsReady() {
            }

            @Override
            public void peerTransportParameters(byte[] parameters) {
            }

            @Override
            public void protocolError(TlsProtocolError err) {
                errors.add(err.getMessage());
            }

            @Override
            public void quicEarlyKeysReady(CipherSuite suite, byte[] secret) {
            }

            @Override
            public void earlyDataAccepted(boolean accepted) {
            }

            @Override
            public void sessionTicketReceived(SessionTicket ticket) {
            }
        };
        byte[] msg = new byte[] { 1, 0 };
        HandshakeInput in = HandshakeInput.message(msg);
        assertArrayEquals(msg, in.wholeMessage());
        in.dispatch(engine, sink);
        assertTrue(engine.isFailed());
        assertEquals(1, errors.size());
        HandshakeInput.streamBegin(new byte[] { 11, 0, 0, 1 }).dispatch(engine, sink);
        HandshakeInput.streamData(new byte[] { 0 }).dispatch(engine, sink);
        HandshakeInput.streamEnd().dispatch(engine, sink);
        assertTrue(engine.isFailed());
    }
}
