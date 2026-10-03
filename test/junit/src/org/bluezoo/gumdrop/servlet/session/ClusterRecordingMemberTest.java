/*
 * ClusterRecordingMemberTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet.session;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UdpEndpoint;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the sending side of {@link Cluster} through a recording
 * endpoint in place of a multicast socket: replication, passivation,
 * pings, node expiry and shutdown are observed from the datagrams and
 * timers the cluster asks the endpoint for.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterRecordingMemberTest {

    private static final class Timer implements TimerHandle {
        boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class Silent implements ProtocolHandler {
        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void connected(org.bluezoo.gumdrop.Endpoint endpoint) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void securityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
        }
    }

    private static final class Recording extends UdpEndpoint {
        final List<InetSocketAddress> destinations = new ArrayList<InetSocketAddress>();
        final List<Integer> sizes = new ArrayList<Integer>();
        final List<Timer> timers = new ArrayList<Timer>();
        final List<Runnable> callbacks = new ArrayList<Runnable>();
        boolean closed;
        boolean closing;
        InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 4000);

        Recording() {
            super(new Silent());
        }

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress dest) {
            destinations.add(dest);
            sizes.add(Integer.valueOf(data.remaining()));
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            Timer t = new Timer();
            timers.add(t);
            callbacks.add(callback);
            return t;
        }

        @Override
        public boolean isClosing() {
            return closing;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return remote;
        }

        @Override
        public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() {
            return null;
        }
    }

    private MockSessionContext context;
    private SessionManager manager;
    private Cluster cluster;
    private UUID contextUuid;
    private Recording endpoint;
    private Object member;

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(Class<?> type, String name) {
        Method[] methods = type.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name)) {
                methods[i].setAccessible(true);
                return methods[i];
            }
        }
        throw new IllegalStateException(name);
    }

    @Before
    public void setUp() throws Exception {
        context = new MockSessionContext();
        context.setDistributable(true);
        manager = new SessionManager(context);
        cluster = new Cluster(new ClusterContainer() {
            @Override
            public int getClusterPort() {
                return 9998;
            }

            @Override
            public InetAddress getClusterGroupAddress() {
                return Cluster.DEFAULT_GROUP_IPV4;
            }

            @Override
            public byte[] getClusterKey() {
                byte[] key = new byte[32];
                for (int i = 0; i < key.length; i++) {
                    key[i] = (byte) (i + 3);
                }
                return key;
            }

            @Override
            public SessionContext getContextByDigest(byte[] digest) {
                return context;
            }

            @Override
            public Iterable<SessionContext> getDistributableContexts() {
                return Collections.<SessionContext>singletonList(context);
            }
        });
        contextUuid = UUID.randomUUID();
        cluster.registerContext(contextUuid, manager);

        List<?> members = (List<?>) field(Cluster.class, "members").get(cluster);
        member = members.get(0);
        endpoint = new Recording();
        field(member.getClass(), "endpoint").set(member, endpoint);
        @SuppressWarnings("unchecked")
        List<Object> active = (List<Object>) field(Cluster.class, "activeMembers").get(cluster);
        active.add(member);
    }

    @Test
    public void testFullReplicationSendsToLoopbackAndGroup() throws Exception {
        Session s = (Session) manager.createSession();
        s.setAttribute("a", "b");
        cluster.replicate(contextUuid, s);
        assertEquals(2, endpoint.destinations.size());
        assertEquals("127.0.0.1", endpoint.destinations.get(0).getAddress().getHostAddress());
        assertEquals(9998, endpoint.destinations.get(1).getPort());
        assertFalse(s.isDirty());
    }

    @Test
    public void testDeltaReplicationAfterSmallChange() throws Exception {
        Session s = (Session) manager.createSession();
        for (int i = 0; i < 6; i++) {
            s.setAttribute("k" + i, "v");
        }
        cluster.replicate(contextUuid, s);
        endpoint.destinations.clear();
        s.setAttribute("k0", "changed");
        assertFalse(s.needsFullReplication());
        cluster.replicate(contextUuid, s);
        assertEquals(2, endpoint.destinations.size());
    }

    @Test
    public void testPassivateSendsDatagram() throws Exception {
        Session s = (Session) manager.createSession();
        cluster.passivate(contextUuid, s);
        assertEquals(2, endpoint.destinations.size());
    }

    @Test
    public void testLargeSessionIsSentInFragments() throws Exception {
        Session s = (Session) manager.createSession();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 150000; i++) {
            big.append('z');
        }
        s.setAttribute("big", big.toString());
        cluster.replicate(contextUuid, s);
        assertTrue(endpoint.destinations.size() >= 6);
        s.clearDirtyState();
        for (int i = 0; i < 4; i++) {
            s.setAttribute("o" + i, "v");
        }
        s.setAttribute("big", big.toString() + "!");
        s.clearDirtyState();
        s.setAttribute("big", big.toString() + "!!");
        endpoint.destinations.clear();
        assertFalse(s.needsFullReplication());
        cluster.replicate(contextUuid, s);
        assertTrue(endpoint.destinations.size() >= 6);
    }

    @Test
    public void testReplicateAllSendsEverySession() throws Exception {
        manager.createSession();
        manager.createSession();
        Method m = method(Cluster.class, "replicateAll");
        m.invoke(cluster);
        assertFalse(endpoint.destinations.isEmpty());
    }

    @Test
    public void testPingTimerSendsPingExpiresNodesAndReschedules() throws Exception {
        @SuppressWarnings("unchecked")
        Map<UUID, Map<UUID, Long>> nodes =
                (Map<UUID, Map<UUID, Long>>) field(Cluster.class, "nodeContexts").get(cluster);
        UUID stale = UUID.randomUUID();
        Map<UUID, Long> staleContexts = new ConcurrentHashMap<UUID, Long>();
        staleContexts.put(UUID.randomUUID(), Long.valueOf(0L));
        nodes.put(stale, staleContexts);
        UUID fresh = UUID.randomUUID();
        Map<UUID, Long> freshContexts = new ConcurrentHashMap<UUID, Long>();
        freshContexts.put(UUID.randomUUID(), Long.valueOf(System.currentTimeMillis()));
        nodes.put(fresh, freshContexts);

        method(Cluster.class, "schedulePing").invoke(cluster);
        assertEquals(1, endpoint.callbacks.size());
        endpoint.callbacks.get(0).run();

        assertEquals(2, endpoint.destinations.size());
        assertFalse(nodes.containsKey(stale));
        assertTrue(nodes.containsKey(fresh));
        assertEquals(2, endpoint.callbacks.size());
    }

    @Test
    public void testPingTimerWithMetricsRecordsNodeLeft() throws Exception {
        TelemetryConfig config = new TelemetryConfig();
        config.setMetricsEnabled(true);
        cluster.setTelemetryConfig(config);
        method(Cluster.class, "initializeMetrics").invoke(cluster);
        @SuppressWarnings("unchecked")
        Map<UUID, Map<UUID, Long>> nodes =
                (Map<UUID, Map<UUID, Long>>) field(Cluster.class, "nodeContexts").get(cluster);
        Map<UUID, Long> staleContexts = new ConcurrentHashMap<UUID, Long>();
        staleContexts.put(UUID.randomUUID(), Long.valueOf(0L));
        nodes.put(UUID.randomUUID(), staleContexts);
        Session s = (Session) manager.createSession();
        cluster.replicate(contextUuid, s);
        s.setAttribute("x", "y");
        cluster.replicate(contextUuid, s);
        method(Cluster.class, "onPingTimer").invoke(cluster);
        assertTrue(nodes.isEmpty());
    }

    @Test
    public void testCloseCancelsTimerAndClosesEndpoints() throws Exception {
        method(Cluster.class, "schedulePing").invoke(cluster);
        cluster.close();
        assertTrue(endpoint.timers.get(0).isCancelled());
        assertTrue(endpoint.closed);
        assertNull(field(member.getClass(), "endpoint").get(member));
        assertTrue(cluster.getJoinedGroupAddresses().isEmpty());
    }

    @Test
    public void testCloseSkipsEndpointAlreadyClosing() throws Exception {
        endpoint.closing = true;
        cluster.close();
        assertFalse(endpoint.closed);
    }

    @Test
    public void testSchedulePingWithoutActiveMembersDoesNothing() throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> active = (List<Object>) field(Cluster.class, "activeMembers").get(cluster);
        active.clear();
        method(Cluster.class, "schedulePing").invoke(cluster);
        assertTrue(endpoint.timers.isEmpty());
    }

    @Test
    public void testProtocolHandlerForwardsDatagramsAndErrors() throws Exception {
        Class<?> handlerClass = Class.forName(
                "org.bluezoo.gumdrop.servlet.session.Cluster$ClusterProtocolHandler");
        Constructor<?> ctor = handlerClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        ProtocolHandler handler = (ProtocolHandler) ctor.newInstance(cluster, member);
        handler.connected(endpoint);
        handler.receive(ByteBuffer.wrap(new byte[64]));
        handler.securityEstablished(null);
        handler.error(new java.io.IOException("boom"));
        handler.disconnected();
        assertTrue(endpoint.destinations.isEmpty());
    }

    @Test
    public void testEventTypeNames() throws Exception {
        Method m = method(Cluster.class, "getEventTypeName");
        assertEquals("replicate", m.invoke(null, Byte.valueOf((byte) 0)));
        assertEquals("passivate", m.invoke(null, Byte.valueOf((byte) 1)));
        assertEquals("ping", m.invoke(null, Byte.valueOf((byte) 2)));
        assertEquals("delta", m.invoke(null, Byte.valueOf((byte) 3)));
        assertEquals("fragment", m.invoke(null, Byte.valueOf((byte) 4)));
        assertEquals("unknown", m.invoke(null, Byte.valueOf((byte) 9)));
    }

    @Test
    public void testSessionManagerUsesTheClusterWhenSet() throws Exception {
        assertFalse(manager.isClusteringEnabled());
        manager.setCluster(cluster);
        assertTrue(manager.isClusteringEnabled());
        UUID before = manager.getContextUuid();
        UUID after = manager.regenerateContextUuid();
        assertFalse(before.equals(after));
        assertEquals(after, manager.getContextUuid());
        Session s = (Session) manager.createSession();
        s.setAttribute("a", "b");
        manager.replicateSession(s);
        assertEquals(2, endpoint.destinations.size());
        manager.removeSession(s.getId());
        assertEquals(4, endpoint.destinations.size());
        manager.setCluster(null);
        assertFalse(manager.isClusteringEnabled());
    }

    @Test
    public void testUnregisterContextForgetsTheManager() throws Exception {
        assertTrue(cluster.getSessionManager(contextUuid) == manager);
        cluster.unregisterContext(contextUuid);
        assertNull(cluster.getSessionManager(contextUuid));
        cluster.unregisterContext(contextUuid);
        assertNull(cluster.getSessionManager(contextUuid));
    }

    @Test
    public void testSessionManagerLookupByDigest() throws Exception {
        Object found = method(Cluster.class, "getSessionManagerByDigest")
                .invoke(cluster, new Object[] {new byte[16]});
        assertTrue(found == manager);
        cluster.unregisterContext(contextUuid);
        Object gone = method(Cluster.class, "getSessionManagerByDigest")
                .invoke(cluster, new Object[] {new byte[16]});
        assertNull(gone);
    }

    @Test
    public void testReplicationAllowedClassesAreConfiguredOnTheSerializer() throws Exception {
        java.util.Set<String> extra = new java.util.HashSet<String>();
        extra.add(StringBuilder.class.getName());
        cluster.setReplicationAllowedClasses(extra);
        try {
            assertTrue(SessionSerializer.isAllowedDeserializationClass(StringBuilder.class));
            cluster.setReplicationAllowedClasses(null);
            assertFalse(SessionSerializer.isAllowedDeserializationClass(StringBuilder.class));
        } finally {
            SessionSerializer.configureAllowedClasses(null);
        }
    }
}
