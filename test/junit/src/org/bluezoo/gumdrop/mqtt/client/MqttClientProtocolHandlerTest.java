/*
 * MqttClientProtocolHandlerTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;
import org.bluezoo.gumdrop.mqtt.codec.MqttPacketEncoder;
import org.bluezoo.gumdrop.mqtt.codec.MqttProperties;
import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link MqttClientProtocolHandler} with server-originated packets
 * and checks the bytes it sends back and the callbacks it raises.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttClientProtocolHandlerTest {

    private static final MqttVersion V = MqttVersion.V3_1_1;

    private static final class Events implements MqttClientCallback,
            MqttMessageListener {
        boolean connected;
        boolean sessionPresent;
        int returnCode = -1;
        int lost;
        Exception lostCause;
        final List<Integer> completed = new ArrayList<Integer>();
        int subAckId = -1;
        int[] granted;
        final List<String> topics = new ArrayList<String>();
        final List<String> payloads = new ArrayList<String>();
        final List<Integer> qos = new ArrayList<Integer>();
        boolean lastRetain;

        @Override
        public void connected(boolean sessionPresent, int returnCode) {
            this.connected = true;
            this.sessionPresent = sessionPresent;
            this.returnCode = returnCode;
        }

        @Override
        public void connectionLost(Exception cause) {
            lost++;
            lostCause = cause;
        }

        @Override
        public void subscribeAcknowledged(int packetId, int[] grantedQoS) {
            subAckId = packetId;
            granted = grantedQoS;
        }

        @Override
        public void publishComplete(int packetId) {
            completed.add(packetId);
        }

        @Override
        public void messageReceived(String topic, MqttMessageContent content,
                int qos, boolean retain) {
            topics.add(topic);
            payloads.add(new String(content.asByteArray(),
                    StandardCharsets.UTF_8));
            this.qos.add(qos);
            lastRetain = retain;
        }
    }

    private Events events;
    private BinaryRecordingEndpoint endpoint;
    private MqttClientProtocolHandler handler;

    @Before
    public void setUp() {
        events = new Events();
        endpoint = new BinaryRecordingEndpoint();
        handler = newHandler(events, events, 30);
        handler.connected(endpoint);
    }

    private static MqttClientProtocolHandler newHandler(Events e,
            Events listener, int keepAlive) {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(V);
        p.setClientId("client");
        p.setCleanSession(true);
        p.setKeepAlive(keepAlive);
        return new MqttClientProtocolHandler(p, e, listener,
                new InMemoryMessageStore());
    }

    private void accept() {
        handler.receive(MqttPacketEncoder.encodeConnAck(false, 0,
                MqttProperties.EMPTY, V));
        endpoint.clearWrites();
    }

    private static ByteBuffer publishFromServer(String topic, int qos,
            boolean retain, int id, String payload) {
        return MqttPacketEncoder.encodePublish(topic, qos, false, retain, id,
                payload.getBytes(StandardCharsets.UTF_8),
                MqttProperties.EMPTY, V);
    }

    private int type(int index) {
        return endpoint.getWrites().get(index)[0] & 0xf0;
    }

    @Test
    public void connectedSendsConnectPacket() {
        assertEquals(1, endpoint.getWrites().size());
        assertEquals(0x10, type(0));
        assertNotNull(handler.getEndpoint());
        assertNotNull(handler.getQoSManager());
    }

    @Test
    public void connAckAcceptedStartsKeepAlive() {
        handler.receive(MqttPacketEncoder.encodeConnAck(true, 0,
                MqttProperties.EMPTY, V));
        assertTrue(events.connected);
        assertTrue(events.sessionPresent);
        assertEquals(0, events.returnCode);
        assertEquals(1, endpoint.getTimers().size());
        assertEquals(30000, endpoint.getTimers().get(0).getDelayMs());
    }

    @Test
    public void connAckRefusedReportsReturnCodeWithoutKeepAlive() {
        handler.receive(MqttPacketEncoder.encodeConnAck(false, 5,
                MqttProperties.EMPTY, V));
        assertEquals(5, events.returnCode);
        assertTrue(endpoint.getTimers().isEmpty());
    }

    @Test
    public void duplicateConnAckIgnored() {
        accept();
        events.connected = false;
        handler.receive(MqttPacketEncoder.encodeConnAck(false, 0,
                MqttProperties.EMPTY, V));
        assertFalse(events.connected);
    }

    @Test
    public void keepAliveTimerSendsPingReqAndReschedules() {
        accept();
        endpoint.fireTimers();
        assertEquals(1, endpoint.getWrites().size());
        assertEquals(0xC0, type(0));
        assertEquals(2, endpoint.getTimers().size());
    }

    @Test
    public void zeroKeepAliveSchedulesNothing() {
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        MqttClientProtocolHandler h = newHandler(events, events, 0);
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnAck(false, 0,
                MqttProperties.EMPTY, V));
        assertTrue(ep.getTimers().isEmpty());
    }

    @Test
    public void publishQoS0SendsNoPacketId() {
        accept();
        int id = handler.publish("a/b", "hi".getBytes(StandardCharsets.UTF_8),
                QoS.AT_MOST_ONCE, true);
        assertEquals(0, id);
        assertEquals(0x31, endpoint.getWrites().get(0)[0] & 0xff);
        assertEquals(0, handler.getQoSManager().outboundCount());
    }

    @Test
    public void publishQoS1TrackedUntilPubAck() {
        accept();
        int id = handler.publish("t", new byte[] {1}, QoS.AT_LEAST_ONCE,
                false);
        assertTrue(id > 0);
        assertEquals(1, handler.getQoSManager().outboundCount());
        handler.receive(MqttPacketEncoder.encodePubAck(id, 0,
                MqttProperties.EMPTY, V));
        assertEquals(0, handler.getQoSManager().outboundCount());
        assertEquals(1, events.completed.size());
        assertEquals(Integer.valueOf(id), events.completed.get(0));
    }

    @Test
    public void publishQoS2CompletesThroughPubRecPubComp() {
        accept();
        int id = handler.publish("t", new byte[] {1}, QoS.EXACTLY_ONCE,
                false);
        endpoint.clearWrites();
        handler.receive(MqttPacketEncoder.encodePubRec(id, 0,
                MqttProperties.EMPTY, V));
        assertEquals(0x60, type(0));
        handler.receive(MqttPacketEncoder.encodePubComp(id, 0,
                MqttProperties.EMPTY, V));
        assertEquals(0, handler.getQoSManager().outboundCount());
        assertEquals(1, events.completed.size());
    }

    @Test
    public void subscribeSendsPacketAndSubAckReported() {
        accept();
        int id = handler.subscribe(new String[] {"a", "b"},
                new QoS[] {QoS.AT_MOST_ONCE, QoS.AT_LEAST_ONCE});
        assertEquals(0x82, endpoint.getWrites().get(0)[0] & 0xff);
        handler.receive(MqttPacketEncoder.encodeSubAck(id, new int[] {0, 1},
                MqttProperties.EMPTY, V));
        assertEquals(id, events.subAckId);
        assertArrayEquals(new int[] {0, 1}, events.granted);
    }

    @Test
    public void unsubscribeSendsPacketAndUnsubAckTolerated() {
        accept();
        int id = handler.unsubscribe(new String[] {"a"});
        assertEquals(0xA2, endpoint.getWrites().get(0)[0] & 0xff);
        handler.receive(MqttPacketEncoder.encodeUnsubAck(id, new int[0],
                MqttProperties.EMPTY, V));
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void inboundQoS0DeliveredToListener() {
        accept();
        handler.receive(publishFromServer("x/y", 0, true, 0, "payload"));
        assertEquals("x/y", events.topics.get(0));
        assertEquals("payload", events.payloads.get(0));
        assertEquals(Integer.valueOf(0), events.qos.get(0));
        assertTrue(events.lastRetain);
        assertTrue(endpoint.getWrites().isEmpty());
    }

    @Test
    public void inboundQoS1DeliveredAndAcked() {
        accept();
        handler.receive(publishFromServer("x", 1, false, 12, "m"));
        assertEquals(0x40, type(0));
        assertEquals(1, events.topics.size());
    }

    @Test
    public void inboundQoS2HeldUntilPubRel() {
        accept();
        handler.receive(publishFromServer("x", 2, false, 21, "held"));
        assertEquals(0x50, type(0));
        assertTrue(events.topics.isEmpty());
        assertTrue(handler.getQoSManager().isInboundQoS2Tracked(21));
        endpoint.clearWrites();
        handler.receive(MqttPacketEncoder.encodePubRel(21, 0,
                MqttProperties.EMPTY, V));
        assertEquals(0x70, type(0));
        assertEquals("held", events.payloads.get(0));
    }

    @Test
    public void duplicateInboundQoS2NotTrackedTwice() {
        accept();
        handler.receive(publishFromServer("x", 2, false, 21, "held"));
        handler.receive(publishFromServer("x", 2, false, 21, "held"));
        assertEquals(1, handler.getQoSManager().inboundCount());
    }

    @Test
    public void inboundPublishBeforeConnAckIgnored() {
        handler.receive(publishFromServer("x", 0, false, 0, "early"));
        assertTrue(events.topics.isEmpty());
    }

    @Test
    public void inboundPublishWithoutListenerReleasesContent() {
        MqttClientProtocolHandler h = new MqttClientProtocolHandler(
                connectPacketFor("nolistener"), null, null,
                new InMemoryMessageStore());
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnAck(false, 0,
                MqttProperties.EMPTY, V));
        h.receive(publishFromServer("x", 0, false, 0, "dropped"));
        h.disconnected();
        h.error(new RuntimeException("no callback"));
    }

    private static ConnectPacket connectPacketFor(String id) {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(V);
        p.setClientId(id);
        return p;
    }

    @Test
    public void disconnectSendsPacketAndClosesAndCancelsTimer() {
        accept();
        handler.disconnect();
        assertEquals(0xE0, type(0));
        assertFalse(endpoint.isOpen());
        assertTrue(endpoint.getTimers().get(0).isCancelled());
    }

    @Test
    public void publishAfterDisconnectSendsNothing() {
        accept();
        handler.disconnect();
        endpoint.clearWrites();
        handler.publish("t", new byte[0], QoS.AT_MOST_ONCE, false);
        assertTrue(endpoint.getWrites().isEmpty());
    }

    @Test
    public void transportDisconnectNotifiesCallback() {
        accept();
        handler.disconnected();
        assertEquals(1, events.lost);
        assertNull(events.lostCause);
        assertTrue(endpoint.getTimers().get(0).isCancelled());
    }

    @Test
    public void errorNotifiesCallbackWithCause() {
        accept();
        RuntimeException cause = new RuntimeException("io");
        handler.error(cause);
        assertEquals(1, events.lost);
        assertEquals(cause, events.lostCause);
    }

    @Test
    public void serverDisconnectClosesEndpoint() {
        accept();
        handler.disconnect(0, MqttProperties.EMPTY);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void parseErrorClosesEndpoint() {
        accept();
        handler.parseError("bad");
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void noOpServerEventsAreTolerated() {
        accept();
        handler.connect(connectPacketFor("x"));
        handler.startSubscribe(1, MqttProperties.EMPTY);
        handler.subscribeFilter("a", 0);
        handler.endSubscribe();
        handler.startUnsubscribe(1, MqttProperties.EMPTY);
        handler.unsubscribeFilter("a");
        handler.endUnsubscribe();
        handler.pingReq();
        handler.pingResp();
        handler.auth(0, MqttProperties.EMPTY);
        handler.securityEstablished(null);
        assertTrue(endpoint.isOpen());
    }
}
