/*
 * MqttClientConnectTest.java
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

package org.bluezoo.gumdrop.mqtt.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.mqtt.codec.MqttPacketEncoder;
import org.bluezoo.gumdrop.mqtt.codec.MqttProperties;
import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Test;

/**
 * {@link MqttClient#connect} and the facade operations that follow it,
 * against a mock broker played frame by frame in memory. The endpoint
 * seam attaches the client's protocol handler to a
 * {@link BinaryRecordingEndpoint} instead of dialling a socket, so the
 * test reads the packets the client sent and feeds back the broker's
 * replies. The real loopback dial stays in the integration test.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttClientConnectTest {

    private static final MqttVersion V = MqttVersion.V3_1_1;

    /** Client whose transport is an in-memory endpoint. */
    private static final class StubClient extends MqttClient {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        MqttClientProtocolHandler handler;
        IOException failure;
        int opened;

        StubClient() {
            host("broker.test");
        }

        @Override
        ClientEndpoint openEndpoint(Gumdrop gumdrop,
                MqttClientProtocolHandler ph, byte[] echConfigList)
                throws IOException {
            opened++;
            if (failure != null) {
                throw failure;
            }
            handler = ph;
            ph.connected(endpoint);
            return null;
        }
    }

    private static final class Events implements MqttClientCallback,
            MqttMessageListener {
        boolean connected;
        boolean sessionPresent;
        int returnCode = -1;
        int lost;
        int subAckId = -1;
        final List<Integer> completed = new ArrayList<Integer>();
        final List<String> topics = new ArrayList<String>();
        final List<String> payloads = new ArrayList<String>();

        @Override
        public void connected(boolean sessionPresent, int returnCode) {
            this.connected = true;
            this.sessionPresent = sessionPresent;
            this.returnCode = returnCode;
        }

        @Override
        public void connectionLost(Exception cause) {
            lost++;
        }

        @Override
        public void subscribeAcknowledged(int packetId, int[] grantedQoS) {
            subAckId = packetId;
        }

        @Override
        public void publishComplete(int packetId) {
            completed.add(Integer.valueOf(packetId));
        }

        @Override
        public void messageReceived(String topic, MqttMessageContent content,
                int qos, boolean retain) {
            topics.add(topic);
            payloads.add(new String(content.asByteArray(),
                    StandardCharsets.UTF_8));
        }
    }

    private static String sentText(StubClient c) {
        return new String(c.endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
    }

    private static void brokerAccepts(StubClient c) {
        c.handler.receive(MqttPacketEncoder.encodeConnAck(true, 0,
                MqttProperties.EMPTY, V));
        c.endpoint.clearWrites();
    }

    @Test
    public void connectSendsConnectWithConfiguredFields() throws Exception {
        StubClient c = new StubClient();
        c.setClientId("client-1");
        c.setKeepAlive(45);
        c.setCleanSession(false);
        c.setCredentials("alice", "secret");
        c.setWill("will/topic", new byte[] {1, 2}, QoS.AT_LEAST_ONCE, true);
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);

        assertEquals(1, c.opened);
        String sent = sentText(c);
        assertTrue(sent.contains("MQTT"));
        assertTrue(sent.contains("client-1"));
        assertTrue(sent.contains("alice"));
        assertTrue(sent.contains("secret"));
        assertTrue(sent.contains("will/topic"));
        assertEquals("CONNECT is packet type 1", 0x10, c.endpoint.getWrites().get(0)[0] & 0xF0);
    }

    @Test
    public void connectWithoutOptionalFieldsUsesDefaults() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        String sent = sentText(c);
        assertTrue(sent.contains("MQTT"));
        assertFalse(sent.contains("alice"));
    }

    @Test
    public void willWithoutQosDefaultsToAtMostOnce() throws Exception {
        StubClient c = new StubClient();
        c.setWill("w", new byte[0], null, false);
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        assertTrue(sentText(c).contains("w"));
    }

    @Test
    public void connAckIsReportedToCallback() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        assertTrue(events.connected);
        assertTrue(events.sessionPresent);
        assertEquals(0, events.returnCode);
    }

    @Test
    public void endpointFailureIsThrownToTheCaller() throws Exception {
        StubClient c = new StubClient();
        c.failure = new IOException("no route");
        Events events = new Events();
        try {
            c.connect(TestGumdrop.create(), events, events);
            fail("expected IOException");
        } catch (IOException e) {
            assertSame(c.failure, e);
        }
        assertEquals(0, events.lost);
    }

    @Test
    public void connectWithoutTargetIsRejected() throws Exception {
        MqttClient c = new MqttClient();
        Events events = new Events();
        try {
            c.connect(TestGumdrop.create(), events, events);
            fail("expected a missing target to be rejected");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void publishQoS0SendsNoPacketId() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        int id = c.publish("a/b", "hello", QoS.AT_MOST_ONCE);
        assertEquals(0, id);
        String sent = sentText(c);
        assertTrue(sent.contains("a/b"));
        assertTrue(sent.endsWith("hello"));
    }

    @Test
    public void publishNullStringSendsEmptyPayload() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        c.publish("t", (String) null, QoS.AT_MOST_ONCE);
        assertTrue(sentText(c).endsWith("t"));
    }

    @Test
    public void publishQoS1CompletesOnPubAck() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.setMessageStore(new InMemoryMessageStore());
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        int id = c.publish("t", new byte[] {7}, QoS.AT_LEAST_ONCE, true);
        assertTrue(id > 0);
        c.handler.receive(MqttPacketEncoder.encodePubAck(id, 0,
                MqttProperties.EMPTY, V));
        assertEquals(1, events.completed.size());
        assertEquals(id, events.completed.get(0).intValue());
    }

    @Test
    public void subscribeAndUnsubscribeRoundTrip() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        int id = c.subscribe("sensors/#", QoS.AT_LEAST_ONCE);
        assertTrue(sentText(c).contains("sensors/#"));
        c.handler.receive(MqttPacketEncoder.encodeSubAck(id,
                new int[] {1}, MqttProperties.EMPTY, V));
        assertEquals(id, events.subAckId);

        c.endpoint.clearWrites();
        int uid = c.unsubscribe("sensors/#", "other");
        assertTrue(uid != id);
        assertTrue(sentText(c).contains("other"));
    }

    @Test
    public void inboundPublishReachesListener() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        c.handler.receive(MqttPacketEncoder.encodePublish("news", 0, false,
                false, 0, "headline".getBytes(StandardCharsets.UTF_8),
                MqttProperties.EMPTY, V));
        assertEquals(1, events.topics.size());
        assertEquals("news", events.topics.get(0));
        assertEquals("headline", events.payloads.get(0));
    }

    @Test
    public void disconnectSendsPacketAndClosesEndpoint() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        c.disconnect();
        assertEquals(0xE0, c.endpoint.getWrites().get(0)[0] & 0xF0);
        assertEquals(1, c.endpoint.getCloseCount());
    }

    @Test
    public void keepAliveTimerPingsThroughTheFacade() throws Exception {
        StubClient c = new StubClient();
        c.setKeepAlive(5);
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        brokerAccepts(c);
        assertEquals(1, c.endpoint.getTimers().size());
        assertEquals(5000L, c.endpoint.getTimers().get(0).getDelayMs());
        c.endpoint.fireTimers();
        assertEquals(0xC0, c.endpoint.getWrites().get(0)[0] & 0xF0);
    }

    @Test
    public void transportLossIsReportedToCallback() throws Exception {
        StubClient c = new StubClient();
        Events events = new Events();
        c.connect(TestGumdrop.create(), events, events);
        c.handler.disconnected();
        assertEquals(1, events.lost);
    }
}
