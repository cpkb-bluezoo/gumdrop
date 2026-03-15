/*
 * MQTTFrameParserTest.java
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

package org.bluezoo.gumdrop.mqtt.codec;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MQTTFrameParserTest {

    private RecordingHandler handler;
    private MQTTFrameParser parser;

    @Before
    public void setUp() {
        handler = new RecordingHandler();
        parser = new MQTTFrameParser(handler);
    }

    // -- CONNECT --

    @Test
    public void testParseConnect311() {
        ConnectPacket src = new ConnectPacket();
        src.setVersion(MQTTVersion.V3_1_1);
        src.setCleanSession(true);
        src.setKeepAlive(60);
        src.setClientId("testClient");

        ByteBuffer wire = MQTTPacketEncoder.encodeConnect(src);
        parser.receive(wire);

        assertNotNull(handler.lastConnect);
        assertEquals(MQTTVersion.V3_1_1, handler.lastConnect.getVersion());
        assertTrue(handler.lastConnect.isCleanSession());
        assertEquals(60, handler.lastConnect.getKeepAlive());
        assertEquals("testClient", handler.lastConnect.getClientId());
        assertFalse(handler.lastConnect.isWillFlag());
        assertNull(handler.lastConnect.getUsername());
        assertNull(handler.lastConnect.getPassword());
    }

    @Test
    public void testParseConnectWithCredentials() {
        ConnectPacket src = new ConnectPacket();
        src.setVersion(MQTTVersion.V3_1_1);
        src.setCleanSession(false);
        src.setKeepAlive(120);
        src.setClientId("client1");
        src.setUsername("alice");
        src.setPassword("secret".getBytes(StandardCharsets.UTF_8));

        ByteBuffer wire = MQTTPacketEncoder.encodeConnect(src);
        parser.receive(wire);

        assertNotNull(handler.lastConnect);
        assertEquals("alice", handler.lastConnect.getUsername());
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8),
                handler.lastConnect.getPassword());
    }

    @Test
    public void testParseConnectWithWill() {
        ConnectPacket src = new ConnectPacket();
        src.setVersion(MQTTVersion.V3_1_1);
        src.setCleanSession(true);
        src.setKeepAlive(30);
        src.setClientId("willClient");
        src.setWillFlag(true);
        src.setWillTopic("client/status");
        src.setWillPayload("offline".getBytes(StandardCharsets.UTF_8));
        src.setWillQoS(QoS.AT_LEAST_ONCE);
        src.setWillRetain(true);

        ByteBuffer wire = MQTTPacketEncoder.encodeConnect(src);
        parser.receive(wire);

        assertNotNull(handler.lastConnect);
        assertTrue(handler.lastConnect.isWillFlag());
        assertEquals("client/status", handler.lastConnect.getWillTopic());
        assertArrayEquals("offline".getBytes(StandardCharsets.UTF_8),
                handler.lastConnect.getWillPayload());
        assertEquals(QoS.AT_LEAST_ONCE, handler.lastConnect.getWillQoS());
        assertTrue(handler.lastConnect.isWillRetain());
    }

    // -- CONNACK --

    @Test
    public void testParseConnAck() {
        ByteBuffer wire = MQTTPacketEncoder.encodeConnAck(
                true, MQTTEventHandler.CONNACK_ACCEPTED,
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertTrue(handler.connAckReceived);
        assertTrue(handler.connAckSessionPresent);
        assertEquals(0, handler.connAckReturnCode);
    }

    // -- PUBLISH (streaming) --

    @Test
    public void testParsePublishQoS0() {
        ByteBuffer wire = MQTTPacketEncoder.encodePublish(
                "sensors/temp", 0, false, false, 0,
                "22.5".getBytes(StandardCharsets.UTF_8),
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertTrue(handler.publishStarted);
        assertEquals("sensors/temp", handler.publishTopicName);
        assertEquals(0, handler.publishQoS);
        assertTrue(handler.publishEnded);
        assertArrayEquals("22.5".getBytes(StandardCharsets.UTF_8),
                handler.publishPayload.toByteArray());
    }

    @Test
    public void testParsePublishQoS1() {
        ByteBuffer wire = MQTTPacketEncoder.encodePublish(
                "data/x", 1, false, true, 42,
                "hello".getBytes(StandardCharsets.UTF_8),
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertTrue(handler.publishStarted);
        assertEquals(1, handler.publishQoS);
        assertEquals(42, handler.publishPacketId);
        assertTrue(handler.publishRetain);
        assertTrue(handler.publishEnded);
    }

    @Test
    public void testParsePublishQoS2WithDup() {
        ByteBuffer wire = MQTTPacketEncoder.encodePublish(
                "events/a", 2, true, false, 1001,
                new byte[0],
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertTrue(handler.publishStarted);
        assertEquals(2, handler.publishQoS);
        assertTrue(handler.publishDup);
        assertEquals(1001, handler.publishPacketId);
        assertTrue(handler.publishEnded);
    }

    @Test
    public void testPublishStreamingChunked() {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        ByteBuffer wire = MQTTPacketEncoder.encodePublish(
                "big/topic", 0, false, false, 0,
                payload, MQTTProperties.EMPTY, MQTTVersion.V3_1_1);

        byte[] wireBytes = new byte[wire.remaining()];
        wire.get(wireBytes);

        // Feed in small chunks to exercise streaming
        for (int offset = 0; offset < wireBytes.length; ) {
            int chunkSize = Math.min(10, wireBytes.length - offset);
            ByteBuffer chunk = ByteBuffer.wrap(wireBytes, offset, chunkSize);
            parser.receive(chunk);
            offset += chunkSize;
        }

        assertTrue(handler.publishStarted);
        assertTrue(handler.publishEnded);
        assertEquals("big/topic", handler.publishTopicName);
        assertArrayEquals(payload, handler.publishPayload.toByteArray());
    }

    // -- PUBACK / PUBREC / PUBREL / PUBCOMP --

    @Test
    public void testParsePubAck() {
        ByteBuffer wire = MQTTPacketEncoder.encodePubAck(
                7, 0, MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);
        assertEquals(7, handler.lastPubAckPacketId);
    }

    @Test
    public void testParsePubRec() {
        ByteBuffer wire = MQTTPacketEncoder.encodePubRec(
                99, 0, MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);
        assertEquals(99, handler.lastPubRecPacketId);
    }

    @Test
    public void testParsePubRel() {
        ByteBuffer wire = MQTTPacketEncoder.encodePubRel(
                100, 0, MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);
        assertEquals(100, handler.lastPubRelPacketId);
    }

    @Test
    public void testParsePubComp() {
        ByteBuffer wire = MQTTPacketEncoder.encodePubComp(
                101, 0, MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);
        assertEquals(101, handler.lastPubCompPacketId);
    }

    // -- SUBSCRIBE / SUBACK --

    @Test
    public void testParseSubscribe() {
        ByteBuffer wire = MQTTPacketEncoder.encodeSubscribe(
                10,
                new String[]{"sensors/#", "alerts/+/critical"},
                new int[]{1, 2},
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertTrue(handler.subscribeStarted);
        assertEquals(10, handler.subscribePacketId);
        assertEquals(2, handler.subscribeFilters.size());
        assertEquals("sensors/#", handler.subscribeFilters.get(0));
        assertEquals(1, handler.subscribeQoSLevels.get(0).intValue());
        assertEquals("alerts/+/critical", handler.subscribeFilters.get(1));
        assertEquals(2, handler.subscribeQoSLevels.get(1).intValue());
        assertTrue(handler.subscribeEnded);
    }

    @Test
    public void testParseSubAck() {
        ByteBuffer wire = MQTTPacketEncoder.encodeSubAck(
                10, new int[]{1, 2},
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertEquals(10, handler.subAckPacketId);
        assertNotNull(handler.subAckReturnCodes);
        assertEquals(2, handler.subAckReturnCodes.length);
        assertEquals(1, handler.subAckReturnCodes[0]);
        assertEquals(2, handler.subAckReturnCodes[1]);
    }

    // -- UNSUBSCRIBE / UNSUBACK --

    @Test
    public void testParseUnsubscribe() {
        ByteBuffer wire = MQTTPacketEncoder.encodeUnsubscribe(
                20,
                new String[]{"sensors/#", "alerts/+/critical"},
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertTrue(handler.unsubscribeStarted);
        assertEquals(20, handler.unsubscribePacketId);
        assertEquals(2, handler.unsubscribeFilters.size());
        assertTrue(handler.unsubscribeEnded);
    }

    @Test
    public void testParseUnsubAck() {
        ByteBuffer wire = MQTTPacketEncoder.encodeUnsubAck(
                20, new int[0],
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertEquals(20, handler.unsubAckPacketId);
    }

    // -- PINGREQ / PINGRESP --

    @Test
    public void testParsePingReq() {
        ByteBuffer wire = MQTTPacketEncoder.encodePingReq();
        parser.receive(wire);
        assertTrue(handler.pingReqReceived);
    }

    @Test
    public void testParsePingResp() {
        ByteBuffer wire = MQTTPacketEncoder.encodePingResp();
        parser.receive(wire);
        assertTrue(handler.pingRespReceived);
    }

    // -- DISCONNECT --

    @Test
    public void testParseDisconnect311() {
        ByteBuffer wire = MQTTPacketEncoder.encodeDisconnect(
                0, MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);
        assertTrue(handler.disconnectReceived);
    }

    // -- Partial frame handling --

    @Test
    public void testPartialFramePreserved() {
        ByteBuffer wire = MQTTPacketEncoder.encodePublish(
                "test/partial", 0, false, false, 0,
                "data".getBytes(StandardCharsets.UTF_8),
                MQTTProperties.EMPTY, MQTTVersion.V3_1_1);

        ByteBuffer partial = ByteBuffer.allocate(3);
        wire.limit(3);
        partial.put(wire);
        partial.flip();

        parser.receive(partial);
        assertFalse("Should not deliver partial packet",
                handler.publishStarted);
    }

    @Test
    public void testMultiplePacketsInOneBuffer() {
        ByteBuffer ping1 = MQTTPacketEncoder.encodePingReq();
        ByteBuffer ping2 = MQTTPacketEncoder.encodePingResp();

        ByteBuffer combined = ByteBuffer.allocate(
                ping1.remaining() + ping2.remaining());
        combined.put(ping1);
        combined.put(ping2);
        combined.flip();

        parser.receive(combined);

        assertTrue(handler.pingReqReceived);
        assertTrue(handler.pingRespReceived);
        assertFalse(combined.hasRemaining());
    }

    @Test
    public void testOversizedPacketReportsError() {
        parser.setMaxPacketSize(10);

        ByteBuffer wire = MQTTPacketEncoder.encodePublish(
                "test/big", 0, false, false, 0,
                new byte[100], MQTTProperties.EMPTY, MQTTVersion.V3_1_1);
        parser.receive(wire);

        assertNotNull(handler.lastError);
        assertTrue(handler.lastError.contains("exceeds maximum"));
    }

    // -- Recording handler --

    private static class RecordingHandler implements MQTTEventHandler {

        ConnectPacket lastConnect;
        boolean connAckReceived;
        boolean connAckSessionPresent;
        int connAckReturnCode = -1;

        boolean publishStarted;
        boolean publishDup;
        int publishQoS = -1;
        boolean publishRetain;
        String publishTopicName;
        int publishPacketId;
        int publishPayloadLength;
        ByteArrayOutputStream publishPayload = new ByteArrayOutputStream();
        boolean publishEnded;

        int lastPubAckPacketId = -1;
        int lastPubRecPacketId = -1;
        int lastPubRelPacketId = -1;
        int lastPubCompPacketId = -1;

        boolean subscribeStarted;
        int subscribePacketId;
        List<String> subscribeFilters = new ArrayList<>();
        List<Integer> subscribeQoSLevels = new ArrayList<>();
        boolean subscribeEnded;

        int subAckPacketId = -1;
        int[] subAckReturnCodes;

        boolean unsubscribeStarted;
        int unsubscribePacketId;
        List<String> unsubscribeFilters = new ArrayList<>();
        boolean unsubscribeEnded;

        int unsubAckPacketId = -1;

        boolean pingReqReceived;
        boolean pingRespReceived;
        boolean disconnectReceived;
        int disconnectReasonCode;
        boolean authReceived;
        String lastError;

        @Override
        public void connect(ConnectPacket packet) {
            lastConnect = packet;
        }

        @Override
        public void connAck(boolean sessionPresent, int returnCode,
                            MQTTProperties properties) {
            connAckReceived = true;
            connAckSessionPresent = sessionPresent;
            connAckReturnCode = returnCode;
        }

        @Override
        public void startPublish(boolean dup, int qos, boolean retain,
                                 String topicName, int packetId,
                                 MQTTProperties properties,
                                 int payloadLength) {
            publishStarted = true;
            publishDup = dup;
            publishQoS = qos;
            publishRetain = retain;
            publishTopicName = topicName;
            publishPacketId = packetId;
            publishPayloadLength = payloadLength;
        }

        @Override
        public void publishData(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            publishPayload.write(bytes, 0, bytes.length);
        }

        @Override
        public void endPublish() {
            publishEnded = true;
        }

        @Override
        public void pubAck(int packetId, int reasonCode,
                           MQTTProperties properties) {
            lastPubAckPacketId = packetId;
        }

        @Override
        public void pubRec(int packetId, int reasonCode,
                           MQTTProperties properties) {
            lastPubRecPacketId = packetId;
        }

        @Override
        public void pubRel(int packetId, int reasonCode,
                           MQTTProperties properties) {
            lastPubRelPacketId = packetId;
        }

        @Override
        public void pubComp(int packetId, int reasonCode,
                            MQTTProperties properties) {
            lastPubCompPacketId = packetId;
        }

        @Override
        public void startSubscribe(int packetId,
                                   MQTTProperties properties) {
            subscribeStarted = true;
            subscribePacketId = packetId;
        }

        @Override
        public void subscribeFilter(String topicFilter, int qos) {
            subscribeFilters.add(topicFilter);
            subscribeQoSLevels.add(qos);
        }

        @Override
        public void endSubscribe() {
            subscribeEnded = true;
        }

        @Override
        public void subAck(int packetId, MQTTProperties properties,
                           int[] returnCodes) {
            subAckPacketId = packetId;
            subAckReturnCodes = returnCodes;
        }

        @Override
        public void startUnsubscribe(int packetId,
                                     MQTTProperties properties) {
            unsubscribeStarted = true;
            unsubscribePacketId = packetId;
        }

        @Override
        public void unsubscribeFilter(String topicFilter) {
            unsubscribeFilters.add(topicFilter);
        }

        @Override
        public void endUnsubscribe() {
            unsubscribeEnded = true;
        }

        @Override
        public void unsubAck(int packetId, MQTTProperties properties,
                             int[] reasonCodes) {
            unsubAckPacketId = packetId;
        }

        @Override
        public void pingReq() {
            pingReqReceived = true;
        }

        @Override
        public void pingResp() {
            pingRespReceived = true;
        }

        @Override
        public void disconnect(int reasonCode, MQTTProperties properties) {
            disconnectReceived = true;
            disconnectReasonCode = reasonCode;
        }

        @Override
        public void auth(int reasonCode, MQTTProperties properties) {
            authReceived = true;
        }

        @Override
        public void parseError(String message) {
            lastError = message;
        }
    }
}
