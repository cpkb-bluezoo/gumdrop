/*
 * MqttCodecSweepTest.java
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

import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Truncation and corruption sweeps over {@link MqttFrameParser} for MQTT
 * 3.1.1 and 5.0: no input may make the parser throw (malformed packets are
 * reported through {@code parseError}), and a truncated packet must be left
 * entirely in the caller's buffer.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttCodecSweepTest {

    /** Ignores every event. */
    private static final class NullHandler implements MqttEventHandler {
        public void connect(ConnectPacket packet) { }
        public void connAck(boolean sessionPresent, int returnCode, MqttProperties p) { }
        public void startPublish(boolean dup, int qos, boolean retain, String topicName,
                int packetId, MqttProperties properties, int payloadLength) { }
        public void publishData(ByteBuffer data) { }
        public void endPublish() { }
        public void pubAck(int packetId, int reasonCode, MqttProperties p) { }
        public void pubRec(int packetId, int reasonCode, MqttProperties p) { }
        public void pubRel(int packetId, int reasonCode, MqttProperties p) { }
        public void pubComp(int packetId, int reasonCode, MqttProperties p) { }
        public void startSubscribe(int packetId, MqttProperties p) { }
        public void subscribeFilter(String topicFilter, int qos) { }
        public void endSubscribe() { }
        public void subAck(int packetId, MqttProperties p, int[] returnCodes) { }
        public void startUnsubscribe(int packetId, MqttProperties p) { }
        public void unsubscribeFilter(String topicFilter) { }
        public void endUnsubscribe() { }
        public void unsubAck(int packetId, MqttProperties p, int[] reasonCodes) { }
        public void pingReq() { }
        public void pingResp() { }
        public void disconnect(int reasonCode, MqttProperties p) { }
        public void auth(int reasonCode, MqttProperties p) { }
        public void parseError(String message) { }
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static MqttProperties props() {
        MqttProperties p = new MqttProperties();
        p.setIntegerProperty(MqttProperties.SESSION_EXPIRY_INTERVAL, 60);
        p.setIntegerProperty(MqttProperties.RECEIVE_MAXIMUM, 10);
        p.setStringProperty(MqttProperties.REASON_STRING, "why");
        p.setBinaryProperty(MqttProperties.CORRELATION_DATA, new byte[] {1, 2, 3});
        p.addUserProperty("k", "v");
        return p;
    }

    private static ConnectPacket connect(MqttVersion v) {
        ConnectPacket c = new ConnectPacket();
        c.setVersion(v);
        c.setCleanSession(true);
        c.setKeepAlive(30);
        c.setClientId("client-1");
        c.setWillFlag(true);
        c.setWillQoS(QoS.AT_LEAST_ONCE);
        c.setWillTopic("will/topic");
        c.setWillPayload(new byte[] {9, 8, 7});
        c.setUsername("user");
        c.setPassword(new byte[] {5, 6});
        if (v == MqttVersion.V5_0) {
            c.setProperties(props());
            c.setWillProperties(props());
        }
        return c;
    }

    private static void sweep(byte[] valid, final MqttVersion version) {
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) {
                MqttFrameParser parser = new MqttFrameParser(new NullHandler());
                parser.setVersion(version);
                parser.receive(ByteBuffer.wrap(input));
            }
        };
        List<String> failures = TruncationSweep.allFailures(valid, target);
        assertTrue(failures.toString(), failures.isEmpty());
    }

    private void sweepAll(MqttVersion v) {
        MqttProperties p = v == MqttVersion.V5_0 ? props() : MqttProperties.EMPTY;
        sweep(bytes(MqttPacketEncoder.encodeConnect(connect(v))), v);
        sweep(bytes(MqttPacketEncoder.encodeConnAck(true, 0, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodePublish("a/b", 1, false, true, 7,
                new byte[] {1, 2, 3, 4}, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodePubAck(7, 16, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodePubRel(7, 0, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodeSubscribe(3, new String[] {"a/#", "b/+"},
                new int[] {1, 2}, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodeSubAck(3, new int[] {0, 1}, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodeUnsubscribe(4, new String[] {"a/#"}, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodeUnsubAck(4, new int[] {0, 17}, p, v)), v);
        sweep(bytes(MqttPacketEncoder.encodeDisconnect(4, p, v)), v);
    }

    @Test
    public void v311PacketsNeverThrow() {
        sweepAll(MqttVersion.V3_1_1);
    }

    @Test
    public void v5PacketsNeverThrow() {
        sweepAll(MqttVersion.V5_0);
        sweep(bytes(MqttPacketEncoder.encodeAuth(24, props())), MqttVersion.V5_0);
    }

    @Test
    public void truncatedConnectIsLeftInTheCallersBuffer() {
        byte[] valid = bytes(MqttPacketEncoder.encodeConnect(connect(MqttVersion.V5_0)));
        for (int n = 0; n < valid.length; n++) {
            MqttFrameParser parser = new MqttFrameParser(new NullHandler());
            ByteBuffer buf = ByteBuffer.wrap(valid, 0, n);
            parser.receive(buf);
            assertEquals("prefix " + n, 0, buf.position());
        }
    }

    @Test
    public void maximalRemainingLengthIsRejectedWithoutAllocation() {
        byte[] huge = {0x30, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0, 1, 'a'};
        sweep(huge, MqttVersion.V5_0);
        byte[] endless = {0x30, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F};
        sweep(endless, MqttVersion.V5_0);
    }
}
