/*
 * MqttServerCompositionTest.java
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


package org.bluezoo.gumdrop.mqtt.server;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.BasicRealm;
import org.bluezoo.gumdrop.mqtt.MqttListener;
import org.bluezoo.gumdrop.mqtt.MqttProtocolHandler;
import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;
import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageStore;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Tests for {@link MqttServer} composition and lifecycle, the session
 * provider helpers, {@link MqttSession} and {@link WillManager}, using
 * recording listeners instead of real sockets.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttServerCompositionTest {

    /** Listener that records lifecycle calls instead of binding. */
    private static class StubListener extends MqttListener {
        final List<String> calls;
        final boolean failStart;
        final boolean failStop;

        StubListener(List<String> calls, boolean failStart, boolean failStop) {
            this.calls = calls;
            this.failStart = failStart;
            this.failStop = failStop;
        }

        @Override
        public void start(Gumdrop gumdrop) {
            calls.add("start");
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
        }

        @Override
        public void stop() {
            calls.add("stop");
            if (failStop) {
                throw new IllegalStateException("stop failed");
            }
        }
    }

    /** Provider that records start/stop and hands out no handler. */
    private static class CountingProvider implements MqttServerSessionProvider {
        int starts;
        int stops;
        int opens;

        @Override
        public ConnectHandler openSession(TcpListener listener) {
            opens++;
            return null;
        }

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private static class Handler implements ConnectHandler {
        @Override
        public void handleConnect(ConnectState state, ConnectPacket packet, Endpoint endpoint) {
        }

        @Override
        public void disconnected() {
        }
    }

    @Test
    public void composeRequiresAListener() {
        try {
            MqttServer.compose().server();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("at least one listener is required", expected.getMessage());
        }
    }

    @Test
    public void composerRejectsNulls() {
        try {
            MqttServer.compose().listener(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("listener", expected.getMessage());
        }
        try {
            MqttServer.compose().sessionProvider(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("provider", expected.getMessage());
        }
        try {
            MqttServerSessionProviders.perSession(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("supplier", expected.getMessage());
        }
    }

    @Test
    public void composedServerCarriesConfiguration() {
        MqttListener listener = new MqttListener();
        BasicRealm realm = new BasicRealm();
        MqttServer server = MqttServer.compose().listener(listener).realm(realm)
                .maxPacketSize(2048).server();
        assertSame(realm, server.getRealm());
        assertEquals(2048, server.getMaxPacketSize());
        assertEquals(1, server.getListeners().size());
        assertSame(listener, server.getListeners().get(0));
        assertNotNull(server.getSubscriptionManager());
        assertNotNull(server.getWillManager());
        assertNull(server.getMessageStore());
        assertNull(server.getSessionProvider());
        try {
            server.getListeners().add(new MqttListener());
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, server.getListeners().size());
        }
    }

    @Test
    public void openBrokerReturnsNullHandlerAndProtocolHandler() {
        MqttListener listener = new MqttListener();
        MqttServer server = MqttServer.compose().listener(listener).server();
        assertNull(server.openSession(listener));
        MqttProtocolHandler handler = server.createProtocolHandler(listener);
        assertNotNull(handler);
    }

    @Test
    public void sessionPerConnectionUsesSupplier() {
        final Handler fixed = new Handler();
        Supplier<ConnectHandler> supplier = new Supplier<ConnectHandler>() {
            @Override
            public ConnectHandler get() {
                return fixed;
            }
        };
        MqttListener listener = new MqttListener();
        MqttServer server = MqttServer.compose().listener(listener)
                .sessionPerConnection(supplier).server();
        assertSame(fixed, server.openSession(listener));
        MqttProtocolHandler handler = server.createProtocolHandler(listener);
        assertNotNull(handler);
    }

    @Test
    public void startWiresListenersAndStopTearsDown() {
        List<String> calls = new ArrayList<String>();
        StubListener l1 = new StubListener(calls, false, false);
        StubListener l2 = new StubListener(calls, false, false);
        BasicRealm realm = new BasicRealm();
        CountingProvider provider = new CountingProvider();
        MqttServer server = MqttServer.compose().listener(l1).listener(l2)
                .sessionProvider(provider).realm(realm).maxPacketSize(4096).server();
        server.start(null);
        assertEquals(1, provider.starts);
        assertNotNull(server.getMessageStore());
        assertSame(realm, l1.getRealm());
        assertSame(realm, l2.getRealm());
        assertEquals(4096, l1.getMaxPacketSize());
        assertSame(server, l1.getServer());
        assertEquals(2, calls.size());
        server.stop();
        assertEquals(1, provider.stops);
        assertEquals(4, calls.size());
        assertEquals("stop", calls.get(3));
    }

    @Test
    public void listenerRealmIsNotOverriddenAndZeroMaxPacketLeavesListenerDefault() {
        List<String> calls = new ArrayList<String>();
        StubListener l = new StubListener(calls, false, false);
        BasicRealm own = new BasicRealm();
        l.realm(own);
        MqttServer server = MqttServer.compose().listener(l).realm(new BasicRealm())
                .maxPacketSize(0).server();
        server.start(null);
        assertSame(own, l.getRealm());
        assertEquals(1_048_576, l.getMaxPacketSize());
    }

    @Test
    public void listenerStartAndStopFailuresAreContained() {
        List<String> calls = new ArrayList<String>();
        StubListener bad = new StubListener(calls, true, true);
        StubListener good = new StubListener(calls, false, false);
        MqttServer server = MqttServer.compose().listener(bad).listener(good).server();
        server.start(null);
        assertEquals(2, calls.size());
        server.stop();
        assertEquals(4, calls.size());
    }

    @Test
    public void realmAndPacketSizeAreSettableAfterConstruction() {
        MqttServer server = new MqttServer();
        server.addListener(new MqttListener());
        assertEquals(1, server.getListeners().size());
        server.realm(null);
        assertNull(server.getRealm());
        server.maxPacketSize(10);
        assertEquals(10, server.getMaxPacketSize());
    }

    @Test
    public void defaultMessageStoreIsInMemory() {
        MqttServer server = new MqttServer();
        server.start(null);
        MqttMessageStore store = server.getMessageStore();
        assertTrue(store instanceof InMemoryMessageStore);
        server.stop();
    }

    @Test
    public void sessionProviderDefaultsAreNoOps() {
        MqttServerSessionProvider provider = new MqttServerSessionProvider() {
            @Override
            public ConnectHandler openSession(TcpListener listener) {
                return null;
            }
        };
        provider.start();
        provider.stop();
        assertNull(provider.openSession(null));
    }

    @Test
    public void sessionAccessorsAndConnectedState() {
        MqttSession session = new MqttSession("cid", MqttVersion.V5_0, true);
        assertEquals("cid", session.getClientId());
        assertEquals(MqttVersion.V5_0, session.getVersion());
        assertTrue(session.isCleanSession());
        assertNotNull(session.getQoSManager());
        assertFalse(session.isConnected());
        assertNull(session.getEndpoint());
        RecordingStubEndpoint ep = new RecordingStubEndpoint();
        session.setEndpoint(ep);
        assertSame(ep, session.getEndpoint());
        assertTrue(session.isConnected());
        ep.close();
        assertFalse(session.isConnected());
        session.setUsername("bob");
        session.setKeepAlive(45);
        assertEquals("bob", session.getUsername());
        assertEquals(45, session.getKeepAlive());
        assertTrue(session.toString().contains("cid"));
    }

    private static MqttMessageContent content(byte[] data) {
        return new InMemoryMessageStore.InMemoryContent(data);
    }

    @Test
    public void willManagerSetRemoveClearHas() {
        WillManager wills = new WillManager();
        assertFalse(wills.has("c"));
        assertNull(wills.remove("c"));
        wills.clear("c");
        wills.set("c", "t/1", content(new byte[] {1}), QoS.AT_LEAST_ONCE, true);
        assertTrue(wills.has("c"));
        wills.set("c", "t/2", content(new byte[] {2, 2}), QoS.EXACTLY_ONCE, false);
        WillManager.WillMessage msg = wills.remove("c");
        assertEquals("t/2", msg.getTopic());
        assertEquals(2, msg.getContent().size());
        assertEquals(QoS.EXACTLY_ONCE, msg.getQoS());
        assertFalse(msg.isRetain());
        assertFalse(wills.has("c"));
        wills.set("d", "t", content(new byte[0]), QoS.AT_MOST_ONCE, true);
        wills.clear("d");
        assertFalse(wills.has("d"));
    }
}
