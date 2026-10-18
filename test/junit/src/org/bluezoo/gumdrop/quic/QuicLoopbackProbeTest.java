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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderPrefix;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.junit.Test;

/**
 * Issue 568: a Probe Timeout probe carries the oldest unacknowledged data
 * in its packet number space, not just a PING (RFC 9002 section 6.2.4),
 * and may do so even when the congestion window is full (section 7.5),
 * so that the probe itself repairs the loss rather than costing a round
 * trip to find out that the data must be sent again.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackProbeTest {

    private static final int PAYLOAD = 20000;

    @Test
    public void probeCarriesUnacknowledgedStreamDataEvenWithTheWindowFull() throws Exception {
        final QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
        long now = System.nanoTime();
        client.conn.frozenNanos = now;
        server.conn.frozenNanos = now;

        // A full window of data leaves and is lost.
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !toServer;
            }
        };
        Endpoint stream = client.conn.openStream(new Rec());
        int before = lb.toServerLog.size();
        stream.send(ByteBuffer.wrap(new byte[PAYLOAD]));
        lb.pump();
        int inFlight = lb.toServerLog.size() - before;
        assertTrue("a window's worth in flight: " + inFlight, inFlight >= 8);

        // The Probe Timeout fires: the probe is the next datagram.
        lb.filter = null;
        client.conn.clockOffsetMillis += 1500;
        int beforeProbe = lb.toServerLog.size();
        QuicForger.invoke(client.conn, "onLossDetectionTimeout");
        assertTrue("a probe was sent", lb.toServerLog.size() > beforeProbe);
        byte[] probe = lb.toServerLog.get(beforeProbe);
        // A PING with an IMMEDIATE_ACK is some 40 bytes; a probe that
        // repeats the oldest lost chunk is a full datagram.
        assertTrue("probe of " + probe.length + " bytes carries stream data", probe.length > 1000);
        lb.pump();
        assertTrue("the probe's data reached the server", server.bidi.recs.get(0).bytes > 0);
    }

    @Test
    public void handshakeProbeCarriesTheLostCryptoData() throws Exception {
        final QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        // The server's whole first flight is lost.
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        QuicConnection clientConn = QuicForger.clientConnection(lb.clientEngine);
        QuicConnection serverConn = QuicForger.serverConnection(lb.serverEngine);
        byte[] originalDcid = (byte[]) QuicForger.field(clientConn, "originalDcid");

        lb.filter = null;
        serverConn.clockOffsetMillis += 1500;
        int beforeProbe = lb.toClientLog.size();
        QuicForger.invoke(serverConn, "onLossDetectionTimeout");
        assertTrue("a probe was sent", lb.toClientLog.size() > beforeProbe);
        byte[] plaintext = longHeaderPlaintext(lb.toClientLog.get(beforeProbe), serverConn, originalDcid);
        int payload = plaintext.length;
        while (payload > 0 && plaintext[payload - 1] == 0) {
            payload--; // trailing PADDING
        }
        // An ACK and a PING come to a couple of dozen bytes; a CRYPTO
        // frame of the lost flight is far longer.
        assertTrue("probe payload of " + payload + " bytes carries CRYPTO", payload > 100);
    }

    // A server that hits the anti-amplification limit has not sent what it
    // withholds, and must send it, in order, once the client's next
    // datagram raises the limit. It counted the withheld CRYPTO data as
    // sent, so the flight resumed after a gap (kwik gave up with
    // CRYPTO_BUFFER_EXCEEDED) or not at all.
    @Test
    public void withheldHandshakeDataIsSentOnceTheLimitRises() throws Exception {
        int saved = QuicConnection.amplificationFactor;
        QuicConnection.amplificationFactor = 1;
        try {
            final QuicLoopback lb = new QuicLoopback();
            lb.startFactories();
            ConnCapture server = new ConnCapture();
            lb.startServer(server);
            ConnCapture client = new ConnCapture();
            lb.startClient(null, client);
            lb.pump();
            QuicConnection clientConn = QuicForger.clientConnection(lb.clientEngine);

            // The client's probes (padded Initials) raise the limit.
            for (int i = 0; i < 4 && !clientConn.isEstablished(); i++) {
                clientConn.clockOffsetMillis += 1500L << i;
                QuicForger.invoke(clientConn, "onLossDetectionTimeout");
                lb.pump();
            }
            assertTrue("the held-back data followed", clientConn.isEstablished());
        } finally {
            QuicConnection.amplificationFactor = saved;
        }
    }

    // Opens the long-header packet at the start of `datagram` sent by
    // `sender`: an Initial with the Initial keys both sides derive from the
    // client's first Destination Connection ID (RFC 9001 section 5.2), a
    // Handshake packet with the sender's own Handshake keys.
    private static byte[] longHeaderPlaintext(byte[] datagram, QuicConnection sender, byte[] originalDcid)
            throws Exception {
        LongHeaderPrefix prefix = LongHeaderCodec.parsePrefix(datagram);
        PacketProtectionKeys keys;
        if (prefix.getPacketType() == LongHeaderCodec.TYPE_INITIAL) {
            Method derive = QuicConnection.class.getDeclaredMethod("deriveInitialKeys", QuicVersion.class,
                    byte[].class, CryptoServices.class);
            derive.setAccessible(true);
            keys = ((PacketProtectionKeys[]) derive.invoke(null, sender.getVersion(), originalDcid,
                    new CryptoServices()))[1];
        } else {
            assertEquals(LongHeaderCodec.TYPE_HANDSHAKE, prefix.getPacketType());
            Map<?, ?> send = (Map<?, ?>) QuicForger.field(sender, "sendKeys");
            keys = (PacketProtectionKeys) send.get(org.bluezoo.gumdrop.quic.tls.EncryptionLevel.HANDSHAKE);
        }
        int pnOffset = prefix.getPacketNumberOffset();
        int packetLength = pnOffset + (int) prefix.getRemainingLength();
        byte[] packet = new byte[packetLength];
        System.arraycopy(datagram, 0, packet, 0, packetLength);
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, sample.length);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        PacketProtection.xorFirstByte(packet, mask, true);
        int pnLength = (packet[0] & 0x03) + 1;
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);
        long packetNumber = 0;
        for (int i = 0; i < pnLength; i++) {
            packetNumber = (packetNumber << 8) | (packet[pnOffset + i] & 0xff);
        }
        int headerLength = pnOffset + pnLength;
        byte[] header = new byte[headerLength];
        System.arraycopy(packet, 0, header, 0, headerLength);
        byte[] ciphertext = new byte[packetLength - headerLength];
        System.arraycopy(packet, headerLength, ciphertext, 0, ciphertext.length);
        return PacketProtection.open(keys, packetNumber, header, ciphertext);
    }
}
