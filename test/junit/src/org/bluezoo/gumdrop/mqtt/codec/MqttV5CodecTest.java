/*
 * MqttV5CodecTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Round-trip and malformed-input tests for the MQTT 5.0 paths of the
 * encoder and the push parser, plus the small enum helpers.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttV5CodecTest {

    /** Records every event as a string and keeps the last properties seen. */
    private static class Log implements MqttEventHandler {
        final List<String> events = new ArrayList<String>();
        MqttProperties lastProps;
        ConnectPacket connect;
        int[] codes;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        public void connect(ConnectPacket packet) {
            connect = packet;
            events.add("connect");
        }

        public void connAck(boolean sessionPresent, int returnCode, MqttProperties properties) {
            lastProps = properties;
            events.add("connack:" + sessionPresent + ":" + returnCode);
        }

        public void startPublish(boolean dup, int qos, boolean retain, String topicName,
                int packetId, MqttProperties properties, int payloadLength) {
            lastProps = properties;
            events.add("publish:" + dup + ":" + qos + ":" + retain + ":" + topicName + ":"
                    + packetId + ":" + payloadLength);
        }

        public void publishData(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            payload.write(b, 0, b.length);
        }

        public void endPublish() {
            events.add("endpublish");
        }

        public void pubAck(int packetId, int reasonCode, MqttProperties properties) {
            lastProps = properties;
            events.add("puback:" + packetId + ":" + reasonCode);
        }

        public void pubRec(int packetId, int reasonCode, MqttProperties properties) {
            lastProps = properties;
            events.add("pubrec:" + packetId + ":" + reasonCode);
        }

        public void pubRel(int packetId, int reasonCode, MqttProperties properties) {
            lastProps = properties;
            events.add("pubrel:" + packetId + ":" + reasonCode);
        }

        public void pubComp(int packetId, int reasonCode, MqttProperties properties) {
            lastProps = properties;
            events.add("pubcomp:" + packetId + ":" + reasonCode);
        }

        public void startSubscribe(int packetId, MqttProperties properties) {
            lastProps = properties;
            events.add("sub:" + packetId);
        }

        public void subscribeFilter(String topicFilter, int qos) {
            events.add("filter:" + topicFilter + ":" + qos);
        }

        public void endSubscribe() {
            events.add("endsub");
        }

        public void subAck(int packetId, MqttProperties properties, int[] returnCodes) {
            lastProps = properties;
            codes = returnCodes;
            events.add("suback:" + packetId);
        }

        public void startUnsubscribe(int packetId, MqttProperties properties) {
            lastProps = properties;
            events.add("unsub:" + packetId);
        }

        public void unsubscribeFilter(String topicFilter) {
            events.add("unfilter:" + topicFilter);
        }

        public void endUnsubscribe() {
            events.add("endunsub");
        }

        public void unsubAck(int packetId, MqttProperties properties, int[] reasonCodes) {
            lastProps = properties;
            codes = reasonCodes;
            events.add("unsuback:" + packetId);
        }

        public void pingReq() {
            events.add("pingreq");
        }

        public void pingResp() {
            events.add("pingresp");
        }

        public void disconnect(int reasonCode, MqttProperties properties) {
            lastProps = properties;
            events.add("disconnect:" + reasonCode);
        }

        public void auth(int reasonCode, MqttProperties properties) {
            lastProps = properties;
            events.add("auth:" + reasonCode);
        }

        public void parseError(String message) {
            events.add("error");
        }
    }

    private static MqttProperties sampleProps() {
        MqttProperties p = new MqttProperties();
        p.setIntegerProperty(MqttProperties.SESSION_EXPIRY_INTERVAL, 3600);
        p.setStringProperty(MqttProperties.REASON_STRING, "because");
        p.addUserProperty("k", "v");
        return p;
    }

    private static MqttFrameParser v5Parser(Log log) {
        MqttFrameParser parser = new MqttFrameParser(log);
        parser.setVersion(MqttVersion.V5_0);
        return parser;
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    @Test
    public void connectV5WithWillCredentialsRoundTrips() {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(MqttVersion.V5_0);
        p.setClientId("cid");
        p.setCleanSession(true);
        p.setKeepAlive(30);
        p.setProperties(sampleProps());
        p.setWillFlag(true);
        p.setWillQoS(QoS.EXACTLY_ONCE);
        p.setWillRetain(true);
        p.setWillTopic("will/t");
        p.setWillPayload(new byte[] {1, 2, 3});
        MqttProperties wp = new MqttProperties();
        wp.setIntegerProperty(MqttProperties.WILL_DELAY_INTERVAL, 5);
        p.setWillProperties(wp);
        p.setUsername("user");
        p.setPassword(new byte[] {9, 8});
        Log log = new Log();
        MqttFrameParser parser = new MqttFrameParser(log);
        parser.receive(MqttPacketEncoder.encodeConnect(p));
        assertEquals(1, log.events.size());
        ConnectPacket got = log.connect;
        assertEquals(MqttVersion.V5_0, got.getVersion());
        assertEquals("cid", got.getClientId());
        assertTrue(got.isCleanSession());
        assertEquals(30, got.getKeepAlive());
        assertEquals(QoS.EXACTLY_ONCE, got.getWillQoS());
        assertTrue(got.isWillRetain());
        assertEquals("will/t", got.getWillTopic());
        assertArrayEquals(new byte[] {1, 2, 3}, got.getWillPayload());
        assertEquals(Integer.valueOf(5),
                got.getWillProperties().getIntegerProperty(MqttProperties.WILL_DELAY_INTERVAL));
        assertEquals("user", got.getUsername());
        assertArrayEquals(new byte[] {9, 8}, got.getPassword());
        assertEquals(Integer.valueOf(3600),
                got.getProperties().getIntegerProperty(MqttProperties.SESSION_EXPIRY_INTERVAL));
        assertTrue(got.toString().contains("cid"));
    }

    @Test
    public void connectWithNullWillPayloadAndNoWillQos() {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(MqttVersion.V3_1_1);
        p.setClientId("c");
        p.setWillFlag(true);
        p.setWillTopic("t");
        Log log = new Log();
        new MqttFrameParser(log).receive(MqttPacketEncoder.encodeConnect(p));
        assertEquals("connect", log.events.get(0));
        assertEquals(0, log.connect.getWillPayload().length);
        assertEquals(QoS.AT_MOST_ONCE, log.connect.getWillQoS());
    }

    @Test
    public void connectV5WithWillAndNullWillProperties() {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(MqttVersion.V5_0);
        p.setClientId("c");
        p.setWillFlag(true);
        p.setWillTopic("t");
        p.setWillPayload(new byte[0]);
        p.setWillProperties(null);
        ByteBuffer wire = MqttPacketEncoder.encodeConnect(p);
        assertTrue(wire.remaining() > 0);
    }

    @Test
    public void connAckV5CarriesProperties() {
        Log log = new Log();
        v5Parser(log).receive(MqttPacketEncoder.encodeConnAck(true, 0, sampleProps(),
                MqttVersion.V5_0));
        assertEquals("connack:true:0", log.events.get(0));
        assertEquals("because", log.lastProps.getStringProperty(MqttProperties.REASON_STRING));
    }

    @Test
    public void connAckV5WithoutPropertiesBytes() {
        Log log = new Log();
        MqttFrameParser parser = v5Parser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x20, 0x02, 0x00, 0x05}));
        assertEquals("connack:false:5", log.events.get(0));
        assertTrue(log.lastProps.isEmpty());
    }

    @Test
    public void publishV5RoundTripAllShapes() {
        Log log = new Log();
        MqttFrameParser parser = v5Parser(log);
        byte[] payload = {1, 2, 3, 4};
        parser.receive(MqttPacketEncoder.encodePublish("a/b", 1, true, true, 77, payload,
                sampleProps(), MqttVersion.V5_0));
        assertEquals("publish:true:1:true:a/b:77:4", log.events.get(0));
        assertEquals("endpublish", log.events.get(1));
        assertArrayEquals(payload, log.payload.toByteArray());
        assertEquals("v", log.lastProps.getUserProperties().get(0)[1]);
    }

    @Test
    public void publishV5NullPayloadQos0() {
        Log log = new Log();
        v5Parser(log).receive(MqttPacketEncoder.encodePublish("t", 0, false, false, 0, null,
                MqttProperties.EMPTY, MqttVersion.V5_0));
        assertEquals("publish:false:0:false:t:0:0", log.events.get(0));
        assertEquals("endpublish", log.events.get(1));
    }

    @Test
    public void publishHeaderThenStreamedPayloadInChunks() {
        Log log = new Log();
        MqttFrameParser parser = v5Parser(log);
        ByteBuffer header = MqttPacketEncoder.encodePublishHeader("topic", 2, false, false, 9,
                6, sampleProps(), MqttVersion.V5_0);
        byte[] h = bytes(header);
        parser.receive(ByteBuffer.wrap(h, 0, 2));
        for (int i = 2; i < h.length; i++) {
            parser.receive(ByteBuffer.wrap(new byte[] {h[i]}));
        }
        assertEquals("publish:false:2:false:topic:9:6", log.events.get(0));
        parser.receive(ByteBuffer.wrap(new byte[] {1, 2}));
        parser.receive(ByteBuffer.wrap(new byte[] {3, 4, 5, 6}));
        assertEquals("endpublish", log.events.get(1));
        assertEquals(6, log.payload.size());
    }

    @Test
    public void publishHeaderEncoderV311Qos0() {
        ByteBuffer header = MqttPacketEncoder.encodePublishHeader("t", 0, false, false, 0, 3,
                null, MqttVersion.V3_1_1);
        byte[] b = bytes(header);
        assertEquals(0x30, b[0] & 0xFF);
        assertEquals(6, b[1]);
        assertEquals(5, b.length);
    }

    @Test
    public void simpleAcksV5WithAndWithoutProperties() {
        Log log = new Log();
        MqttFrameParser parser = v5Parser(log);
        parser.receive(MqttPacketEncoder.encodePubAck(1, 16, sampleProps(), MqttVersion.V5_0));
        parser.receive(MqttPacketEncoder.encodePubRec(2, 0x80, MqttProperties.EMPTY, MqttVersion.V5_0));
        parser.receive(MqttPacketEncoder.encodePubRel(3, 0x92, sampleProps(), MqttVersion.V5_0));
        parser.receive(MqttPacketEncoder.encodePubComp(4, 0, MqttProperties.EMPTY, MqttVersion.V5_0));
        assertEquals("puback:1:16", log.events.get(0));
        assertEquals("pubrec:2:128", log.events.get(1));
        assertEquals("pubrel:3:146", log.events.get(2));
        assertEquals("pubcomp:4:0", log.events.get(3));
    }

    @Test
    public void simpleAckV5ShortFormHasNoReasonCode() {
        Log log = new Log();
        v5Parser(log).receive(ByteBuffer.wrap(new byte[] {0x40, 0x02, 0x00, 0x07}));
        assertEquals("puback:7:0", log.events.get(0));
    }

    @Test
    public void pubRelCarriesFixedFlags() {
        byte[] b = bytes(MqttPacketEncoder.encodePubRel(1, 0, null, MqttVersion.V3_1_1));
        assertEquals(0x62, b[0] & 0xFF);
        assertEquals(4, b.length);
    }

    @Test
    public void subscribeAndAcksV5RoundTrip() {
        Log log = new Log();
        MqttFrameParser parser = v5Parser(log);
        parser.receive(MqttPacketEncoder.encodeSubscribe(5, new String[] {"a/#", "b/+"},
                new int[] {1, 2}, sampleProps(), MqttVersion.V5_0));
        parser.receive(MqttPacketEncoder.encodeSubAck(5, new int[] {1, 2, 0x80},
                MqttProperties.EMPTY, MqttVersion.V5_0));
        parser.receive(MqttPacketEncoder.encodeUnsubscribe(6, new String[] {"a/#"},
                sampleProps(), MqttVersion.V5_0));
        parser.receive(MqttPacketEncoder.encodeUnsubAck(6, new int[] {0, 17},
                sampleProps(), MqttVersion.V5_0));
        assertEquals("sub:5", log.events.get(0));
        assertEquals("filter:a/#:1", log.events.get(1));
        assertEquals("filter:b/+:2", log.events.get(2));
        assertEquals("endsub", log.events.get(3));
        assertEquals("suback:5", log.events.get(4));
        assertEquals("unsub:6", log.events.get(5));
        assertEquals("unfilter:a/#", log.events.get(6));
        assertEquals("endunsub", log.events.get(7));
        assertEquals("unsuback:6", log.events.get(8));
        assertArrayEquals(new int[] {0, 17}, log.codes);
    }

    @Test
    public void unsubAckV311HasNoReasonCodes() {
        Log log = new Log();
        new MqttFrameParser(log).receive(MqttPacketEncoder.encodeUnsubAck(8, new int[] {0},
                null, MqttVersion.V3_1_1));
        assertEquals("unsuback:8", log.events.get(0));
        assertEquals(0, log.codes.length);
    }

    @Test
    public void subAckV311() {
        Log log = new Log();
        new MqttFrameParser(log).receive(MqttPacketEncoder.encodeSubAck(8, new int[] {1, 0x80},
                null, MqttVersion.V3_1_1));
        assertEquals("suback:8", log.events.get(0));
        assertArrayEquals(new int[] {1, 0x80}, log.codes);
    }

    @Test
    public void disconnectAndAuthV5() {
        Log log = new Log();
        MqttFrameParser parser = v5Parser(log);
        parser.receive(MqttPacketEncoder.encodeDisconnect(4, sampleProps(), MqttVersion.V5_0));
        parser.receive(MqttPacketEncoder.encodeAuth(0x18, sampleProps()));
        parser.receive(ByteBuffer.wrap(new byte[] {(byte) 0xE0, 0x00}));
        parser.receive(ByteBuffer.wrap(new byte[] {(byte) 0xF0, 0x00}));
        assertEquals("disconnect:4", log.events.get(0));
        assertEquals("auth:24", log.events.get(1));
        assertEquals("disconnect:0", log.events.get(2));
        assertEquals("auth:0", log.events.get(3));
    }

    @Test
    public void disconnectV311IsTwoBytes() {
        byte[] b = bytes(MqttPacketEncoder.encodeDisconnect(0, null, MqttVersion.V3_1_1));
        assertArrayEquals(new byte[] {(byte) 0xE0, 0x00}, b);
    }

    @Test
    public void allPropertyTypesRoundTrip() {
        MqttProperties p = new MqttProperties();
        int[] bytesIds = {MqttProperties.PAYLOAD_FORMAT_INDICATOR,
            MqttProperties.REQUEST_PROBLEM_INFORMATION, MqttProperties.REQUEST_RESPONSE_INFORMATION,
            MqttProperties.MAXIMUM_QOS, MqttProperties.RETAIN_AVAILABLE,
            MqttProperties.WILDCARD_SUBSCRIPTION_AVAILABLE,
            MqttProperties.SUBSCRIPTION_IDENTIFIER_AVAILABLE,
            MqttProperties.SHARED_SUBSCRIPTION_AVAILABLE};
        for (int i = 0; i < bytesIds.length; i++) {
            p.setIntegerProperty(bytesIds[i], i + 1);
        }
        int[] fourIds = {MqttProperties.MESSAGE_EXPIRY_INTERVAL, MqttProperties.SESSION_EXPIRY_INTERVAL,
            MqttProperties.WILL_DELAY_INTERVAL, MqttProperties.MAXIMUM_PACKET_SIZE};
        for (int i = 0; i < fourIds.length; i++) {
            p.setIntegerProperty(fourIds[i], 100000 + i);
        }
        int[] twoIds = {MqttProperties.SERVER_KEEP_ALIVE, MqttProperties.RECEIVE_MAXIMUM,
            MqttProperties.TOPIC_ALIAS_MAXIMUM, MqttProperties.TOPIC_ALIAS};
        for (int i = 0; i < twoIds.length; i++) {
            p.setIntegerProperty(twoIds[i], 300 + i);
        }
        p.setIntegerProperty(MqttProperties.SUBSCRIPTION_IDENTIFIER, 300000);
        int[] stringIds = {MqttProperties.CONTENT_TYPE, MqttProperties.RESPONSE_TOPIC,
            MqttProperties.ASSIGNED_CLIENT_IDENTIFIER, MqttProperties.AUTHENTICATION_METHOD,
            MqttProperties.RESPONSE_INFORMATION, MqttProperties.SERVER_REFERENCE,
            MqttProperties.REASON_STRING};
        for (int i = 0; i < stringIds.length; i++) {
            p.setStringProperty(stringIds[i], "s" + i);
        }
        p.setBinaryProperty(MqttProperties.CORRELATION_DATA, new byte[] {1, 2});
        p.setBinaryProperty(MqttProperties.AUTHENTICATION_DATA, new byte[] {3});
        p.addUserProperty("a", "b");
        p.addUserProperty("c", "d");
        ByteBuffer buf = ByteBuffer.allocate(p.encodedLength() + 4);
        p.encode(buf);
        buf.flip();
        MqttProperties back = MqttProperties.decode(buf);
        for (int i = 0; i < bytesIds.length; i++) {
            assertEquals(Integer.valueOf(i + 1), back.getIntegerProperty(bytesIds[i]));
        }
        for (int i = 0; i < fourIds.length; i++) {
            assertEquals(Integer.valueOf(100000 + i), back.getIntegerProperty(fourIds[i]));
        }
        for (int i = 0; i < twoIds.length; i++) {
            assertEquals(Integer.valueOf(300 + i), back.getIntegerProperty(twoIds[i]));
        }
        assertEquals(Integer.valueOf(300000),
                back.getIntegerProperty(MqttProperties.SUBSCRIPTION_IDENTIFIER));
        for (int i = 0; i < stringIds.length; i++) {
            assertEquals("s" + i, back.getStringProperty(stringIds[i]));
        }
        assertArrayEquals(new byte[] {1, 2}, back.getBinaryProperty(MqttProperties.CORRELATION_DATA));
        assertArrayEquals(new byte[] {3}, back.getBinaryProperty(MqttProperties.AUTHENTICATION_DATA));
        assertEquals(2, back.getUserProperties().size());
    }

    @Test
    public void propertyAccessorsOnEmptyAndWrongTypes() {
        MqttProperties empty = new MqttProperties();
        assertTrue(empty.isEmpty());
        assertNull(empty.getIntegerProperty(1));
        assertNull(empty.getStringProperty(1));
        assertNull(empty.getBinaryProperty(1));
        assertNull(empty.getUserProperties());
        assertEquals(0, empty.encodedLength());
        MqttProperties p = new MqttProperties();
        p.setStringProperty(MqttProperties.CONTENT_TYPE, "x");
        assertNull(p.getIntegerProperty(MqttProperties.CONTENT_TYPE));
        assertNull(p.getBinaryProperty(MqttProperties.CONTENT_TYPE));
        p.setIntegerProperty(MqttProperties.TOPIC_ALIAS, 1);
        assertNull(p.getStringProperty(MqttProperties.TOPIC_ALIAS));
        assertNull(p.getUserProperties());
        p.setStringProperty(MqttProperties.USER_PROPERTY, "bogus");
        assertNull(p.getUserProperties());
    }

    @Test
    public void decodeSkipsUnknownPropertyAndEmptyBlock() {
        ByteBuffer unknown = ByteBuffer.wrap(new byte[] {0x03, 0x7E, 0x01, 0x02});
        MqttProperties p = MqttProperties.decode(unknown);
        assertTrue(p.isEmpty());
        assertFalse(unknown.hasRemaining());
        ByteBuffer zero = ByteBuffer.wrap(new byte[] {0x00});
        assertSame(MqttProperties.EMPTY, MqttProperties.decode(zero));
    }

    @Test
    public void unknownPropertyIdEncodesAsBareId() {
        MqttProperties p = new MqttProperties();
        p.setIntegerProperty(0x7E, 5);
        assertEquals(1, p.encodedLength());
        ByteBuffer buf = ByteBuffer.allocate(4);
        p.encode(buf);
        buf.flip();
        assertEquals(2, buf.remaining());
    }

    @Test
    public void parserRejectsBadInputWithErrors() {
        Log log = new Log();
        MqttFrameParser parser = new MqttFrameParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x00, 0x00}));
        assertEquals("error", log.events.get(0));
        log = new Log();
        parser = new MqttFrameParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x30, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        assertEquals("error", log.events.get(0));
        log = new Log();
        parser = new MqttFrameParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x10, 0x05, 0x00, 0x04, 'M', 'Q', 'T', 'T'}));
        assertEquals("error", log.events.get(0));
    }

    @Test
    public void connectWithUnsupportedProtocolLevelReportsError() {
        byte[] body = {0x00, 0x04, 'M', 'Q', 'T', 'T', 0x09, 0x02, 0x00, 0x0A, 0x00, 0x01, 'c'};
        byte[] wire = new byte[body.length + 2];
        wire[0] = 0x10;
        wire[1] = (byte) body.length;
        System.arraycopy(body, 0, wire, 2, body.length);
        Log log = new Log();
        new MqttFrameParser(log).receive(ByteBuffer.wrap(wire));
        assertEquals("error", log.events.get(0));
    }

    @Test
    public void connAckPacketTypeFromClientDirectionStillDispatches() {
        Log log = new Log();
        new MqttFrameParser(log).receive(ByteBuffer.wrap(new byte[] {0x20, 0x01, 0x00}));
        assertEquals("error", log.events.get(0));
    }

    @Test
    public void truncatedBodyReportsDecodeError() {
        Log log = new Log();
        new MqttFrameParser(log).receive(ByteBuffer.wrap(new byte[] {0x40, 0x01, 0x00}));
        assertEquals("error", log.events.get(0));
    }

    @Test
    public void publishHeaderNeverCompletedReportsError() {
        Log log = new Log();
        MqttFrameParser parser = new MqttFrameParser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x32, 0x03, 0x00, 0x05, 'a'}));
        assertEquals(0, log.events.size());
        parser.receive(ByteBuffer.wrap(new byte[] {0x00}));
        assertEquals("error", log.events.get(0));
    }

    @Test
    public void publishV5BadPropertyLengthReportsError() {
        Log log = new Log();
        MqttFrameParser parser = v5Parser(log);
        parser.receive(ByteBuffer.wrap(new byte[] {0x30, 0x06, 0x00, 0x01, 't', (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF}));
        assertEquals("error", log.events.get(0));
    }

    @Test
    public void largePublishHeaderGrowsScratchBuffer() {
        Log log = new Log();
        MqttFrameParser parser = new MqttFrameParser(log);
        StringBuilder topic = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            topic.append('x');
        }
        byte[] wire = bytes(MqttPacketEncoder.encodePublish(topic.toString(), 1, false, false, 3,
                new byte[] {7}, null, MqttVersion.V3_1_1));
        for (int i = 0; i < wire.length; i += 100) {
            int n = Math.min(100, wire.length - i);
            parser.receive(ByteBuffer.wrap(wire, i, n));
        }
        assertEquals("publish:false:1:false:" + topic + ":3:1", log.events.get(0));
        assertEquals("endpublish", log.events.get(1));
    }

    @Test
    public void parserAccessorsAndNullHandler() {
        MqttFrameParser parser = new MqttFrameParser(new Log());
        assertEquals(MqttVersion.V3_1_1, parser.getVersion());
        parser.setMaxPacketSize(100);
        assertEquals(100, parser.getMaxPacketSize());
        try {
            new MqttFrameParser(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void enumLookups() {
        assertEquals(QoS.AT_MOST_ONCE, QoS.fromValue(0));
        assertEquals(QoS.AT_LEAST_ONCE, QoS.fromValue(1));
        assertEquals(QoS.EXACTLY_ONCE, QoS.fromValue(2));
        assertNull(QoS.fromValue(3));
        assertNull(QoS.fromValue(-1));
        assertEquals(1, QoS.AT_LEAST_ONCE.getValue());
        assertEquals(MqttVersion.V3_1_1, MqttVersion.fromProtocolLevel(4));
        assertEquals(MqttVersion.V5_0, MqttVersion.fromProtocolLevel(5));
        assertNull(MqttVersion.fromProtocolLevel(3));
        assertEquals(4, MqttVersion.V3_1_1.getProtocolLevel());
        assertEquals("MQTT", MqttVersion.V5_0.getProtocolName());
        assertNull(MqttPacketType.fromValue(0));
        assertNull(MqttPacketType.fromValue(16));
        assertEquals(MqttPacketType.CONNECT, MqttPacketType.fromValue(1));
        assertEquals(MqttPacketType.AUTH, MqttPacketType.fromValue(15));
    }

    @Test
    public void connectPacketDefaultsAndNullHandling() {
        ConnectPacket p = new ConnectPacket();
        assertNotNull(p.getProperties());
        p.setProperties(null);
        assertTrue(p.getProperties().isEmpty());
        p.setWillProperties(null);
        assertNull(p.getUsername());
        assertNull(p.getPassword());
        assertNull(p.getWillPayload());
        assertFalse(p.isWillFlag());
        assertNotNull(p.toString());
    }
}
