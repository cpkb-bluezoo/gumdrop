/*
 * ClientEndpointPoolTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ClientEndpointPool}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClientEndpointPoolTest {

    /** Tracks open state for proxy endpoints. */
    private static final class State implements InvocationHandler {
        boolean open = true;
        int closes;
        /** When set, tasks given to execute() are held instead of run, like a loop that has not got to them yet. */
        boolean holdTasks;
        final java.util.List<Runnable> held = new java.util.ArrayList<Runnable>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String n = method.getName();
            if ("isOpen".equals(n)) {
                return Boolean.valueOf(open);
            }
            if ("close".equals(n)) {
                open = false;
                closes++;
                return null;
            }
            if ("execute".equals(n)) {
                if (holdTasks) {
                    held.add((Runnable) args[0]);
                } else {
                    ((Runnable) args[0]).run();
                }
                return null;
            }
            if ("hashCode".equals(n)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(n)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("toString".equals(n)) {
                return "stub";
            }
            return null;
        }
    }

    private ClientEndpointPool pool;
    private ClientEndpointPool.PoolTarget target;

    private static Endpoint endpoint(State state) {
        return (Endpoint) Proxy.newProxyInstance(
                ClientEndpointPoolTest.class.getClassLoader(),
                new Class<?>[] {Endpoint.class}, state);
    }

    @Before
    public void setUp() throws Exception {
        pool = new ClientEndpointPool();
        target = new ClientEndpointPool.PoolTarget(InetAddress.getByName("127.0.0.1"), 4000, false);
    }

    @After
    public void tearDown() {
        pool.shutdown();
    }

    @Test
    public void configurationValidation() {
        assertEquals(ClientEndpointPool.DEFAULT_MAX_ENDPOINTS_PER_TARGET, pool.getMaxEndpointsPerTarget());
        assertEquals(ClientEndpointPool.DEFAULT_IDLE_TIMEOUT_MS, pool.getIdleTimeoutMs());
        pool.setMaxEndpointsPerTarget(2);
        assertEquals(2, pool.getMaxEndpointsPerTarget());
        pool.setIdleTimeoutMs(ClientEndpointPool.MIN_IDLE_TIMEOUT_MS);
        assertEquals(ClientEndpointPool.MIN_IDLE_TIMEOUT_MS, pool.getIdleTimeoutMs());
        try {
            pool.setMaxEndpointsPerTarget(0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            pool.setIdleTimeoutMs(1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void registerReleaseAndReacquire() {
        State s = new State();
        Endpoint ep = endpoint(s);
        assertNull(pool.tryAcquire(target));
        ClientEndpointPool.PoolEntry entry = pool.register(target, ep);
        assertTrue(entry.isBusy());
        assertSame(ep, entry.getEndpoint());
        assertSame(target, entry.getTarget());
        assertEquals(1, pool.getTotalEndpointCount());
        assertEquals(0, pool.getIdleEndpointCount());
        assertNull(pool.tryAcquire(target));

        pool.release(entry);
        assertFalse(entry.isBusy());

        ClientEndpointPool.PoolEntry again = pool.tryAcquire(target);
        assertSame(entry, again);
        assertTrue(again.isBusy());
        pool.release(null);
        pool.remove(null);
    }

    @Test
    public void idleCountTracksReleaseAndAcquire() {
        ClientEndpointPool.PoolEntry entry = pool.register(target, endpoint(new State()));
        assertEquals(0, pool.getIdleEndpointCount());
        pool.release(entry);
        assertEquals(1, pool.getIdleEndpointCount());
        ClientEndpointPool.PoolEntry again = pool.tryAcquire(target);
        assertSame(entry, again);
        assertEquals(0, pool.getIdleEndpointCount());
        pool.release(again);
        assertEquals(1, pool.getIdleEndpointCount());
        pool.remove(again);
        assertEquals(0, pool.getIdleEndpointCount());
    }

    @Test
    public void closedEndpointsAreDiscarded() {
        State s = new State();
        ClientEndpointPool.PoolEntry entry = pool.register(target, endpoint(s));
        pool.release(entry);
        s.open = false;
        assertNull(pool.tryAcquire(target));
        assertEquals(0, pool.getTotalEndpointCount());

        State s2 = new State();
        ClientEndpointPool.PoolEntry entry2 = pool.register(target, endpoint(s2));
        s2.open = false;
        pool.release(entry2);
        assertEquals(0, pool.getTotalEndpointCount());
    }

    @Test
    public void capIsEnforced() {
        pool.setMaxEndpointsPerTarget(2);
        assertTrue(pool.canCreateEndpoint(target));
        pool.register(target, endpoint(new State()));
        assertTrue(pool.canCreateEndpoint(target));
        pool.register(target, endpoint(new State()));
        assertFalse(pool.canCreateEndpoint(target));
    }

    @Test
    public void releaseOverCapClosesEndpoint() {
        pool.setMaxEndpointsPerTarget(1);
        State a = new State();
        State b = new State();
        ClientEndpointPool.PoolEntry ea = pool.register(target, endpoint(a));
        pool.register(target, endpoint(b));
        pool.release(ea);
        assertEquals(1, a.closes);
        assertFalse(a.open);
        assertEquals(1, pool.getTotalEndpointCount());
    }

    @Test
    public void removeClosesOpenEndpoint() {
        State s = new State();
        ClientEndpointPool.PoolEntry entry = pool.register(target, endpoint(s));
        pool.remove(entry);
        assertFalse(s.open);
        assertEquals(0, pool.getTotalEndpointCount());
        pool.remove(entry);
        assertEquals(1, s.closes);
    }

    @Test
    public void releaseOfUnknownTargetClosesEndpoint() throws Exception {
        State s = new State();
        ClientEndpointPool.PoolTarget other =
                new ClientEndpointPool.PoolTarget(InetAddress.getByName("127.0.0.2"), 1, true);
        ClientEndpointPool.PoolEntry entry = pool.register(other, endpoint(s));
        pool.remove(entry);
        pool.release(entry);
        assertFalse(s.open);
    }

    @Test
    public void shutdownClosesEverything() {
        State a = new State();
        State b = new State();
        pool.register(target, endpoint(a));
        ClientEndpointPool.PoolEntry eb = pool.register(target, endpoint(b));
        pool.release(eb);
        pool.shutdown();
        assertFalse(a.open);
        assertFalse(b.open);
        assertEquals(0, pool.getTotalEndpointCount());
    }

    @Test
    public void poolTargetIdentity() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        InlineSelectorLoop loop = new InlineSelectorLoop();
        ClientEndpointPool.PoolTarget a = new ClientEndpointPool.PoolTarget(lo, 80, false);
        ClientEndpointPool.PoolTarget b = new ClientEndpointPool.PoolTarget(lo, 80, false);
        ClientEndpointPool.PoolTarget c = new ClientEndpointPool.PoolTarget(lo, 80, true);
        ClientEndpointPool.PoolTarget d = new ClientEndpointPool.PoolTarget(lo, 81, false);
        ClientEndpointPool.PoolTarget e = new ClientEndpointPool.PoolTarget(lo, 80, false, loop);
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertNotEquals(a, e);
        assertNotEquals(a, null);
        assertNotEquals(a, "x");
        assertEquals(lo, a.getHost());
        assertEquals(80, a.getPort());
        assertFalse(a.isSecure());
        assertTrue(c.isSecure());
        assertNull(a.getSelectorLoop());
        assertSame(loop, e.getSelectorLoop());
        assertEquals("tcp://127.0.0.1:80", a.toString());
        assertEquals("tls://127.0.0.1:80", c.toString());
        assertTrue(e.toString().startsWith("tcp://127.0.0.1:80@loop-"));
        assertNotNull(a.toString());
    }

    @Test
    public void closesAreHandedToTheEndpointsLoopNotDoneByTheCaller() {
        pool.setMaxEndpointsPerTarget(1);
        State a = new State();
        a.holdTasks = true;
        State b = new State();
        ClientEndpointPool.PoolEntry ea = pool.register(target, endpoint(a));
        pool.register(target, endpoint(b));

        pool.release(ea);

        assertEquals("the caller must not close an endpoint it does not own", 0, a.closes);
        assertEquals(1, a.held.size());
        a.held.get(0).run();
        assertEquals(1, a.closes);
    }

    @Test
    public void shutdownHandsEveryCloseToItsLoop() {
        State a = new State();
        a.holdTasks = true;
        pool.register(target, endpoint(a));

        pool.shutdown();

        assertEquals(0, a.closes);
        assertEquals(1, a.held.size());
        a.held.get(0).run();
        assertEquals(1, a.closes);
    }
}
