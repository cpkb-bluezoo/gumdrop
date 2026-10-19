/*
 * ClientConnectApplyTest.java
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderPrefix;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.junit.Test;

/**
 * Issue 571: a server's HANDSHAKE_DONE frame is sent again until it is
 * acknowledged (RFC 9000 section 13.3). It used to be queued once, so if
 * the packet carrying it was lost the client never confirmed the
 * handshake, kept its Handshake keys and timers, and had every Handshake
 * retransmission dropped by a server that had already discarded those
 * keys.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackHandshakeDoneTest {

    // Whether a datagram holds a short-header (1-RTT) packet, on its own
    // or after coalesced long-header ones: the server's first such datagram
    // is the one that carries HANDSHAKE_DONE.
    private static boolean carriesOneRtt(byte[] datagram) {
        int offset = 0;
        while (offset < datagram.length) {
            if ((datagram[offset] & 0x80) == 0) {
                return true;
            }
            LongHeaderPrefix prefix;
            try {
                prefix = LongHeaderCodec.parsePrefix(datagram, offset);
            } catch (IllegalArgumentException e) {
                return false;
            }
            offset = prefix.getPacketNumberOffset() + (int) prefix.getRemainingLength();
        }
        return false;
    }

    @Test
    public void lostHandshakeDoneIsSentAgainUntilAcknowledged() throws Exception {
        final QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        final boolean[] dropped = new boolean[1];
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                if (toServer || dropped[0] || !carriesOneRtt(datagram)) {
                    return true;
                }
                dropped[0] = true;
                return false;
            }
        };
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue("the datagram with HANDSHAKE_DONE was dropped", dropped[0]);
        QuicConnection clientConn = QuicForger.clientConnection(lb.clientEngine);
        QuicConnection serverConn = QuicForger.serverConnection(lb.serverEngine);
        assertFalse(((Boolean) QuicForger.field(clientConn, "handshakeConfirmed")).booleanValue());

        // Loss detection on the server: probe, acknowledgement, loss
        // declared, and the frame must come round again.
        for (int round = 0; round < 6 && !((Boolean) QuicForger.field(clientConn, "handshakeConfirmed")).booleanValue(); round++) {
            serverConn.clockOffsetMillis += 1500;
            clientConn.clockOffsetMillis += 1500;
            QuicForger.invoke(serverConn, "onLossDetectionTimeout");
            lb.pump();
        }
        assertTrue("client confirmed the handshake", ((Boolean) QuicForger.field(clientConn, "handshakeConfirmed")).booleanValue());
    }

    // Handshake keys the server still holds: while HANDSHAKE_DONE is
    // unacknowledged a client that never saw it keeps sending at the
    // Handshake level, possibly from a new address, and only a server
    // that can still read those packets can find that out.
    private static boolean serverReadsHandshake(QuicConnection serverConn) throws Exception {
        java.util.Map<?, ?> keys = (java.util.Map<?, ?>) QuicForger.field(serverConn, "recvKeys");
        return keys.containsKey(EncryptionLevel.HANDSHAKE);
    }

    @Test
    public void serverKeepsHandshakeKeysUntilHandshakeDoneIsAcknowledged() throws Exception {
        final QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        final boolean[] dropped = new boolean[1];
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                if (toServer || dropped[0] || !carriesOneRtt(datagram)) {
                    return true;
                }
                dropped[0] = true;
                return false;
            }
        };
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue("the datagram with HANDSHAKE_DONE was dropped", dropped[0]);
        QuicConnection clientConn = QuicForger.clientConnection(lb.clientEngine);
        QuicConnection serverConn = QuicForger.serverConnection(lb.serverEngine);
        assertTrue("server can still read Handshake packets", serverReadsHandshake(serverConn));

        for (int round = 0; round < 6 && !((Boolean) QuicForger.field(clientConn, "handshakeConfirmed")).booleanValue(); round++) {
            serverConn.clockOffsetMillis += 1500;
            clientConn.clockOffsetMillis += 1500;
            QuicForger.invoke(serverConn, "onLossDetectionTimeout");
            lb.pump();
        }
        lb.pump();
        assertFalse("keys discarded once HANDSHAKE_DONE was acknowledged", serverReadsHandshake(serverConn));
    }
}
