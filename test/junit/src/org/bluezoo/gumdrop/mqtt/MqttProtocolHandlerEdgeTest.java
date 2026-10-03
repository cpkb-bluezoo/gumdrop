/*
 * MqttProtocolHandlerEdgeTest.java
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
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
import org.bluezoo.gumdrop.mqtt.server.QoSManager;
import org.bluezoo.gumdrop.mqtt.server.SubscribeState;
import org.bluezoo.gumdrop.mqtt.server.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.server.WillManager;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageWriter;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge branches of {@link MqttProtocolHandler}: telemetry spans of a session,
 * acknowledgements outside the connected state, packet identifier exhaustion
 * for buffered, streamed and retained deliveries, subscribers that vanished,
 * will messages with empty payloads and v5 publish rejection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttProtocolHandlerEdgeTest {

    /** Config that remembers the traces it created. */
    private static final class CapturingConfig extends TelemetryConfig {
        final List<Trace> traces = new ArrayList<Trace>();

        CapturingConfig() {
            setTracesEnabled(true);
        }

        @Override
        public Trace createTrace(String rootSpanName, SpanKind kind) {
            Trace trace = super.createTrace(rootSpanName, kind);
            if (trace != null) {
                traces.add(trace);
            }
            return trace;
        }
    }

    /** Config that declines to create traces although telemetry is on. */
    private static final class NullTraceConfig extends TelemetryConfig {
        NullTraceConfig() {
            setTracesEnabled(true);
        }

        @Override
        public Trace createTrace(String rootSpanName, SpanKind kind) {
            return null;
        }
    }

    /** Content that is never buffered, so delivery must stream it. */
    private static final class StreamedContent implements MqttMessageContent {
        private final byte[] data;

        StreamedContent(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public boolean isBuffered() {
            return false;
        }

        @Override
        public byte[] asByteArray() {
            return data;
        }

        @Override
        public ReadableByteChannel openChannel() throws IOException {
            return Channels.newChannel(new ByteArrayInputStream(data));
        }

        @Override
        public void release() {
        }
    }

    private static final class StreamingStore implements MqttMessageStore {
        @Override
        public MqttMessageWriter createWriter() {
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            return new MqttMessageWriter() {
                private boolean open = true;

                @Override
                public int write(ByteBuffer src) {
                    int n = src.remaining();
                    byte[] b = new byte[n];
                    src.get(b);
                    buf.write(b, 0, n);
                    return n;
                }

                @Override
                public MqttMessageContent commit() {
                    open = false;
                    return new StreamedContent(buf.toByteArray());
                }

                @Override
                public void discard() {
                    open = false;
                }

                @Override
                public boolean isOpen() {
                    return open;
                }

                @Override
                public void close() {
                    open = false;
                }
            };
        }
    }

    private static class Decider implements ConnectHandler, MqttSessionHandler {
        ConnectState connectState;
        PublishState publishState;
        SubscribeState subscribeState;

        @Override
        public void handleConnect(ConnectState state, ConnectPacket packet, Endpoint endpoint) {
            connectState = state;
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void authorizePublish(PublishState state, String clientId, String topic,
                int qos, boolean retain) {
            publishState = state;
        }

        @Override
        public void authorizeSubscription(SubscribeState state, String clientId,
                String topicFilter, QoS requestedQoS) {
            subscribeState = state;
        }
    }

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
        handler = newHandler(new InMemoryMessageStore());
        endpoint = new BinaryRecordingEndpoint();
    }

    private MqttProtocolHandler newHandler(MqttMessageStore store) {
        return new MqttProtocolHandler(listener, subs, wills, store);
    }

    private static ConnectPacket connectPacket(String clientId, boolean clean, int keepAlive,
            MqttVersion version) {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(version);
        p.setClientId(clientId);
        p.setCleanSession(clean);
        p.setKeepAlive(keepAlive);
        return p;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void connect(String id) {
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket(id, true, 0, MqttVersion.V3_1_1)));
        endpoint.clearWrites();
    }

    private BinaryRecordingEndpoint client(MqttMessageStore store, String id, boolean clean) {
        MqttProtocolHandler h = newHandler(store);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(
                connectPacket(id, clean, 0, MqttVersion.V3_1_1)));
        ep.clearWrites();
        return ep;
    }

    private void subscribe(MqttProtocolHandler h, String filter, int qos) {
        h.receive(MqttPacketEncoder.encodeSubscribe(1, new String[] {filter},
                new int[] {qos}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
    }

    private static ByteBuffer publishPacket(String topic, int qos, boolean retain, int id,
            String payload) {
        return MqttPacketEncoder.encodePublish(topic, qos, false, retain, id,
                bytes(payload), MqttProperties.EMPTY, MqttVersion.V3_1_1);
    }

    private static void collect(Span span, List<String> events, List<String> attrs) {
        for (SpanEvent e : span.getEvents()) {
            events.add(e.getName());
        }
        for (Attribute a : span.getAttributes()) {
            attrs.add(a.getKey());
        }
        for (Span child : span.getChildren()) {
            collect(child, events, attrs);
        }
    }

    private static List<String> events(Trace trace) {
        List<String> events = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        collect(trace.getRootSpan(), events, attrs);
        return events;
    }

    private static List<String> attrs(Trace trace) {
        List<String> events = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        collect(trace.getRootSpan(), events, attrs);
        return attrs;
    }

    private static boolean allEnded(Span span) {
        if (!span.isEnded()) {
            return false;
        }
        for (Span child : span.getChildren()) {
            if (!allEnded(child)) {
                return false;
            }
        }
        return true;
    }

    // ── telemetry ──

    @Test
    public void cleanSessionIsRecordedOnTheSessionAndAuthenticatedSpans() {
        CapturingConfig config = new CapturingConfig();
        endpoint.setTelemetryConfig(config);
        handler.connected(endpoint);
        ConnectPacket p = connectPacket("traced", true, 0, MqttVersion.V3_1_1);
        p.setUsername("alice");
        p.setPassword(bytes("pw"));
        handler.receive(MqttPacketEncoder.encodeConnect(p));
        subscribe(handler, "t/#", 0);
        handler.receive(publishPacket("t/x", 0, false, 0, "m"));
        handler.receive(MqttPacketEncoder.encodeUnsubscribe(2, new String[] {"t/#"},
                MqttProperties.EMPTY, MqttVersion.V3_1_1));
        handler.receive(MqttPacketEncoder.encodeDisconnect(0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        handler.disconnected();
        assertEquals(1, config.traces.size());
        Trace trace = config.traces.get(0);
        List<String> events = events(trace);
        assertTrue(events.toString(), events.contains("CONNECTED"));
        assertTrue(events.toString(), events.contains("SUBSCRIBE"));
        assertTrue(events.toString(), events.contains("PUBLISH"));
        assertTrue(events.toString(), events.contains("UNSUBSCRIBE"));
        assertTrue(events.toString(), events.contains("DISCONNECT"));
        List<String> attrs = attrs(trace);
        assertTrue(attrs.toString(), attrs.contains("mqtt.client_id"));
        assertTrue(attrs.toString(), attrs.contains("mqtt.clean_session"));
        assertTrue(attrs.toString(), attrs.contains("enduser.id"));
        assertTrue(attrs.toString(), attrs.contains("net.peer.ip"));
        for (Span child : trace.getRootSpan().getChildren()) {
            assertTrue(child.getName(), allEnded(child));
        }
    }

    @Test
    public void connectionWithoutUsernameHasNoAuthenticatedSpan() {
        CapturingConfig config = new CapturingConfig();
        endpoint.setTelemetryConfig(config);
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("anon", true, 0, MqttVersion.V3_1_1)));
        assertFalse(attrs(config.traces.get(0)).contains("enduser.id"));
    }

    @Test
    public void abruptDisconnectEndsTheSessionSpanWithAnError() {
        CapturingConfig config = new CapturingConfig();
        endpoint.setTelemetryConfig(config);
        handler.connected(endpoint);
        ConnectPacket p = connectPacket("lost", true, 0, MqttVersion.V3_1_1);
        p.setUsername("alice");
        handler.receive(MqttPacketEncoder.encodeConnect(p));
        handler.disconnected();
        Trace trace = config.traces.get(0);
        boolean sawError = false;
        for (Span child : trace.getRootSpan().getChildren()) {
            if (child.getStatus() != null && child.getStatus().isError()) {
                sawError = true;
            }
        }
        assertTrue(sawError);
        assertFalse(events(trace).contains("DISCONNECT"));
    }

    @Test
    public void connectionErrorIsRecordedAndClosesTheEndpoint() {
        CapturingConfig config = new CapturingConfig();
        endpoint.setTelemetryConfig(config);
        handler.connected(endpoint);
        handler.error(new IOException("boom"));
        assertFalse(endpoint.isOpen());
        Trace trace = config.traces.get(0);
        assertTrue(events(trace).toString(), events(trace).contains("exception"));
    }

    @Test
    public void telemetryEnabledEndpointWithoutATraceIsTolerated() {
        endpoint.setTelemetryConfig(new NullTraceConfig());
        handler.connected(endpoint);
        assertNull(endpoint.getTrace());
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("quiet", true, 0, MqttVersion.V3_1_1)));
        handler.error(new IOException("boom"));
        handler.disconnected();
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void disconnectingBeforeConnectingEndsCleanly() {
        handler.connected(endpoint);
        handler.disconnected();
        assertNull(handler.getSession());
    }

    @Test
    public void disconnectPacketBeforeConnectClosesTheEndpoint() {
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeDisconnect(0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        assertFalse(endpoint.isOpen());
        assertEquals(MqttProtocolHandler.State.DISCONNECTING, handler.getState());
    }

    // ── acknowledgements outside the connected state ──

    @Test
    public void acknowledgementsBeforeConnectAreIgnored() {
        handler.connected(endpoint);
        handler.pubAck(1, 0, MqttProperties.EMPTY);
        handler.pubRec(1, 0, MqttProperties.EMPTY);
        handler.pubRel(1, 0, MqttProperties.EMPTY);
        handler.pubComp(1, 0, MqttProperties.EMPTY);
        assertTrue(endpoint.getWrites().isEmpty());
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void acknowledgementsAfterDisconnectAreIgnored() {
        connect("c1");
        handler.receive(MqttPacketEncoder.encodeDisconnect(0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        handler.pubAck(1, 0, MqttProperties.EMPTY);
        handler.pubRec(1, 0, MqttProperties.EMPTY);
        handler.pubRel(1, 0, MqttProperties.EMPTY);
        handler.pubComp(1, 0, MqttProperties.EMPTY);
        assertTrue(endpoint.getWrites().isEmpty());
    }

    @Test
    public void releaseOfAnUnknownQoS2PacketStillGetsPubComp() {
        connect("c1");
        handler.receive(MqttPacketEncoder.encodePubRel(77, 0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        List<byte[]> w = endpoint.getWrites();
        assertEquals(1, w.size());
        assertEquals(0x70, w.get(0)[0] & 0xff);
    }

    // ── connect variants ──

    @Test
    public void willWithEmptyPayloadAndNoQosIsStoredAtMostOnce() {
        handler.connected(endpoint);
        ConnectPacket p = connectPacket("w", true, 0, MqttVersion.V3_1_1);
        p.setWillFlag(true);
        p.setWillTopic("will/topic");
        p.setWillPayload(new byte[0]);
        p.setWillQoS(null);
        handler.receive(MqttPacketEncoder.encodeConnect(p));
        assertEquals(MqttProtocolHandler.State.CONNECTED, handler.getState());
        BinaryRecordingEndpoint watcher = client(new InMemoryMessageStore(), "watch", true);
        assertNotNull(watcher);
    }

    @Test
    public void tooLargePacketsAreRejectedWhenALimitIsConfigured() {
        listener.setMaxPacketSize(20);
        MqttProtocolHandler h = newHandler(new InMemoryMessageStore());
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        ConnectPacket p = connectPacket("a-rather-long-client-identifier", true, 0, MqttVersion.V3_1_1);
        h.receive(MqttPacketEncoder.encodeConnect(p));
        assertFalse(ep.isOpen());
    }

    @Test
    public void acceptingTheConnectionWithoutASessionHandlerKeepsPlainRouting() {
        Decider decider = new Decider();
        handler.setConnectHandler(decider);
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("plain", true, 0, MqttVersion.V3_1_1)));
        decider.connectState.acceptConnection(null);
        assertEquals(MqttProtocolHandler.State.CONNECTED, handler.getState());
        subscribe(handler, "x", 0);
        handler.receive(publishPacket("x", 0, false, 0, "m"));
        assertEquals("connack, suback and the routed publish", 3, endpoint.getWrites().size());
    }

    // ── publish rejection ──

    @Test
    public void rejectedQoS0PublishIsSilentAndV5Qos1UsesTheNotAuthorizedReason() {
        Decider decider = new Decider();
        handler.setConnectHandler(decider);
        handler.connected(endpoint);
        ConnectPacket p = connectPacket("v5", true, 0, MqttVersion.V5_0);
        handler.receive(MqttPacketEncoder.encodeConnect(p));
        decider.connectState.acceptConnection(decider);
        endpoint.clearWrites();

        handler.receive(MqttPacketEncoder.encodePublish("a", 0, false, false, 0,
                bytes("x"), MqttProperties.EMPTY, MqttVersion.V5_0));
        decider.publishState.rejectPublish();
        assertTrue(endpoint.getWrites().isEmpty());

        handler.receive(MqttPacketEncoder.encodePublish("a", 1, false, false, 5,
                bytes("x"), MqttProperties.EMPTY, MqttVersion.V5_0));
        decider.publishState.rejectPublish();
        List<byte[]> w = endpoint.getWrites();
        assertEquals(1, w.size());
        byte[] puback = w.get(0);
        assertEquals(0x40, puback[0] & 0xff);
        assertEquals(0x87, puback[4] & 0xff);
    }

    // ── delivery edge cases ──

    private static void exhaustPacketIds(MqttSession session) {
        QoSManager qos = session.getQoSManager();
        MqttMessageContent filler = new StreamedContent(new byte[0]);
        for (int i = 0; i < 65535; i++) {
            int id = qos.nextPacketId();
            qos.trackOutbound(new QoSManager.InFlightMessage(id, "fill", filler, QoS.AT_LEAST_ONCE));
        }
        assertEquals(QoSManager.NO_PACKET_ID_AVAILABLE, qos.nextPacketId());
    }

    @Test
    public void bufferedDeliveryIsDroppedWhenTheSubscriberHasNoFreePacketId() {
        MqttMessageStore store = new InMemoryMessageStore();
        MqttProtocolHandler subHandler = newHandler(store);
        BinaryRecordingEndpoint subEp = new BinaryRecordingEndpoint();
        subHandler.connected(subEp);
        subHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("sub", true, 0, MqttVersion.V3_1_1)));
        subscribe(subHandler, "full", 1);
        exhaustPacketIds(subs.getSession("sub"));
        subEp.clearWrites();
        connect("pub");
        handler.receive(publishPacket("full", 1, false, 3, "payload"));
        assertTrue("nothing could be delivered", subEp.getWrites().isEmpty());
    }

    @Test
    public void streamedDeliveryIsSkippedWhenTheSubscriberHasNoFreePacketId() {
        StreamingStore store = new StreamingStore();
        MqttProtocolHandler subHandler = newHandler(store);
        BinaryRecordingEndpoint subEp = new BinaryRecordingEndpoint();
        subHandler.connected(subEp);
        subHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("sub", true, 0, MqttVersion.V3_1_1)));
        subscribe(subHandler, "full", 1);
        exhaustPacketIds(subs.getSession("sub"));
        subEp.clearWrites();
        MqttProtocolHandler pubHandler = newHandler(store);
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        pubHandler.connected(pubEp);
        pubHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("pub", true, 0, MqttVersion.V3_1_1)));
        pubHandler.receive(publishPacket("full", 1, false, 3, "payload"));
        assertTrue("nothing could be delivered", subEp.getWrites().isEmpty());
    }

    @Test
    public void retainedDeliveryIsDroppedWhenTheNewSubscriberHasNoFreePacketId() {
        client(new InMemoryMessageStore(), "pub", true);
        MqttProtocolHandler pubHandler = newHandler(new InMemoryMessageStore());
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        pubHandler.connected(pubEp);
        pubHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("pub2", true, 0, MqttVersion.V3_1_1)));
        pubHandler.receive(publishPacket("kept", 1, true, 9, "retained"));

        MqttProtocolHandler subHandler = newHandler(new InMemoryMessageStore());
        BinaryRecordingEndpoint subEp = new BinaryRecordingEndpoint();
        subHandler.connected(subEp);
        subHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("late", true, 0, MqttVersion.V3_1_1)));
        exhaustPacketIds(subs.getSession("late"));
        subEp.clearWrites();
        subscribe(subHandler, "kept", 1);
        List<byte[]> w = subEp.getWrites();
        assertEquals("only the SUBACK, the retained message was dropped", 1, w.size());
        assertEquals(0x90, w.get(0)[0] & 0xff);
    }

    @Test
    public void vanishedAndClosedSubscribersAreSkipped() {
        BinaryRecordingEndpoint gone = client(new InMemoryMessageStore(), "gone", true);
        MqttProtocolHandler goneHandler = newHandler(new InMemoryMessageStore());
        BinaryRecordingEndpoint goneEp = new BinaryRecordingEndpoint();
        goneHandler.connected(goneEp);
        goneHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("closed", true, 0, MqttVersion.V3_1_1)));
        subscribe(goneHandler, "news", 0);
        goneEp.clearWrites();
        goneEp.close();
        subs.subscribe("ghost", "news", QoS.AT_MOST_ONCE);
        connect("pub");
        handler.receive(publishPacket("news", 0, false, 0, "hello"));
        assertTrue(goneEp.getWrites().isEmpty());
        assertTrue(gone.getWrites().isEmpty());
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void streamedDeliverySkipsSubscribersThatAreGoneOrClosed() {
        StreamingStore store = new StreamingStore();
        MqttProtocolHandler closedHandler = newHandler(store);
        BinaryRecordingEndpoint closedEp = new BinaryRecordingEndpoint();
        closedHandler.connected(closedEp);
        closedHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("closed", true, 0, MqttVersion.V3_1_1)));
        subscribe(closedHandler, "news", 0);
        closedEp.clearWrites();
        closedEp.close();
        subs.subscribe("ghost", "news", QoS.AT_MOST_ONCE);
        MqttProtocolHandler pubHandler = newHandler(store);
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        pubHandler.connected(pubEp);
        pubHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("pub", true, 0, MqttVersion.V3_1_1)));
        pubHandler.receive(publishPacket("news", 0, false, 0, "hello"));
        assertTrue(closedEp.getWrites().isEmpty());
        assertTrue(pubEp.isOpen());
    }

    @Test
    public void streamedRetainedMessageUsesTheSubscribersGrantedQos() {
        StreamingStore store = new StreamingStore();
        MqttProtocolHandler pubHandler = newHandler(store);
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        pubHandler.connected(pubEp);
        pubHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("pub", true, 0, MqttVersion.V3_1_1)));
        pubHandler.receive(publishPacket("kept", 1, true, 9, "retained"));
        MqttProtocolHandler subHandler = newHandler(store);
        BinaryRecordingEndpoint subEp = new BinaryRecordingEndpoint();
        subHandler.connected(subEp);
        subHandler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("sub", true, 0, MqttVersion.V3_1_1)));
        subEp.clearWrites();
        subscribe(subHandler, "kept", 0);
        List<byte[]> w = subEp.getWrites();
        assertTrue("retained publish and suback: " + w.size(), w.size() >= 2);
        assertEquals("the retained message is delivered first, as QoS 0 retained", 0x31, w.get(0)[0] & 0xff);
        assertEquals(0x90, w.get(w.size() - 1)[0] & 0xff);
    }

    @Test
    public void keepAliveTimerIsNotArmedForSessionsWithoutKeepAlive() {
        connect("c1");
        handler.receive(MqttPacketEncoder.encodePingReq());
        assertEquals(1, endpoint.getWrites().size());
        assertTrue(endpoint.getTimers().size() >= 1);
        for (BinaryRecordingEndpoint.StubTimer t : endpoint.getTimers()) {
            assertTrue("only the cancelled connect timer exists", t.isCancelled());
        }
    }

    // ── more connect variants, driven directly ──

    @Test
    public void connectPacketWithoutClientIdOrQosOrPayloadGetsDefaults() {
        handler.connected(endpoint);
        ConnectPacket p = connectPacket(null, true, 0, MqttVersion.V3_1_1);
        p.setWillFlag(true);
        p.setWillTopic("will/none");
        p.setWillPayload(null);
        p.setWillQoS(null);
        handler.connect(p);
        assertEquals(MqttProtocolHandler.State.CONNECTED, handler.getState());
        String generated = handler.getSession().getClientId();
        assertFalse(generated.isEmpty());
        assertNotNull(wills);
    }

    @Test
    public void unlimitedPacketSizeIsHonoured() {
        listener.setMaxPacketSize(0);
        MqttProtocolHandler h = newHandler(new InMemoryMessageStore());
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("unbounded", true, 0, MqttVersion.V3_1_1)));
        assertEquals(MqttProtocolHandler.State.CONNECTED, h.getState());
    }

    @Test
    public void realmRejectsAUsernameWithoutAPassword() {
        org.bluezoo.gumdrop.auth.BasicRealm realm = new org.bluezoo.gumdrop.auth.BasicRealm() {
            @Override
            public boolean passwordMatch(String username, String password) {
                return true;
            }
        };
        listener.setRealm(realm);
        TelemetryConfig tc = new TelemetryConfig();
        tc.setMetricsEnabled(true);
        listener.setTelemetryConfig(tc);
        listener.start();
        handler.connected(endpoint);
        ConnectPacket p = connectPacket("nopw", true, 0, MqttVersion.V3_1_1);
        p.setUsername("alice");
        handler.receive(MqttPacketEncoder.encodeConnect(p));
        assertEquals(4, endpoint.getWrites().get(0)[3]);
        assertFalse(endpoint.isOpen());
        BinaryRecordingEndpoint ok = new BinaryRecordingEndpoint();
        MqttProtocolHandler h = newHandler(new InMemoryMessageStore());
        h.connected(ok);
        ConnectPacket good = connectPacket("withpw", true, 0, MqttVersion.V3_1_1);
        good.setUsername("alice");
        good.setPassword(bytes("pw"));
        h.receive(MqttPacketEncoder.encodeConnect(good));
        assertEquals(0, ok.getWrites().get(0)[3]);
        h.disconnected();
    }

    @Test
    public void cleanDisconnectOfAPersistentSessionKeepsItsSubscriptions() {
        MqttProtocolHandler h = newHandler(new InMemoryMessageStore());
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("keeper", false, 0, MqttVersion.V3_1_1)));
        subscribe(h, "keep/#", 1);
        h.receive(MqttPacketEncoder.encodeDisconnect(0, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        assertFalse(ep.isOpen());
        assertTrue(subs.getSubscriptions("keeper").contains("keep/#"));
    }

    @Test
    public void sendingAfterTheEndpointClosedIsSkipped() {
        connect("c1");
        endpoint.close();
        handler.receive(publishPacket("t", 1, false, 4, "m"));
        assertTrue(endpoint.getWrites().isEmpty());
    }

    @Test
    public void retainedPublishWithoutSubscribersKeepsItsContent() {
        connect("c1");
        handler.receive(publishPacket("lonely", 0, true, 0, "kept"));
        assertEquals(1, subs.getRetainedStore().match("lonely").size());
    }

    // ── asynchronous decisions resolved more than once or inline ──

    private static final class Immediate extends Decider {
        @Override
        public void authorizeSubscription(SubscribeState state, String clientId,
                String topicFilter, QoS requestedQoS) {
            state.grantSubscription(requestedQoS);
            state.rejectSubscription();
            state.grantSubscription(QoS.AT_MOST_ONCE);
        }
    }

    @Test
    public void subscriptionDecisionsResolveOnlyOnce() {
        Immediate decider = new Immediate();
        handler.setConnectHandler(decider);
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("once", true, 0, MqttVersion.V3_1_1)));
        decider.connectState.acceptConnection(decider);
        endpoint.clearWrites();
        subscribe(handler, "a", 1);
        List<byte[]> w = endpoint.getWrites();
        assertEquals(1, w.size());
        assertEquals(0x90, w.get(0)[0] & 0xff);
        assertEquals("the first decision wins", 1, w.get(0)[4]);
    }

    @Test
    public void publishDecisionsResolveOnlyOnce() {
        Decider decider = new Decider();
        handler.setConnectHandler(decider);
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("once", true, 0, MqttVersion.V3_1_1)));
        decider.connectState.acceptConnection(decider);
        endpoint.clearWrites();
        handler.receive(publishPacket("a", 1, false, 7, "m"));
        decider.publishState.allowPublish();
        decider.publishState.allowPublish();
        decider.publishState.rejectPublish();
        assertEquals(1, endpoint.getWrites().size());
        handler.receive(publishPacket("a", 1, false, 8, "m"));
        decider.publishState.rejectPublish();
        decider.publishState.allowPublish();
        decider.publishState.rejectPublish();
        assertEquals(2, endpoint.getWrites().size());
    }

    // ── telemetry corner cases ──

    @Test
    public void repeatedTeardownLeavesEndedSpansAlone() {
        CapturingConfig config = new CapturingConfig();
        endpoint.setTelemetryConfig(config);
        handler.connected(endpoint);
        ConnectPacket p = connectPacket("twice", true, 0, MqttVersion.V3_1_1);
        p.setUsername("alice");
        handler.receive(MqttPacketEncoder.encodeConnect(p));
        handler.receive(MqttPacketEncoder.encodeDisconnect(0, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        handler.disconnected();
        handler.disconnected();
        handler.error(new IOException("late"));
        Trace trace = config.traces.get(0);
        for (Span child : trace.getRootSpan().getChildren()) {
            assertTrue(child.getName(), allEnded(child));
            assertFalse(child.getName(), child.getStatus() != null && child.getStatus().isError());
        }
    }

    @Test
    public void nullTraceWithAUsernameSkipsTheAuthenticatedSpan() {
        endpoint.setTelemetryConfig(new NullTraceConfig());
        handler.connected(endpoint);
        ConnectPacket p = connectPacket("quiet", true, 0, MqttVersion.V3_1_1);
        p.setUsername("alice");
        handler.receive(MqttPacketEncoder.encodeConnect(p));
        assertEquals(MqttProtocolHandler.State.CONNECTED, handler.getState());
        assertNull(endpoint.getTrace());
    }

    @Test
    public void fineLoggingDoesNotChangeConnectionHandling() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(
                MqttProtocolHandler.class.getName());
        java.util.logging.Level saved = logger.getLevel();
        logger.setLevel(java.util.logging.Level.FINE);
        try {
            handler.connected(endpoint);
            handler.securityEstablished(null);
            handler.receive(MqttPacketEncoder.encodeConnect(
                    connectPacket("fine", true, 5, MqttVersion.V3_1_1)));
            handler.auth(0, MqttProperties.EMPTY);
            assertEquals(MqttProtocolHandler.State.CONNECTED, handler.getState());
            for (BinaryRecordingEndpoint.StubTimer t : endpoint.getTimers()) {
                if (!t.isCancelled()) {
                    assertTrue(t.getDelayMs() > 0);
                }
            }
            endpoint.fireTimers();
            assertFalse(endpoint.isOpen());
        } finally {
            logger.setLevel(saved);
        }
    }

    // ── connect timeout while the asynchronous decision is pending ──

    private static int liveTimers(BinaryRecordingEndpoint ep) {
        int n = 0;
        for (BinaryRecordingEndpoint.StubTimer t : ep.getTimers()) {
            if (!t.isCancelled()) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void connectTimeoutClosesAConnectionWhoseAsyncDecisionNeverArrives() {
        Decider decider = new Decider();
        handler.setConnectHandler(decider);
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("slow", true, 0, MqttVersion.V3_1_1)));
        assertEquals(MqttProtocolHandler.State.AWAITING_CONNECT_AUTH, handler.getState());
        assertEquals("the connect timer stays armed", 1, liveTimers(endpoint));
        endpoint.fireTimers();
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void connectTimeoutAlsoAppliesToVersion5() {
        Decider decider = new Decider();
        handler.setConnectHandler(decider);
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("slow5", true, 0, MqttVersion.V5_0)));
        endpoint.fireTimers();
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void connectTimerIsCancelledOnceTheDecisionIsMade() {
        Decider accepted = new Decider();
        handler.setConnectHandler(accepted);
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("ok", true, 0, MqttVersion.V3_1_1)));
        accepted.connectState.acceptConnection(accepted);
        assertEquals(0, liveTimers(endpoint));
        endpoint.fireTimers();
        assertEquals(0, endpoint.getCloseCount());

        MqttProtocolHandler h = newHandler(new InMemoryMessageStore());
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        Decider rejected = new Decider();
        h.setConnectHandler(rejected);
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("no", true, 0, MqttVersion.V3_1_1)));
        rejected.connectState.rejectNotAuthorized();
        assertEquals(0, liveTimers(ep));
        assertEquals(1, ep.getCloseCount());
        ep.fireTimers();
        assertEquals(1, ep.getCloseCount());
    }

    @Test
    public void connectTimerIsCancelledAfterASynchronousAccept() {
        handler.connected(endpoint);
        handler.receive(MqttPacketEncoder.encodeConnect(
                connectPacket("sync", true, 0, MqttVersion.V3_1_1)));
        assertEquals(0, liveTimers(endpoint));
    }
}
