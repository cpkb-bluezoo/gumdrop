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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.junit.Test;

/**
 * Issue 565: a client addresses the server by the Source Connection ID
 * of the server's Initial packet (RFC 9000 section 7.2), whatever an
 * earlier Retry, or an earlier Initial that carried nothing but an
 * acknowledgement and echoed the client's own first choice, said. It
 * used to adopt the first long-header packet's value and never change
 * it again.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackServerConnectionIdTest {

    private static byte[] peerConnectionId(QuicConnection conn) throws Exception {
        return (byte[]) QuicForger.field(conn, "peerConnectionId");
    }

    private static void exchange(QuicLoopback lb, ConnCapture client, ConnCapture server) throws Exception {
        Rec reply = new Rec();
        Endpoint request = client.conn.openStream(reply);
        request.send(ByteBuffer.wrap(new byte[] {1}));
        request.close();
        lb.pump();
        Endpoint response = server.bidi.recs.get(0).endpoint;
        response.send(ByteBuffer.wrap(new byte[] {2, 3}));
        response.close();
        lb.pump();
        assertTrue("response delivered", reply.bytes == 2);
    }

    @Test
    public void serverInitialSourceIdIsAdoptedAfterARetryThatUsedAnotherOne() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setRequireRetry(true);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        Field fresh = QuicEngine.class.getDeclaredField("freshConnectionIdAfterRetry");
        fresh.setAccessible(true);
        fresh.setBoolean(lb.serverEngine, true);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
        byte[] retryScid = (byte[]) QuicForger.field(client.conn, "expectedRetrySourceConnectionId");
        byte[] serverScid = server.conn.getOurConnectionId();
        assertFalse("the server chose a fresh connection ID after its Retry", Arrays.equals(retryScid, serverScid));
        assertArrayEquals("client addresses the server's Initial source ID", serverScid, peerConnectionId(client.conn));
        exchange(lb, client, server);
    }

    @Test
    public void serverInitialSourceIdIsAdoptedWhenAnEarlierInitialEchoedOurOwn() throws Exception {
        final QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        // The server's whole first flight is lost; a forged acknowledgement-
        // only Initial, as a server that has only half a ClientHello might
        // send, arrives instead, with the client's own first Destination
        // Connection ID echoed as its Source Connection ID.
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        // The capture only learns of the connection once the handshake
        // completes; until then reach it through the engine.
        QuicConnection clientConn = QuicForger.clientConnection(lb.clientEngine);
        byte[] originalDcid = (byte[]) QuicForger.field(clientConn, "originalDcid");
        lb.injectToClient(forgeAckOnlyServerInitial(clientConn, originalDcid, originalDcid));
        lb.pump();
        assertArrayEquals("the echoed value is what the client has so far", originalDcid, peerConnectionId(clientConn));
        // The server recovers its first flight, under its real connection
        // ID: each probe timeout round sends a probe, whose acknowledgement
        // lets loss detection retransmit what was lost in that packet
        // number space, so several rounds are needed.
        lb.filter = null;
        QuicConnection serverConn = QuicForger.serverConnection(lb.serverEngine);
        for (int round = 0; round < 12 && !clientConn.isEstablished(); round++) {
            serverConn.clockOffsetMillis += 1500;
            clientConn.clockOffsetMillis += 1500;
            QuicForger.invoke(serverConn, "onLossDetectionTimeout");
            QuicForger.invoke(clientConn, "onLossDetectionTimeout");
            lb.pump();
        }
        assertTrue(clientConn.isEstablished());
        byte[] serverScid = serverConn.getOurConnectionId();
        assertFalse(Arrays.equals(originalDcid, serverScid));
        assertArrayEquals("client addresses the server's Initial source ID", serverScid, peerConnectionId(clientConn));
        exchange(lb, client, server);
    }

    @Test
    public void initialSourceConnectionIdParameterMustMatchTheServersInitial() throws Exception {
        QuicConnection client = QuicConnectionTestFactory.create(false);
        byte[] fromInitial = new byte[] {9, 9, 9, 9};
        Field settled = QuicConnection.class.getDeclaredField("serverInitialSourceConnectionId");
        settled.setAccessible(true);
        settled.set(client, fromInitial);
        TransportParameters params = new TransportParameters();
        params.setInitialSourceConnectionId(new byte[] {8, 8, 8, 8});
        params.setInitialMaxData(1000000);
        client.transportParametersReceived(params);
        assertTrue("RFC 9000 section 7.3: a mismatch is a TRANSPORT_PARAMETER_ERROR", client.isClosed());
    }

    // An Initial from the "server" carrying only an ACK of the client's
    // packet 0, protected with the Initial keys both sides derive from the
    // client's first Destination Connection ID.
    private static byte[] forgeAckOnlyServerInitial(QuicConnection client, byte[] originalDcid, byte[] scid)
            throws Exception {
        Method derive = QuicConnection.class.getDeclaredMethod("deriveInitialKeys", QuicVersion.class, byte[].class,
                CryptoServices.class);
        derive.setAccessible(true);
        PacketProtectionKeys keys = ((PacketProtectionKeys[]) derive.invoke(null, client.getVersion(), originalDcid,
                new CryptoServices()))[1];
        ByteBuffer frames = ByteBuffer.allocate(1200);
        QuicFrameWriter.writeAck(frames, new long[][] { { 0, 0 } }, 0);
        QuicFrameWriter.writePadding(frames, 1100 - frames.position());
        frames.flip();
        byte[] plain = new byte[frames.remaining()];
        frames.get(plain);
        long packetNumber = 0;
        int pnLength = 4;
        byte[] header = LongHeaderCodec.build(LongHeaderCodec.TYPE_INITIAL, client.getVersion().getWireValue(),
                client.getOurConnectionId(), scid, new byte[0], packetNumber, pnLength,
                plain.length + QuicAeadAlgorithm.TAG_LENGTH);
        byte[] cipher = PacketProtection.seal(keys, packetNumber, header, plain);
        byte[] packet = new byte[header.length + cipher.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(cipher, 0, packet, header.length, cipher.length);
        int pnOffset = header.length - pnLength;
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, sample.length);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        PacketProtection.xorFirstByte(packet, mask, true);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);
        return packet;
    }
}
