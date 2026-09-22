/*
 * QuicConnectionStreamDrainPerformanceTest.java
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

package org.bluezoo.gumdrop.quic;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngine;
import org.bluezoo.gumdrop.quic.tls.StreamReassembler;

/**
 * Wall-clock regression test for {@code QuicConnection.buildProtectedPacket}
 * / {@code buildZeroRttProtectedPacket}'s STREAM-chunk drain: before the
 * fix, the queued chunks actually sent this flush were removed from a
 * stream's still-pending list with {@code List.removeAll(Collection)},
 * which is an O(n) {@code contains()} scan per element of the pending
 * list -- O(n*m) total to drain {@code m} of {@code n} queued chunks --
 * even though the drained chunks ({@code drainEligibleStreamChunks}'
 * {@code toSend}) are always a strict prefix of the pending list in
 * send order. The fix truncates that known prefix directly
 * ({@code queued.subList(0, m).clear()}), an O(m) bulk shift.
 *
 * <p>A real {@code QuicConnection} normally flushes on every {@code
 * queueStreamData} call (see {@code QuicEngine#requestFlush}), which
 * would drain each chunk the moment it's queued and never let a large
 * backlog form through the public send API alone. The realistic
 * trigger for a large backlog is many stream writes queued from within
 * one received datagram's processing, inside the {@code suppressFlush}
 * window {@code receive()} holds open so a whole flight's worth of
 * frames coalesces into one flush instead of one datagram per frame
 * (see {@code QuicConnection}'s own field comment on {@code
 * suppressFlush}) -- this test reproduces that backlog directly via
 * reflection on that same private flag, rather than reconstructing a
 * full receive-path scenario, since the flag's effect (many chunks
 * queued, one flush) is exactly what matters here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicConnectionStreamDrainPerformanceTest {

    /** Returns fixed, non-cryptographically-meaningful secrets -- this test never decrypts anything. */
    private static final class FakeTlsEngine implements QuicTlsEngine {
        @Override
        public void receiveCryptoData(EncryptionLevel level, long offset, ByteBuffer data)
                throws StreamReassembler.BufferLimitExceededException {
        }

        @Override
        public byte[] getClientHandshakeTrafficSecret() {
            return new byte[32];
        }

        @Override
        public byte[] getServerHandshakeTrafficSecret() {
            return new byte[32];
        }

        @Override
        public byte[] getClientApplicationTrafficSecret() {
            return new byte[32];
        }

        @Override
        public byte[] getServerApplicationTrafficSecret() {
            return new byte[32];
        }

        @Override
        public byte[] getClientEarlyTrafficSecret() {
            return new byte[32];
        }
    }

    private static void setSuppressFlush(QuicConnection conn, boolean value) throws Exception {
        Field field = QuicConnection.class.getDeclaredField("suppressFlush");
        field.setAccessible(true);
        field.set(conn, Boolean.valueOf(value));
    }

    @Test(timeout = 60000)
    public void manyQueuedChunksDrainedInOneFlushStayLinear() throws Exception {
        QuicTransportFactory factory = new QuicTransportFactory();
        QuicEngine engine = new QuicEngine(factory, true);
        engine.init(new QuicDatagramPath() {
            @Override
            public int send(SocketAddress address, ByteBuffer packet) {
                return packet.remaining();
            }

            @Override
            public SocketAddress getLocalAddress() {
                return null;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        });

        byte[] cid = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 4433);
        QuicConnection conn = new QuicConnection(engine, false, addr, addr, cid, cid, cid,
                new TransportParameters(), new byte[32]);

        TransportParameters peer = new TransportParameters();
        peer.setInitialMaxData(Long.MAX_VALUE / 2);
        peer.setInitialMaxStreamDataBidiRemote(Long.MAX_VALUE / 2);
        peer.setInitialMaxStreamsBidi(100);
        conn.seedRememberedTransportParameters(peer);

        conn.setTlsEngine(new FakeTlsEngine());
        conn.handshakeFinished(); // derives real ONE_RTT keys without a real TLS handshake

        // Bypasses openStream()/getSecurityInfo() (which requires a real
        // QuicTlsClientEngine, not just the QuicTlsEngine interface, to
        // build a SecurityInfo) -- queueStreamData itself doesn't touch
        // the stream table at all, only pendingStream/offset tracking,
        // which is all this benchmark needs.
        long streamId = 0; // client-initiated bidi (RFC 9000 section 2.1)

        int chunkCount = 200000;
        byte[] payload = new byte[32];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        // Queue every chunk with flushing suppressed, so they all pile
        // up on the stream's pending list -- reproducing the backlog a
        // real flight-coalescing receive() window builds -- then flush
        // once, draining the whole backlog in a single
        // buildProtectedPacket call, which is what exercises the fixed
        // line.
        setSuppressFlush(conn, true);
        for (int i = 0; i < chunkCount; i++) {
            conn.queueStreamData(streamId, ByteBuffer.wrap(payload), false);
        }
        setSuppressFlush(conn, false);

        long start = System.nanoTime();
        conn.flush();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue(chunkCount + " queued " + payload.length + "-byte STREAM chunks drained in one flush took "
                + elapsedMs + "ms -- expected the drained prefix to be truncated in one bulk shift instead of "
                + "removeAll's O(n*m) contains() scan per pending chunk",
                elapsedMs < 2000);
    }

}
