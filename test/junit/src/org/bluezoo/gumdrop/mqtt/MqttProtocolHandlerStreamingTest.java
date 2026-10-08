/*
 * MqttProtocolHandlerStreamingTest.java
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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.auth.BasicRealm;
import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;
import org.bluezoo.gumdrop.mqtt.codec.MqttPacketEncoder;
import org.bluezoo.gumdrop.mqtt.codec.MqttProperties;
import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.server.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.server.WillManager;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageWriter;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the streaming (non-buffered) publish delivery paths of
 * {@link MqttProtocolHandler} with a message store whose content reports
 * itself as unbuffered, plus the connection tracing and server metrics
 * hooks (a telemetry-enabled endpoint and listener).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttProtocolHandlerStreamingTest {

    /** Content that is never buffered, so delivery must stream it. */
    private static final class StreamedContent implements MqttMessageContent {
        private final byte[] data;
        private final boolean failOpen;
        int releases;

        StreamedContent(byte[] data, boolean failOpen) {
            this.data = data;
            this.failOpen = failOpen;
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
            if (failOpen) {
                throw new IOException("cannot open");
            }
            return Channels.newChannel(new ByteArrayInputStream(data));
        }

        @Override
        public void release() {
            releases++;
        }
    }

    private static final class StreamingStore implements MqttMessageStore {
        boolean failOpen;

        @Override
        public MqttMessageWriter createWriter() {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final boolean fail = failOpen;
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
                    return new StreamedContent(buf.toByteArray(), fail);
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

    /** Wraps an endpoint so it reports telemetry as enabled. */
    private static final class TelemetryHandler implements InvocationHandler {
        private final BinaryRecordingEndpoint delegate;
        private final TelemetryConfig config;
        private Trace trace;

        TelemetryHandler(BinaryRecordingEndpoint delegate,
                         TelemetryConfig config) {
            this.delegate = delegate;
            this.config = config;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            if (name.equals("getTelemetryConfig")) {
                return config;
            }
            if (name.equals("getTrace")) {
                return trace;
            }
            if (name.equals("setTrace")) {
                trace = (Trace) args[0];
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private MqttListener listener;
    private SubscriptionManager subs;
    private WillManager wills;
    private StreamingStore store;

    @Before
    public void setUp() {
        listener = new MqttListener();
        subs = new SubscriptionManager();
        wills = new WillManager();
        store = new StreamingStore();
    }

    private MqttProtocolHandler handler() {
        return new MqttProtocolHandler(listener, subs, wills, store);
    }

    private static ConnectPacket connectPacket(String clientId) {
        ConnectPacket p = new ConnectPacket();
        p.setVersion(MqttVersion.V3_1_1);
        p.setClientId(clientId);
        p.setCleanSession(true);
        p.setKeepAlive(0);
        return p;
    }

    private BinaryRecordingEndpoint client(String id) {
        MqttProtocolHandler h = handler();
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(connectPacket(id)));
        ep.clearWrites();
        return ep;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private MqttProtocolHandler publisher(BinaryRecordingEndpoint ep) {
        MqttProtocolHandler h = handler();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(connectPacket("pub")));
        ep.clearWrites();
        return h;
    }

    private static ByteBuffer publishPacket(String topic, int qos,
            boolean retain, int id, String payload) {
        return MqttPacketEncoder.encodePublish(topic, qos, false, retain, id,
                bytes(payload), MqttProperties.EMPTY, MqttVersion.V3_1_1);
    }

    private static String joined(BinaryRecordingEndpoint ep) {
        return new String(ep.getAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    public void streamedPublishBroadcastToAllOpenSubscribers() {
        BinaryRecordingEndpoint a = client("a");
        BinaryRecordingEndpoint b = client("b");
        BinaryRecordingEndpoint closed = client("closed");
        BinaryRecordingEndpoint gone = client("gone");
        subs.subscribe("a", "t", QoS.AT_MOST_ONCE);
        subs.subscribe("b", "t", QoS.AT_LEAST_ONCE);
        subs.subscribe("closed", "t", QoS.AT_MOST_ONCE);
        subs.subscribe("gone", "t", QoS.AT_MOST_ONCE);
        closed.close();
        subs.getSession("gone").setEndpoint(null);
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        MqttProtocolHandler p = publisher(pubEp);
        p.receive(publishPacket("t", 1, false, 4, "stream-me"));
        assertTrue(joined(a).endsWith("stream-me"));
        assertTrue(joined(b).endsWith("stream-me"));
        assertEquals(0x32, b.getWrites().get(0)[0] & 0xff);
        assertEquals(1, subs.getSession("b").getQoSManager().outboundCount());
        assertTrue(closed.getWrites().isEmpty());
    }

    @Test
    public void streamedPublishWithNoReachableSubscriberSendsNothing() {
        BinaryRecordingEndpoint a = client("a");
        subs.subscribe("a", "t", QoS.AT_MOST_ONCE);
        subs.getSession("a").setEndpoint(null);
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        MqttProtocolHandler p = publisher(pubEp);
        p.receive(publishPacket("t", 0, false, 0, "x"));
        assertTrue(a.getWrites().isEmpty());
    }

    @Test
    public void streamedPublishReadFailureIsLoggedNotThrown() {
        store.failOpen = true;
        BinaryRecordingEndpoint a = client("a");
        subs.subscribe("a", "t", QoS.AT_MOST_ONCE);
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        MqttProtocolHandler p = publisher(pubEp);
        p.receive(publishPacket("t", 0, false, 0, "x"));
        // The header was sent, the payload could not be streamed
        assertEquals(1, a.getWrites().size());
    }

    @Test
    public void retainedStreamedMessageIsStreamedOnSubscribe() {
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        MqttProtocolHandler p = publisher(pubEp);
        p.receive(publishPacket("r/1", 1, true, 2, "kept-big"));
        assertEquals(1, subs.getRetainedStore().size());

        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        MqttProtocolHandler h = handler();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(connectPacket("sub")));
        ep.clearWrites();
        h.receive(MqttPacketEncoder.encodeSubscribe(1, new String[] {"r/+"},
                new int[] {1}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        assertTrue(joined(ep).contains("kept-big"));
        assertEquals(1, subs.getSession("sub").getQoSManager()
                .outboundCount());
    }

    @Test
    public void retainedStreamReadFailureIsLoggedNotThrown() {
        store.failOpen = true;
        BinaryRecordingEndpoint pubEp = new BinaryRecordingEndpoint();
        MqttProtocolHandler p = publisher(pubEp);
        p.receive(publishPacket("r/1", 0, true, 0, "kept"));
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        MqttProtocolHandler h = handler();
        h.connected(ep);
        h.receive(MqttPacketEncoder.encodeConnect(connectPacket("sub")));
        ep.clearWrites();
        h.receive(MqttPacketEncoder.encodeSubscribe(1, new String[] {"r/+"},
                new int[] {0}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        List<byte[]> writes = ep.getWrites();
        assertEquals(2, writes.size());
    }

    @Test
    public void tracingAndMetricsFollowTheConnectionLifecycle() throws Exception {
        TelemetryConfig tc = new TelemetryConfig();
        tc.setTracesEnabled(true);
        tc.setMetricsEnabled(true);
        listener.setTelemetryConfig(tc);
        listener.start();
        assertNotNull(listener.getMetrics());
        BasicRealm realm = new BasicRealm() {
            @Override
            public boolean passwordMatch(String username, String password) {
                return username.equals("u") && password.equals("p");
            }
        };
        listener.setRealm(realm);

        BinaryRecordingEndpoint raw = new BinaryRecordingEndpoint();
        TelemetryHandler th = new TelemetryHandler(raw, tc);
        Endpoint ep = (Endpoint) Proxy.newProxyInstance(
                Endpoint.class.getClassLoader(),
                new Class<?>[] {Endpoint.class}, th);
        MqttProtocolHandler h = handler();
        h.connected(ep);
        assertNotNull(th.trace);
        ConnectPacket p = connectPacket("traced");
        p.setUsername("u");
        p.setPassword(bytes("p"));
        h.receive(MqttPacketEncoder.encodeConnect(p));
        assertEquals(0, raw.getWrites().get(0)[3]);
        h.receive(MqttPacketEncoder.encodeSubscribe(1, new String[] {"x"},
                new int[] {0}, MqttProperties.EMPTY, MqttVersion.V3_1_1));
        h.receive(publishPacket("x", 0, false, 0, "m"));
        h.receive(MqttPacketEncoder.encodeUnsubscribe(2, new String[] {"x"},
                MqttProperties.EMPTY, MqttVersion.V3_1_1));
        h.receive(MqttPacketEncoder.encodeDisconnect(0, MqttProperties.EMPTY,
                MqttVersion.V3_1_1));
        h.disconnected();
        assertFalse(raw.isOpen());

        BinaryRecordingEndpoint raw2 = new BinaryRecordingEndpoint();
        Endpoint ep2 = (Endpoint) Proxy.newProxyInstance(
                Endpoint.class.getClassLoader(),
                new Class<?>[] {Endpoint.class},
                new TelemetryHandler(raw2, tc));
        MqttProtocolHandler h2 = handler();
        h2.connected(ep2);
        ConnectPacket bad = connectPacket("bad");
        bad.setUsername("u");
        bad.setPassword(bytes("wrong"));
        h2.receive(MqttPacketEncoder.encodeConnect(bad));
        assertEquals(4, raw2.getWrites().get(0)[3]);
        assertFalse(raw2.isOpen());
        h2.disconnected();
        ConnectPacket none = connectPacket("none");
        BinaryRecordingEndpoint raw3 = new BinaryRecordingEndpoint();
        MqttProtocolHandler h3 = handler();
        h3.connected(raw3);
        h3.receive(MqttPacketEncoder.encodeConnect(none));
        assertEquals(4, raw3.getWrites().get(0)[3]);
    }
}
