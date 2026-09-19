/*
 * MqttProtocolHandlerTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.auth.BasicRealm;
import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;
import org.bluezoo.gumdrop.mqtt.codec.MqttPacketEncoder;
import org.bluezoo.gumdrop.mqtt.codec.MqttProperties;
import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.server.ConnectHandler;
import org.bluezoo.gumdrop.mqtt.server.ConnectState;
import org.bluezoo.gumdrop.mqtt.server.MqttSession;
import org.bluezoo.gumdrop.mqtt.server.MqttSessionHandler;
import org.bluezoo.gumdrop.mqtt.server.PublishState;
import org.bluezoo.gumdrop.mqtt.server.SubscribeState;
import org.bluezoo.gumdrop.mqtt.server.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.server.WillManager;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link MqttProtocolHandler} with encoded MQTT packets over a
 * recording endpoint, covering the connect state machine, QoS 0/1/2
 * publish flows, subscribe/unsubscribe, keep-alive, will messages and
 * the async authorization callbacks.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttProtocolHandlerTest {

    private MqttListener listener;
    private SubscriptionManager subs;
    private WillManager wills;
    private MqttProtocolHandler handler;
    private BinaryRecordingEndpoint endpoint;

    @Before
    public void setUp() {
        listener = new MqttListener();
        subs = new SubscriptionManager();
        wills = new WillManager();
        handler = newHandler(listener);
        endpoint = new BinaryRecordingEndpoint();
        handler.connected(endpoint);
    }

    private MqttProtocolHandler newHandler(MqttListener l) {
        return new MqttProtocolHandler(l, subs, wills,
                new InMemoryMessageStore());
    }

    private static ConnectPacket connectPacket(String clientId,
            boolean clean, int keepAlive) {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(MqttVersion.V3_1_1);
        p.setClientId(clientId);
        p.setCleanSession(clean);
        p.setKeepAlive(keepAlive);
        return p;
    }

    private void send(ByteBuffer packet) {
        handler.receive(packet);
    }

    private void connect(String clientId) {
        send(MqttPacketEncoder.encodeConnect(connectPacket(clientId, true, 0)));
        endpoint.clearWrites();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void publish(String topic, int qos, boolean retain, int id,
                         String payload) {
        send(MqttPacketEncoder.encodePublish(topic, qos, false, retain, id,
                bytes(payload), MqttProperties.EMPTY, MqttVersion.V3_1_1));
    }

    private int firstByte(int index) {
        List<byte[]> writes = endpoint.getWrites();
        return writes.get(index)[0] & 0xff;
    }

    /** Attaches a second connected client with the given id. */
    private BinaryRecordingEndpoint otherClient(String clientId) {
        MqttProtocolHandler h = newHandler(listener);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(
                connectPacket(clientId, true, 0)));
        ep.clearWrites();
        return ep;
    }

    @Test
    public void connectAcceptedSendsConnAck() {
        send(MqttPacketEncoder.encodeConnect(connectPacket("c1", true, 0)));
        List<byte[]> w = endpoint.getWrites();
        assertEquals(1, w.size());
        assertEquals(0x20, w.get(0)[0] & 0xff);
        assertEquals(0, w.get(0)[3]);
        assertEquals(MqttProtocolHandler.State.CONNECTED, handler.getState());
        assertNotNull(subs.getSession("c1"));
    }

    @Test
    public void connectTimerClosesUnconnectedClient() {
        endpoint.fireTimers();
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void connectTimerCancelledAfterConnect() {
        connect("c1");
        int closes = endpoint.getCloseCount();
        endpoint.fireTimers();
        assertEquals(closes, endpoint.getCloseCount());
    }

    @Test
    public void emptyClientIdWithoutCleanSessionRejected() {
        send(MqttPacketEncoder.encodeConnect(connectPacket("", false, 0)));
        assertEquals(2, endpoint.getWrites().get(0)[3]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void emptyClientIdWithCleanSessionGetsGeneratedId() {
        send(MqttPacketEncoder.encodeConnect(connectPacket("", true, 0)));
        assertEquals(0, endpoint.getWrites().get(0)[3]);
        assertEquals(1, subs.getSessionCount());
    }

    @Test
    public void secondConnectIsProtocolViolation() {
        connect("c1");
        send(MqttPacketEncoder.encodeConnect(connectPacket("c1", true, 0)));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void realmRejectsMissingCredentials() {
        listener.setRealm(new BasicRealm());
        send(MqttPacketEncoder.encodeConnect(connectPacket("c1", true, 0)));
        assertEquals(4, endpoint.getWrites().get(0)[3]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void realmRejectsWrongPassword() {
        listener.setRealm(new BasicRealm());
        ConnectPacket p = connectPacket("c1", true, 0);
        p.setUsername("nobody");
        p.setPassword(bytes("bad"));
        send(MqttPacketEncoder.encodeConnect(p));
        assertEquals(4, endpoint.getWrites().get(0)[3]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void reconnectWithCleanSessionReplacesExisting() {
        BinaryRecordingEndpoint first = otherClient("dup");
        send(MqttPacketEncoder.encodeConnect(connectPacket("dup", true, 0)));
        assertFalse(first.isOpen());
        assertEquals(0, endpoint.getWrites().get(0)[3]);
    }

    @Test
    public void reconnectWithPersistentSessionReportsSessionPresent() {
        send(MqttPacketEncoder.encodeConnect(connectPacket("p", false, 0)));
        handler.disconnected();
        MqttProtocolHandler h2 = newHandler(listener);
        BinaryRecordingEndpoint ep2 = new BinaryRecordingEndpoint();
        h2.connected(ep2);
        h2.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("p", false, 0)));
        assertEquals(1, ep2.getWrites().get(0)[2]);
    }

    @Test
    public void keepAliveSchedulesTimerAndExpiryCloses() {
        send(MqttPacketEncoder.encodeConnect(connectPacket("k", true, 10)));
        boolean found = false;
        for (BinaryRecordingEndpoint.StubTimer t : endpoint.getTimers()) {
            if (t.getDelayMs() == 15000 && !t.isCancelled()) {
                found = true;
            }
        }
        assertTrue(found);
        endpoint.fireTimers();
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void pingReqGetsPingResp() {
        connect("c1");
        send(MqttPacketEncoder.encodePingReq());
        assertEquals(0xD0, firstByte(0));
    }

    @Test
    public void pingBeforeConnectIgnored() {
        send(MqttPacketEncoder.encodePingReq());
        assertTrue(endpoint.getWrites().isEmpty());
    }

    @Test
    public void publishBeforeConnectIsViolation() {
        publish("a", 0, false, 0, "x");
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void subscribeBeforeConnectIsViolation() {
        send(MqttPacketEncoder.encodeSubscribe(1, new String[] {"a"},
                new int[] {0}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void unsubscribeBeforeConnectIsViolation() {
        send(MqttPacketEncoder.encodeUnsubscribe(1, new String[] {"a"},
                MqttProperties.EMPTY, MqttVersion.V3_1_1));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void subscribeGrantsAndAcks() {
        connect("c1");
        send(MqttPacketEncoder.encodeSubscribe(7, new String[] {"a/b", "c/#"},
                new int[] {1, 0}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        byte[] ack = endpoint.getWrites().get(0);
        assertEquals(0x90, ack[0] & 0xff);
        assertEquals(1, ack[4]);
        assertEquals(0, ack[5]);
        assertEquals(2, subs.getSubscriptions("c1").size());
    }

    @Test
    public void unsubscribeRemovesAndAcks() {
        connect("c1");
        send(MqttPacketEncoder.encodeSubscribe(1, new String[] {"a"},
                new int[] {0}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        endpoint.clearWrites();
        send(MqttPacketEncoder.encodeUnsubscribe(2, new String[] {"a"},
                MqttProperties.EMPTY, MqttVersion.V3_1_1));
        assertEquals(0xB0, firstByte(0));
        assertTrue(subs.getSubscriptions("c1").isEmpty());
    }

    @Test
    public void publishQoS0RoutesToSubscriber() {
        connect("pub");
        BinaryRecordingEndpoint subEp = otherClient("sub");
        subs.subscribe("sub", "t/1", QoS.AT_MOST_ONCE);
        publish("t/1", 0, false, 0, "hello");
        assertEquals(1, subEp.getWrites().size());
        byte[] w = subEp.getWrites().get(0);
        assertEquals(0x30, w[0] & 0xff);
        assertTrue(new String(w, StandardCharsets.UTF_8).endsWith("hello"));
        assertTrue(endpoint.getWrites().isEmpty());
    }

    @Test
    public void publishQoS1AckedAndDowngradedToSubscriberQoS() {
        connect("pub");
        BinaryRecordingEndpoint subEp = otherClient("sub");
        subs.subscribe("sub", "t/1", QoS.AT_LEAST_ONCE);
        publish("t/1", 1, false, 5, "q1");
        assertEquals(0x40, firstByte(0));
        assertEquals(1, subEp.getWrites().size());
        assertEquals(0x32, subEp.getWrites().get(0)[0] & 0xff);
        assertEquals(1, subs.getSession("sub").getQoSManager()
                .outboundCount());
    }

    @Test
    public void publishQoS2FlowsThroughPubRecPubRelPubComp() {
        connect("pub");
        BinaryRecordingEndpoint subEp = otherClient("sub");
        subs.subscribe("sub", "t/1", QoS.AT_MOST_ONCE);
        publish("t/1", 2, false, 9, "q2");
        assertEquals(0x50, firstByte(0));
        assertTrue(subEp.getWrites().isEmpty());
        endpoint.clearWrites();
        send(MqttPacketEncoder.encodePubRel(9, 0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        assertEquals(0x70, firstByte(0));
        assertEquals(1, subEp.getWrites().size());
    }

    @Test
    public void duplicateQoS2PublishReRecsWithoutReRouting() {
        connect("pub");
        publish("t", 2, false, 3, "a");
        publish("t", 2, false, 3, "a");
        assertEquals(2, endpoint.getWrites().size());
        assertEquals(0x50, firstByte(1));
    }

    @Test
    public void outboundAckFlowsCompleteTracking() {
        connect("sub");
        subs.subscribe("sub", "t", QoS.EXACTLY_ONCE);
        BinaryRecordingEndpoint pubEp = otherClient("pub");
        MqttSession s = subs.getSession("sub");
        // Deliver QoS 2 to the "sub" session through another publisher
        MqttProtocolHandler ph = newHandler(listener);
        ph.connected(pubEp);
        ph.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("pub2", true, 0)));
        ph.receive(MqttPacketEncoder.encodePublish("t", 2, false, false, 1,
                bytes("m"), MqttProperties.EMPTY, MqttVersion.V3_1_1));
        ph.receive(MqttPacketEncoder.encodePubRel(1, 0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        int id = s.getQoSManager().getOutboundMessages().keySet()
                .iterator().next();
        endpoint.clearWrites();
        send(MqttPacketEncoder.encodePubRec(id, 0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        assertEquals(0x62, firstByte(0));
        send(MqttPacketEncoder.encodePubComp(id, 0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        send(MqttPacketEncoder.encodePubAck(id, 0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        assertEquals(0, s.getQoSManager().outboundCount());
    }

    @Test
    public void retainedMessageDeliveredOnSubscribe() {
        connect("pub");
        publish("r/1", 0, true, 0, "kept");
        assertEquals(1, subs.getRetainedStore().size());
        BinaryRecordingEndpoint subEp = otherClient("sub");
        MqttProtocolHandler h = newHandler(listener);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("sub2", true, 0)));
        ep.clearWrites();
        h.receive(MqttPacketEncoder.encodeSubscribe(1, new String[] {"r/+"},
                new int[] {0}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        assertEquals(2, ep.getWrites().size());
        // Retained message is delivered before the SUBACK completes
        assertEquals(0x31, ep.getWrites().get(0)[0] & 0xff);
        assertEquals(0x90, ep.getWrites().get(1)[0] & 0xff);
        assertTrue(subEp.getWrites().isEmpty());
    }

    @Test
    public void willPublishedOnUncleanDisconnect() {
        BinaryRecordingEndpoint subEp = otherClient("sub");
        subs.subscribe("sub", "will/t", QoS.AT_MOST_ONCE);
        ConnectPacket p = connectPacket("w", true, 0);
        p.setWillFlag(true);
        p.setWillTopic("will/t");
        p.setWillPayload(bytes("bye"));
        p.setWillQoS(QoS.AT_MOST_ONCE);
        send(MqttPacketEncoder.encodeConnect(p));
        assertTrue(wills.has("w"));
        handler.disconnected();
        assertEquals(1, subEp.getWrites().size());
        assertFalse(wills.has("w"));
    }

    @Test
    public void cleanDisconnectClearsWill() {
        ConnectPacket p = connectPacket("w", true, 0);
        p.setWillFlag(true);
        p.setWillTopic("will/t");
        p.setWillPayload(new byte[0]);
        p.setWillQoS(QoS.AT_MOST_ONCE);
        send(MqttPacketEncoder.encodeConnect(p));
        send(MqttPacketEncoder.encodeDisconnect(0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        assertFalse(wills.has("w"));
        assertFalse(endpoint.isOpen());
        assertNull(subs.getSession("w"));
    }

    @Test
    public void persistentSessionDetachedOnUncleanDisconnect() {
        send(MqttPacketEncoder.encodeConnect(connectPacket("keep", false, 0)));
        handler.disconnected();
        assertNotNull(subs.getSession("keep"));
        assertFalse(subs.getSession("keep").isConnected());
    }

    @Test
    public void errorClosesEndpoint() {
        handler.error(new RuntimeException("boom"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void malformedInputClosesEndpoint() {
        send(ByteBuffer.wrap(new byte[] {0x00, 0x00}));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void authPacketIsIgnored() {
        connect("c1");
        handler.auth(0, MqttProperties.EMPTY);
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void serverSideClientEventsAreNoOps() {
        connect("c1");
        handler.connAck(false, 0, MqttProperties.EMPTY);
        handler.subAck(1, MqttProperties.EMPTY, new int[] {0});
        handler.unsubAck(1, MqttProperties.EMPTY, new int[0]);
        handler.pingResp();
        handler.securityEstablished(null);
        assertTrue(endpoint.isOpen());
        assertEquals(MqttProtocolHandler.State.CONNECTED, handler.getState());
    }

    // ── application-level async handlers ───────────────────────────────

    private static class Recorder implements ConnectHandler,
            MqttSessionHandler {
        ConnectState connectState;
        PublishState publishState;
        SubscribeState subscribeState;
        boolean disconnected;

        @Override
        public void handleConnect(ConnectState state, ConnectPacket packet,
                Endpoint endpoint) {
            connectState = state;
        }

        @Override
        public void disconnected() {
            disconnected = true;
        }

        @Override
        public void authorizePublish(PublishState state, String clientId,
                String topic, int qos, boolean retain) {
            publishState = state;
        }

        @Override
        public void authorizeSubscription(SubscribeState state,
                String clientId, String topicFilter, QoS requestedQoS) {
            subscribeState = state;
        }
    }

    @Test
    public void asyncConnectAcceptedCompletesHandshake() {
        Recorder r = new Recorder();
        handler.setConnectHandler(r);
        send(MqttPacketEncoder.encodeConnect(connectPacket("a", true, 0)));
        assertTrue(endpoint.getWrites().isEmpty());
        assertEquals(MqttProtocolHandler.State.AWAITING_CONNECT_AUTH,
                handler.getState());
        r.connectState.acceptConnection(r);
        assertEquals(0x20, firstByte(0));
        r.connectState.acceptConnection(r);
        assertEquals(1, endpoint.getWrites().size());
        handler.disconnected();
        assertTrue(r.disconnected);
    }

    @Test
    public void asyncConnectRejections() {
        Recorder r = new Recorder();
        handler.setConnectHandler(r);
        send(MqttPacketEncoder.encodeConnect(connectPacket("a", true, 0)));
        r.connectState.rejectNotAuthorized();
        assertEquals(5, endpoint.getWrites().get(0)[3]);
        assertFalse(endpoint.isOpen());
        r.connectState.rejectBadCredentials();
        assertEquals(1, endpoint.getWrites().size());
    }

    @Test
    public void asyncConnectBadCredentials() {
        Recorder r = new Recorder();
        handler.setConnectHandler(r);
        send(MqttPacketEncoder.encodeConnect(connectPacket("a", true, 0)));
        r.connectState.rejectBadCredentials();
        assertEquals(4, endpoint.getWrites().get(0)[3]);
    }

    private Recorder connectWithHandler() {
        Recorder r = new Recorder();
        handler.setConnectHandler(r);
        send(MqttPacketEncoder.encodeConnect(connectPacket("a", true, 0)));
        r.connectState.acceptConnection(r);
        endpoint.clearWrites();
        return r;
    }

    @Test
    public void asyncPublishAllowedRoutes() {
        Recorder r = connectWithHandler();
        publish("t", 1, false, 4, "x");
        assertTrue(endpoint.getWrites().isEmpty());
        r.publishState.allowPublish();
        assertEquals(0x40, firstByte(0));
        r.publishState.allowPublish();
        assertEquals(1, endpoint.getWrites().size());
    }

    @Test
    public void asyncPublishRejectedQoS1AcksWithoutRouting() {
        Recorder r = connectWithHandler();
        publish("t", 1, false, 4, "x");
        r.publishState.rejectPublish();
        assertEquals(0x40, firstByte(0));
        r.publishState.allowPublish();
        assertEquals(1, endpoint.getWrites().size());
    }

    @Test
    public void asyncSubscribeGrantAndRejectMix() {
        Recorder r = connectWithHandler();
        send(MqttPacketEncoder.encodeSubscribe(3, new String[] {"a"},
                new int[] {1}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        assertTrue(endpoint.getWrites().isEmpty());
        r.subscribeState.grantSubscription(QoS.AT_MOST_ONCE);
        byte[] ack = endpoint.getWrites().get(0);
        assertEquals(0x90, ack[0] & 0xff);
        assertEquals(0, ack[4]);
        r.subscribeState.rejectSubscription();
        assertEquals(1, endpoint.getWrites().size());

        endpoint.clearWrites();
        send(MqttPacketEncoder.encodeSubscribe(4, new String[] {"b"},
                new int[] {1}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        r.subscribeState.rejectSubscription();
        assertEquals(0x80, endpoint.getWrites().get(0)[4] & 0xff);
    }
}
