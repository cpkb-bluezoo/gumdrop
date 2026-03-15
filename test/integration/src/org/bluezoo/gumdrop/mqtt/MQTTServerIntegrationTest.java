/*
 * MQTTServerIntegrationTest.java
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

package org.bluezoo.gumdrop.mqtt;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.mqtt.codec.*;

/**
 * Integration tests for the MQTT server.
 *
 * <p>Starts a real Gumdrop instance with an MQTT listener on port 11883
 * and tests the protocol using raw TCP connections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MQTTServerIntegrationTest extends AbstractServerIntegrationTest {

    private static final int TEST_PORT = 11883;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/mqtt-server-test.xml");
    }

    @Test
    public void testConnectAndDisconnect() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send CONNECT
            ConnectPacket connect = new ConnectPacket();
            connect.setVersion(MQTTVersion.V3_1_1);
            connect.setCleanSession(true);
            connect.setKeepAlive(30);
            connect.setClientId("integrationTest");
            ByteBuffer connectBuf = MQTTPacketEncoder.encodeConnect(connect);
            out.write(toBytes(connectBuf));
            out.flush();

            // Read CONNACK
            byte[] connAckRaw = readPacket(in);
            assertNotNull("Should receive CONNACK", connAckRaw);
            int packetType = (connAckRaw[0] >> 4) & 0x0F;
            assertEquals("Should be CONNACK",
                    MQTTPacketType.CONNACK.getValue(), packetType);
            assertEquals("Return code should be 0 (accepted)",
                    0, connAckRaw[3]);

            // Send DISCONNECT
            ByteBuffer discBuf = MQTTPacketEncoder.encodeDisconnect(
                    MQTTEventHandler.DISCONNECT_NORMAL,
                    MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
            out.write(toBytes(discBuf));
            out.flush();
        }
    }

    @Test
    public void testPingPong() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            connectClient(out, in, "pingTest");

            // Send PINGREQ
            ByteBuffer pingBuf = MQTTPacketEncoder.encodePingReq();
            out.write(toBytes(pingBuf));
            out.flush();

            // Read PINGRESP
            byte[] pingResp = readPacket(in);
            assertNotNull("Should receive PINGRESP", pingResp);
            int packetType = (pingResp[0] >> 4) & 0x0F;
            assertEquals("Should be PINGRESP",
                    MQTTPacketType.PINGRESP.getValue(), packetType);
        }
    }

    @Test
    public void testSubscribeAndPublish() throws Exception {
        try (Socket subscriber = new Socket("127.0.0.1", TEST_PORT);
             Socket publisher = new Socket("127.0.0.1", TEST_PORT)) {

            OutputStream subOut = subscriber.getOutputStream();
            InputStream subIn = subscriber.getInputStream();
            OutputStream pubOut = publisher.getOutputStream();
            InputStream pubIn = publisher.getInputStream();

            connectClient(subOut, subIn, "subscriber");

            // Subscribe to "test/topic"
            ByteBuffer subBuf = MQTTPacketEncoder.encodeSubscribe(
                    1,
                    new String[]{"test/topic"},
                    new int[]{QoS.AT_LEAST_ONCE.getValue()},
                    MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
            subOut.write(toBytes(subBuf));
            subOut.flush();

            // Read SUBACK
            byte[] subAckRaw = readPacket(subIn);
            assertNotNull("Should receive SUBACK", subAckRaw);
            int subAckType = (subAckRaw[0] >> 4) & 0x0F;
            assertEquals("Should be SUBACK",
                    MQTTPacketType.SUBACK.getValue(), subAckType);

            connectClient(pubOut, pubIn, "publisher");

            // Publish to "test/topic"
            ByteBuffer pubBuf = MQTTPacketEncoder.encodePublish(
                    "test/topic", 0, false, false, 0,
                    "hello MQTT".getBytes(StandardCharsets.UTF_8),
                    MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
            pubOut.write(toBytes(pubBuf));
            pubOut.flush();

            // Subscriber should receive the PUBLISH
            byte[] publishRaw = readPacket(subIn);
            assertNotNull("Subscriber should receive PUBLISH", publishRaw);
            int pubType = (publishRaw[0] >> 4) & 0x0F;
            assertEquals("Should be PUBLISH",
                    MQTTPacketType.PUBLISH.getValue(), pubType);
        }
    }

    // ── Helpers ──

    private void connectClient(OutputStream out, InputStream in,
                               String clientId) throws Exception {
        ConnectPacket connect = new ConnectPacket();
        connect.setVersion(MQTTVersion.V3_1_1);
        connect.setCleanSession(true);
        connect.setKeepAlive(30);
        connect.setClientId(clientId);
        out.write(toBytes(MQTTPacketEncoder.encodeConnect(connect)));
        out.flush();

        byte[] connAck = readPacket(in);
        assertNotNull("Should receive CONNACK for " + clientId, connAck);
        assertEquals("CONNACK return code should be 0", 0, connAck[3]);
    }

    /**
     * Reads a single MQTT packet from the stream.
     * Returns the complete packet bytes, or null on timeout/EOF.
     */
    private byte[] readPacket(InputStream in) throws Exception {
        in.mark(5);
        int firstByte = in.read();
        if (firstByte < 0) return null;

        int remainingLength = 0;
        int multiplier = 1;
        for (int i = 0; i < 4; i++) {
            int b = in.read();
            if (b < 0) return null;
            remainingLength += (b & 0x7F) * multiplier;
            if ((b & 0x80) == 0) break;
            multiplier *= 128;
        }

        byte[] payload = new byte[remainingLength];
        int offset = 0;
        while (offset < remainingLength) {
            int read = in.read(payload, offset, remainingLength - offset);
            if (read < 0) return null;
            offset += read;
        }

        ByteBuffer headerBuf = ByteBuffer.allocate(5);
        headerBuf.put((byte) firstByte);
        VariableLengthEncoding.encode(headerBuf, remainingLength);
        headerBuf.flip();

        byte[] full = new byte[headerBuf.remaining() + remainingLength];
        headerBuf.get(full, 0, headerBuf.remaining());
        System.arraycopy(payload, 0, full,
                full.length - remainingLength, remainingLength);
        return full;
    }

    private byte[] toBytes(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }
}
