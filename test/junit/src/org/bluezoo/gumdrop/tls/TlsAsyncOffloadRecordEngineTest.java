/*
 * TlsAsyncOffloadRecordEngineTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.tls.TlsBLoopbackSupport.Peer;
import org.bluezoo.gumdrop.tls.TlsBLoopbackSupport.Sink;

/**
 * Drives the TLS 1.3 and 1.2 record engines with a deterministic, manually
 * stepped {@link HandshakeAsyncOffload}: handshake work is queued and run
 * only when the test says so, exercising the busy / deferred-callback /
 * resume-inbound paths that production offload threads reach.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsAsyncOffloadRecordEngineTest {

    /** A queue-backed offload: batches run when {@link #drain()} is called. */
    private static final class ManualOffload implements HandshakeAsyncOffload {
        private final Object lock = new Object();
        private final List<Object[]> queue = new ArrayList<Object[]>();
        private boolean busy;
        private boolean deferring;
        private List<Runnable> deferred = new ArrayList<Runnable>();
        private Runnable idle;
        boolean failNext;
        int batches;

        @Override
        public Object lock() {
            return lock;
        }

        @Override
        public boolean isBusy() {
            return busy;
        }

        @Override
        public boolean isDeferring() {
            return deferring;
        }

        @Override
        public void dispatch(Runnable call) {
            if (deferring) {
                deferred.add(call);
            } else {
                call.run();
            }
        }

        @Override
        public void submit(BatchProcessor processor, CompletionHandler onDone, FailureHandler onFailure) {
            busy = true;
            queue.add(new Object[] {processor, onDone, onFailure});
        }

        @Override
        public void setIdleListener(Runnable listener) {
            idle = listener;
        }

        boolean hasWork() {
            return !queue.isEmpty();
        }

        /** Runs queued batches (including ones queued by completion handlers) to completion. */
        void drain() {
            while (!queue.isEmpty()) {
                Object[] job = queue.remove(0);
                BatchProcessor processor = (BatchProcessor) job[0];
                CompletionHandler onDone = (CompletionHandler) job[1];
                FailureHandler onFailure = (FailureHandler) job[2];
                batches++;
                if (failNext) {
                    failNext = false;
                    onFailure.failed(new IllegalStateException("simulated"));
                } else {
                    deferred = new ArrayList<Runnable>();
                    deferring = true;
                    try {
                        processor.process();
                    } finally {
                        deferring = false;
                    }
                    List<Runnable> run = deferred;
                    for (int i = 0; i < run.size(); i++) {
                        run.get(i).run();
                    }
                }
                boolean more = onDone.onBatchDone();
                if (!more) {
                    busy = false;
                    if (idle != null) {
                        idle.run();
                    }
                }
            }
        }
    }

    private static void settle(Sink cs, Peer client, ManualOffload co, Sink ss, Peer server,
            ManualOffload so, int chunk) {
        for (int round = 0; round < 500; round++) {
            boolean moved = false;
            if (co.hasWork()) {
                co.drain();
                moved = true;
            }
            if (so.hasWork()) {
                so.drain();
                moved = true;
            }
            byte[] c = cs.takeOutbound();
            if (c.length > 0) {
                TlsBLoopbackSupport.feedChunked(server, c, chunk, ss);
                moved = true;
            }
            byte[] s = ss.takeOutbound();
            if (s.length > 0) {
                TlsBLoopbackSupport.feedChunked(client, s, chunk, cs);
                moved = true;
            }
            if (!moved) {
                return;
            }
        }
        throw new IllegalStateException("did not settle");
    }

    private HandshakeConfig tls13Server() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        return c;
    }

    private HandshakeConfig tls13Client() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        return c;
    }

    private Tls12HandshakeConfig tls12Server() throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        return c;
    }

    private Tls12HandshakeConfig tls12Client() throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        return c;
    }

    private void tls13RoundTrip(int chunk) throws Exception {
        ManualOffload co = new ManualOffload();
        ManualOffload so = new ManualOffload();
        TlsRecordEngine client = new TlsRecordEngine(tls13Client(), co);
        TlsRecordEngine server = new TlsRecordEngine(tls13Server(), so);
        Sink cs = new Sink();
        Sink ss = new Sink();
        Peer cp = TlsBLoopbackSupport.peer(client);
        Peer sp = TlsBLoopbackSupport.peer(server);
        server.start(ss);
        client.start(cs);
        settle(cs, cp, co, ss, sp, so, chunk);
        assertTrue(cs.events.toString(), client.isComplete());
        assertTrue(ss.events.toString(), server.isComplete());
        assertTrue(co.batches > 1);
        byte[] msg = new byte[5000];
        for (int i = 0; i < msg.length; i++) {
            msg[i] = (byte) i;
        }
        client.sendApplicationData(msg, cs);
        server.sendApplicationData(msg, ss);
        settle(cs, cp, co, ss, sp, so, chunk);
        assertArrayEquals(msg, ss.appBytes());
        assertArrayEquals(msg, cs.appBytes());
        assertTrue(client.requestKeyUpdate(cs, true));
        settle(cs, cp, co, ss, sp, so, chunk);
        client.sendApplicationData(msg, cs);
        settle(cs, cp, co, ss, sp, so, chunk);
        assertEquals(msg.length * 2, ss.appBytes().length);
        client.sendCloseNotify(cs);
        settle(cs, cp, co, ss, sp, so, chunk);
        assertTrue(ss.peerClosed);
    }

    @Test
    public void tls13HandshakeThroughOffload() throws Exception {
        tls13RoundTrip(0);
    }

    @Test
    public void tls13HandshakeThroughOffloadByteAtATime() throws Exception {
        tls13RoundTrip(1);
    }

    @Test
    public void tls13HandshakeThroughOffloadInMediumChunks() throws Exception {
        tls13RoundTrip(97);
    }

    @Test
    public void tls13CompatChangeCipherSpecBeforeFinishedDoesNotStallOffloadedServer() throws Exception {
        ManualOffload co = new ManualOffload();
        ManualOffload so = new ManualOffload();
        TlsRecordEngine client = new TlsRecordEngine(tls13Client(), co);
        TlsRecordEngine server = new TlsRecordEngine(tls13Server(), so);
        Sink cs = new Sink();
        Sink ss = new Sink();
        server.start(ss);
        client.start(cs);
        co.drain();
        server.feedCiphertext(cs.takeOutbound(), ss);
        so.drain();
        client.feedCiphertext(ss.takeOutbound(), cs);
        co.drain();
        byte[] finished = cs.takeOutbound();
        byte[] ccs = new byte[] {20, 3, 3, 0, 1, 1};
        byte[] both = new byte[ccs.length + finished.length];
        System.arraycopy(ccs, 0, both, 0, ccs.length);
        System.arraycopy(finished, 0, both, ccs.length, finished.length);
        server.feedCiphertext(both, ss);
        so.drain();
        assertNull(ss.error);
        assertTrue(server.isComplete());
    }

    @Test
    public void tls13OffloadFailureFailsTheConnection() throws Exception {
        ManualOffload co = new ManualOffload();
        TlsRecordEngine client = new TlsRecordEngine(tls13Client(), co);
        Sink cs = new Sink();
        co.failNext = true;
        client.start(cs);
        co.drain();
        assertNotNull(cs.error);
        assertEquals(AlertDescription.INTERNAL_ERROR, cs.error.getAlert());
        assertFalse(client.isComplete());
    }

    @Test
    public void tls13InputWhileBusyIsBufferedUntilIdle() throws Exception {
        ManualOffload co = new ManualOffload();
        ManualOffload so = new ManualOffload();
        TlsRecordEngine client = new TlsRecordEngine(tls13Client(), co);
        TlsRecordEngine server = new TlsRecordEngine(tls13Server(), so);
        Sink cs = new Sink();
        Sink ss = new Sink();
        server.start(ss);
        client.start(cs);
        co.drain();
        byte[] hello = cs.takeOutbound();
        // deliver the ClientHello in two halves; second half arrives while the first batch is queued
        int half = hello.length / 2;
        server.feedCiphertext(hello, 0, half, ss);
        server.feedCiphertext(hello, half, hello.length - half, ss);
        so.drain();
        assertNull(ss.error);
        assertTrue(ss.outbound.size() > 0);
    }

    private void tls12RoundTrip(int chunk) throws Exception {
        ManualOffload co = new ManualOffload();
        ManualOffload so = new ManualOffload();
        Tls12RecordEngine client = new Tls12RecordEngine(tls12Client(), co);
        Tls12RecordEngine server = new Tls12RecordEngine(tls12Server(), so);
        Sink cs = new Sink();
        Sink ss = new Sink();
        Peer cp = TlsBLoopbackSupport.peer(client);
        Peer sp = TlsBLoopbackSupport.peer(server);
        server.start(ss);
        client.start(cs);
        settle(cs, cp, co, ss, sp, so, chunk);
        assertTrue(cs.events.toString(), client.isComplete());
        assertTrue(ss.events.toString(), server.isComplete());
        byte[] msg = new byte[3000];
        for (int i = 0; i < msg.length; i++) {
            msg[i] = (byte) (i * 3);
        }
        client.sendApplicationData(msg, cs);
        server.sendApplicationData(msg, ss);
        settle(cs, cp, co, ss, sp, so, chunk);
        assertArrayEquals(msg, ss.appBytes());
        assertArrayEquals(msg, cs.appBytes());
        client.sendCloseNotify(cs);
        settle(cs, cp, co, ss, sp, so, chunk);
        assertTrue(ss.peerClosed);
    }

    @Test
    public void tls12HandshakeThroughOffload() throws Exception {
        tls12RoundTrip(0);
    }

    @Test
    public void tls12HandshakeThroughOffloadByteAtATime() throws Exception {
        tls12RoundTrip(1);
    }

    @Test
    public void tls12HandshakeThroughOffloadInMediumChunks() throws Exception {
        tls12RoundTrip(61);
    }

    @Test
    public void tls12OffloadFailureFailsTheConnection() throws Exception {
        ManualOffload co = new ManualOffload();
        Tls12RecordEngine client = new Tls12RecordEngine(tls12Client(), co);
        Sink cs = new Sink();
        co.failNext = true;
        client.start(cs);
        co.drain();
        assertNotNull(cs.error);
        assertFalse(client.isComplete());
    }

    @Test
    public void tls12SessionTicketResumptionThroughOffload() throws Exception {
        TicketKeys keys = new TicketKeys(new byte[16]);
        Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        for (int pass = 0; pass < 2; pass++) {
            Tls12HandshakeConfig cc = tls12Client();
            cc.setClientTicketStore(store);
            Tls12HandshakeConfig sc = tls12Server();
            sc.setTicketKeys(keys);
            ManualOffload co = new ManualOffload();
            ManualOffload so = new ManualOffload();
            Tls12RecordEngine client = new Tls12RecordEngine(cc, co);
            Tls12RecordEngine server = new Tls12RecordEngine(sc, so);
            Sink cs = new Sink();
            Sink ss = new Sink();
            server.start(ss);
            client.start(cs);
            settle(cs, TlsBLoopbackSupport.peer(client), co, ss, TlsBLoopbackSupport.peer(server), so, 0);
            assertTrue(client.isComplete());
            assertEquals(pass == 1, client.isResumed());
        }
    }
}
