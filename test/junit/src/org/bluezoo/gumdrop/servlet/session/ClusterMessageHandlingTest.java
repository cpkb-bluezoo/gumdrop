/*
 * ClusterMessageHandlingTest.java
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

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives {@link Cluster} message handling without a network: encrypted
 * datagrams are built here and fed to the receive path, and the sequence
 * window and fragment reassembly helpers are exercised directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterMessageHandlingTest {

    private static final byte EVENT_REPLICATE = 0;
    private static final byte EVENT_PASSIVATE = 1;
    private static final byte EVENT_PING = 2;
    private static final byte EVENT_DELTA = 3;
    private static final byte EVENT_FRAGMENT = 4;

    private MockSessionContext context;
    private SessionManager manager;
    private Cluster cluster;
    private UUID localContextUuid;
    private UUID remoteNode;
    private long sequence;

    private static final class Container implements ClusterContainer {
        final MockSessionContext context;

        Container(MockSessionContext context) {
            this.context = context;
        }

        @Override public int getClusterPort() {
            return 9999;
        }

        @Override public InetAddress getClusterGroupAddress() {
            return null;
        }

        @Override public byte[] getClusterKey() {
            byte[] key = new byte[32];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) i;
            }
            return key;
        }

        @Override public SessionContext getContextByDigest(byte[] digest) {
            byte[] mine = context.getContextDigest();
            for (int i = 0; i < mine.length; i++) {
                if (mine[i] != digest[i]) {
                    return null;
                }
            }
            return context;
        }

        @Override public Iterable<SessionContext> getDistributableContexts() {
            return Collections.<SessionContext>singletonList(context);
        }
    }

    @Before
    public void setUp() throws Exception {
        context = new MockSessionContext();
        context.setDistributable(true);
        manager = new SessionManager(context);
        cluster = new Cluster(new Container(context));
        localContextUuid = UUID.randomUUID();
        cluster.registerContext(localContextUuid, manager);
        remoteNode = UUID.randomUUID();
        sequence = 0L;
    }

    // ===== reflection helpers =====

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

    private Object getField(String name) throws Exception {
        Field f = Cluster.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(cluster);
    }

    private ByteBuffer encrypt(ByteBuffer clear) throws Exception {
        Method m = method(Cluster.class, "encrypt");
        return (ByteBuffer) m.invoke(cluster, clear);
    }

    private void receive(ByteBuffer encrypted) throws Exception {
        Method m = method(Cluster.class, "handleReceive");
        m.invoke(cluster, encrypted, new InetSocketAddress("127.0.0.1", 1), null);
    }

    private ByteBuffer clear(UUID node, UUID ctx, long seq, long timestamp, byte type, ByteBuffer payload) {
        ByteBuffer buf = ByteBuffer.allocate(65000);
        buf.putLong(node.getMostSignificantBits());
        buf.putLong(node.getLeastSignificantBits());
        buf.putLong(ctx.getMostSignificantBits());
        buf.putLong(ctx.getLeastSignificantBits());
        buf.putLong(seq);
        buf.putLong(timestamp);
        buf.put(type);
        if (payload != null) {
            buf.put(payload);
        }
        buf.flip();
        return buf;
    }

    private void send(byte type, ByteBuffer payload) throws Exception {
        sequence++;
        receive(encrypt(clear(remoteNode, UUID.randomUUID(), sequence, System.currentTimeMillis(), type, payload)));
    }

    private ByteBuffer withDigest(int count, ByteBuffer body) {
        ByteBuffer buf = ByteBuffer.allocate(60000);
        buf.put(context.getContextDigest());
        buf.putInt(count);
        buf.put(body);
        buf.flip();
        return buf;
    }

    private Session remoteSession(String id, String attribute) throws Exception {
        SessionManager other = new SessionManager(new MockSessionContext());
        Session s = new Session(context, id);
        s.setAttribute("name", attribute);
        assertNotNull(other);
        return s;
    }

    private Map<?, ?> nodeContexts() throws Exception {
        return (Map<?, ?>) getField("nodeContexts");
    }

    // ===== lifecycle and accessors =====

    @Test
    public void testAccessors() throws Exception {
        assertEquals(9999, cluster.getPort());
        assertNotNull(cluster.getDescription());
        assertEquals(2, cluster.getGroupAddresses().size());
        assertTrue(cluster.getJoinedGroupAddresses().isEmpty());
        assertEquals(manager, cluster.getSessionManager(localContextUuid));
        cluster.unregisterContext(localContextUuid);
        assertNull(cluster.getSessionManager(localContextUuid));
        cluster.close();
    }

    @Test
    public void testRejectsBadKey() {
        ClusterContainer bad = new ClusterContainer() {
            @Override public int getClusterPort() { return 1; }
            @Override public InetAddress getClusterGroupAddress() { return null; }
            @Override public byte[] getClusterKey() { return new byte[5]; }
            @Override public SessionContext getContextByDigest(byte[] digest) { return null; }
            @Override public Iterable<SessionContext> getDistributableContexts() {
                return Collections.<SessionContext>emptyList();
            }
        };
        try {
            new Cluster(bad);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        } catch (java.io.IOException e) {
            fail(e.toString());
        }
    }

    // ===== receive path =====

    @Test
    public void testPingFromNewNodeIsTracked() throws Exception {
        send(EVENT_PING, null);
        assertTrue(nodeContexts().containsKey(remoteNode));
        send(EVENT_PING, null);
        assertEquals(1, nodeContexts().size());
    }

    @Test
    public void testReplicateEventAddsSession() throws Exception {
        Session s = remoteSession("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", "alice");
        send(EVENT_REPLICATE, withDigest(1, s.serialize()));
        Object received = manager.getSession("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1");
        assertNotNull(received);
        assertEquals("alice", ((Session) received).getAttribute("name"));
    }

    @Test
    public void testPassivateEventRemovesSession() throws Exception {
        Session s = remoteSession("b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2", "bob");
        send(EVENT_REPLICATE, withDigest(1, s.serialize()));
        assertNotNull(manager.getSession("b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"));
        ByteBuffer ids = ByteBuffer.allocate(512);
        s.serializeId(ids);
        ids.flip();
        send(EVENT_PASSIVATE, withDigest(1, ids));
        assertNotNull(manager);
    }

    @Test
    public void testDeltaEventUpdatesExistingSession() throws Exception {
        Session s = remoteSession("c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3", "v1");
        send(EVENT_REPLICATE, withDigest(1, s.serialize()));
        s.clearDirtyState();
        s.setAttribute("name", "v2");
        s.setAttribute("extra", "x");
        ByteBuffer delta = s.serializeDelta();
        ByteBuffer body = ByteBuffer.allocate(60000);
        body.put(context.getContextDigest());
        body.put(delta);
        body.flip();
        send(EVENT_DELTA, body);
        // delta for an unknown session is ignored quietly
        Session unknown = remoteSession("d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4", "z");
        unknown.clearDirtyState();
        unknown.setAttribute("a", "b");
        ByteBuffer d2 = unknown.serializeDelta();
        ByteBuffer body2 = ByteBuffer.allocate(60000);
        body2.put(context.getContextDigest());
        body2.put(d2);
        body2.flip();
        send(EVENT_DELTA, body2);
        assertNotNull(manager.getSession("c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"));
    }

    @Test
    public void testFragmentedReplicationIsReassembled() throws Exception {
        Session s = remoteSession("e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5", "carol");
        ByteBuffer whole = s.serialize();
        int total = whole.remaining();
        int half = total / 2;
        byte[] first = new byte[half];
        byte[] second = new byte[total - half];
        whole.get(first);
        whole.get(second);
        long setId = 4242L;
        send(EVENT_FRAGMENT, fragment(setId, 0, 2, EVENT_REPLICATE, first));
        assertEquals(1, ((Map<?, ?>) getField("pendingFragments")).size());
        send(EVENT_FRAGMENT, fragment(setId, 1, 2, EVENT_REPLICATE, second));
        assertEquals(0, ((Map<?, ?>) getField("pendingFragments")).size());
        assertNotNull(manager.getSession("e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5"));
    }

    @Test
    public void testFragmentedDeltaIsReassembled() throws Exception {
        Session s = remoteSession("f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6f6", "d1");
        send(EVENT_REPLICATE, withDigest(1, s.serialize()));
        s.clearDirtyState();
        s.setAttribute("name", "d2");
        ByteBuffer delta = s.serializeDelta();
        byte[] bytes = new byte[delta.remaining()];
        delta.get(bytes);
        send(EVENT_FRAGMENT, fragment(77L, 0, 1, EVENT_DELTA, bytes));
        assertEquals(0, ((Map<?, ?>) getField("pendingFragments")).size());
    }

    @Test
    public void testInvalidFragmentsAreIgnored() throws Exception {
        send(EVENT_FRAGMENT, fragment(1L, 5, 2, EVENT_REPLICATE, new byte[] {1}));
        send(EVENT_FRAGMENT, fragment(2L, 0, 3, EVENT_REPLICATE, new byte[] {1}));
        send(EVENT_FRAGMENT, fragment(2L, 1, 4, EVENT_REPLICATE, new byte[] {1}));
        assertEquals(2, ((Map<?, ?>) getField("pendingFragments")).size());
    }

    @Test
    public void testTooManyPendingFragmentSetsAreRefused() throws Exception {
        for (int i = 0; i < 100; i++) {
            send(EVENT_FRAGMENT, fragment(1000L + i, 0, 2, EVENT_REPLICATE, new byte[] {1}));
        }
        assertEquals(100, ((Map<?, ?>) getField("pendingFragments")).size());
        send(EVENT_FRAGMENT, fragment(5000L, 0, 2, EVENT_REPLICATE, new byte[] {1}));
        assertEquals(100, ((Map<?, ?>) getField("pendingFragments")).size());
    }

    @Test
    public void testUnknownDigestAndNonDistributableContextAreIgnored() throws Exception {
        ByteBuffer body = ByteBuffer.allocate(64);
        body.put(new byte[16]);
        body.putInt(0);
        body.flip();
        context.setContextDigest(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        send(EVENT_REPLICATE, body);
        ByteBuffer d = ByteBuffer.allocate(64);
        d.put(new byte[16]);
        d.flip();
        send(EVENT_DELTA, d);
        context.setContextDigest(new byte[16]);
        context.setDistributable(false);
        ByteBuffer again = ByteBuffer.allocate(64);
        again.put(new byte[16]);
        again.putInt(0);
        again.flip();
        send(EVENT_REPLICATE, again);
        ByteBuffer again2 = ByteBuffer.allocate(64);
        again2.put(new byte[16]);
        again2.flip();
        send(EVENT_DELTA, again2);
        assertNotNull(manager);
    }

    @Test
    public void testRejectedMessages() throws Exception {
        // stale timestamp
        receive(encrypt(clear(remoteNode, UUID.randomUUID(), 1L,
                System.currentTimeMillis() - 120000L, EVENT_PING, null)));
        assertFalse(nodeContexts().containsKey(remoteNode));
        // replay of an identical sequence number
        UUID ctx = UUID.randomUUID();
        long now = System.currentTimeMillis();
        receive(encrypt(clear(remoteNode, ctx, 10L, now, EVENT_PING, null)));
        receive(encrypt(clear(remoteNode, ctx, 10L, now, EVENT_PING, null)));
        // garbage that fails authentication
        ByteBuffer garbage = ByteBuffer.allocate(64);
        for (int i = 0; i < 64; i++) {
            garbage.put((byte) i);
        }
        garbage.flip();
        receive(garbage);
        // a message from this node itself is processed for sequence only
        UUID self = (UUID) getField("nodeUuid");
        receive(encrypt(clear(self, UUID.randomUUID(), 1L, System.currentTimeMillis(), EVENT_PING, null)));
        assertFalse(nodeContexts().containsKey(self));
    }

    @Test
    public void testNewContextOnKnownNodeTriggersReplication() throws Exception {
        long now = System.currentTimeMillis();
        receive(encrypt(clear(remoteNode, new UUID(0, 0), 1L, now, EVENT_PING, null)));
        receive(encrypt(clear(remoteNode, UUID.randomUUID(), 2L, now, EVENT_PING, null)));
        assertEquals(1, nodeContexts().size());
    }

    @Test
    public void testReceivingWithMetricsEnabled() throws Exception {
        TelemetryConfig config = new TelemetryConfig();
        config.setMetricsEnabled(true);
        cluster.setTelemetryConfig(config);
        method(Cluster.class, "initializeMetrics").invoke(cluster);
        assertNotNull(getField("metrics"));
        Session s = remoteSession("0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a", "m");
        send(EVENT_REPLICATE, withDigest(1, s.serialize()));
        send(EVENT_PING, null);
        send(EVENT_FRAGMENT, fragment(31L, 0, 1, EVENT_REPLICATE, new byte[] {1}));
        long now = System.currentTimeMillis();
        receive(encrypt(clear(remoteNode, UUID.randomUUID(), 900L, now - 120000L, EVENT_PING, null)));
        receive(encrypt(clear(remoteNode, UUID.randomUUID(), 901L, now, EVENT_PING, null)));
        receive(encrypt(clear(remoteNode, UUID.randomUUID(), 901L, now, EVENT_PING, null)));
        ByteBuffer garbage = ByteBuffer.allocate(40);
        garbage.put(new byte[40]);
        garbage.flip();
        receive(garbage);
        ClusterMetrics metrics = (ClusterMetrics) getField("metrics");
        metrics.recordNodeJoined();
        metrics.recordNodeLeft();
        metrics.recordSessionReplicated(null);
        metrics.recordSessionReplicated("ctx");
        metrics.recordSessionReceived(null);
        metrics.recordSessionPassivated(null);
        metrics.recordMessageSent(10, "ping");
        metrics.recordMessageReceived(10, "ping");
        metrics.recordDeltaSent();
        metrics.recordDeltaReceived();
        metrics.recordFragmentSent();
        metrics.recordFragmentReceived();
        metrics.recordFragmentReassembled();
        metrics.recordFragmentTimedOut();
        metrics.recordDecryptError();
        metrics.recordReplayError();
        metrics.recordTimestampError();
        metrics.recordReplicationDuration(3.5, "full");
    }

    // ===== sending =====

    @Test
    public void testReplicateAndPassivateWithoutMembers() throws Exception {
        Session s = (Session) manager.createSession();
        s.setAttribute("k", "v");
        cluster.replicate(localContextUuid, s);
        assertFalse(s.isDirty());
        // not dirty any more: nothing to do
        cluster.replicate(localContextUuid, s);
        s.setAttribute("k", "w");
        cluster.replicate(localContextUuid, s);
        cluster.passivate(localContextUuid, s);
    }

    @Test
    public void testReplicateLargeSessionIsFragmented() throws Exception {
        Session s = (Session) manager.createSession();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 70000; i++) {
            big.append('x');
        }
        s.setAttribute("big", big.toString());
        cluster.replicate(localContextUuid, s);
        assertFalse(s.isDirty());
        s.setAttribute("big", big.toString() + "y");
        cluster.replicate(localContextUuid, s);
    }

    @Test
    public void testReplicateAllWithManySessions() throws Exception {
        for (int i = 0; i < 40; i++) {
            Session s = (Session) manager.createSession();
            StringBuilder payload = new StringBuilder();
            for (int j = 0; j < 4000; j++) {
                payload.append('p');
            }
            s.setAttribute("payload", payload.toString());
        }
        send(EVENT_PING, null);
        assertTrue(nodeContexts().containsKey(remoteNode));
    }

    // ===== sequence window =====

    private Object newSequenceState() throws Exception {
        Class<?> type = Class.forName("org.bluezoo.gumdrop.servlet.session.Cluster$NodeSequenceState");
        Constructor<?> c = type.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private boolean record(Object state, long seq) throws Exception {
        Method m = method(state.getClass(), "validateAndRecord");
        try {
            return ((Boolean) m.invoke(state, Long.valueOf(seq))).booleanValue();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @Test
    public void testSequenceWindow() throws Exception {
        Object state = newSequenceState();
        assertTrue(record(state, 5));
        assertFalse(record(state, 5));
        assertTrue(record(state, 3));
        assertFalse(record(state, 3));
        assertTrue(record(state, 6));
        assertTrue(record(state, 70));
        assertFalse(record(state, 5));
        assertTrue(record(state, 69));
        assertTrue(record(state, 200));
        assertTrue(record(state, 130));
        assertTrue(record(state, 2000));
        assertFalse(record(state, 100));
        assertTrue(record(state, 2001));
        assertTrue(record(state, 2005));
        assertTrue(record(state, 2100));
        assertFalse(record(state, 2100));
        assertTrue(record(state, 5000));
    }

    @Test
    public void testFragmentSet() throws Exception {
        Class<?> type = Class.forName("org.bluezoo.gumdrop.servlet.session.Cluster$FragmentSet");
        Constructor<?> c = type.getDeclaredConstructor(int.class, byte.class, byte[].class);
        c.setAccessible(true);
        Object set = c.newInstance(Integer.valueOf(2), Byte.valueOf(EVENT_REPLICATE), new byte[16]);
        Method add = method(type, "addFragment");
        Method complete = method(type, "isComplete");
        Method reassemble = method(type, "reassemble");
        assertFalse(((Boolean) complete.invoke(set)).booleanValue());
        add.invoke(set, Integer.valueOf(1), new byte[] {3, 4});
        add.invoke(set, Integer.valueOf(1), new byte[] {9, 9});
        assertFalse(((Boolean) complete.invoke(set)).booleanValue());
        add.invoke(set, Integer.valueOf(0), new byte[] {1, 2});
        assertTrue(((Boolean) complete.invoke(set)).booleanValue());
        ByteBuffer all = (ByteBuffer) reassemble.invoke(set);
        assertEquals(4, all.remaining());
        assertEquals(1, all.get());
        assertEquals(2, all.get());
        assertEquals(3, all.get());
        assertEquals(4, all.get());
    }

    private ByteBuffer fragment(long setId, int index, int total, byte originalType, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(60000);
        buf.putLong(setId);
        buf.putShort((short) index);
        buf.putShort((short) total);
        buf.put(originalType);
        buf.put(context.getContextDigest());
        buf.put(data);
        buf.flip();
        return buf;
    }
}
