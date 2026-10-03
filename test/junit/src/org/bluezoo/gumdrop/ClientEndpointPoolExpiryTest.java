/*
 * ClientEndpointPoolExpiryTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Idle expiry, cleanup and teardown paths of {@link ClientEndpointPool}:
 * endpoints made to look long idle by backdating their last-use stamp, the
 * cleanup pass run by hand instead of by its timer, and entries whose
 * endpoint has already closed or never existed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClientEndpointPoolExpiryTest {

    /** Hand-written mock endpoint state: open flag and close count. */
    private static final class State implements InvocationHandler {
        boolean open = true;
        int closes;

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
                ((Runnable) args[0]).run();
                return null;
            }
            if ("hashCode".equals(n)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(n)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("toString".equals(n)) {
                return "mock";
            }
            return null;
        }
    }

    private ClientEndpointPool pool;
    private ClientEndpointPool.PoolTarget target;

    private static Endpoint endpoint(State state) {
        return (Endpoint) Proxy.newProxyInstance(
                ClientEndpointPoolExpiryTest.class.getClassLoader(),
                new Class<?>[] {Endpoint.class}, state);
    }

    @Before
    public void setUp() throws Exception {
        pool = new ClientEndpointPool();
        target = new ClientEndpointPool.PoolTarget(InetAddress.getByName("127.0.0.1"), 4100, false);
    }

    @After
    public void tearDown() {
        pool.shutdown();
    }

    @Test
    public void acquireDiscardsAnEndpointIdleBeyondTheTimeout() {
        State s = new State();
        ClientEndpointPool.PoolEntry entry = pool.register(target, endpoint(s));
        pool.release(entry);
        entry.lastUsed = 0L;
        assertNull(pool.tryAcquire(target));
        assertEquals(1, s.closes);
        assertEquals(0, pool.getTotalEndpointCount());
    }

    @Test
    public void acquireSkipsExpiredEntryAndReturnsAFreshOne() {
        State stale = new State();
        State fresh = new State();
        ClientEndpointPool.PoolEntry old = pool.register(target, endpoint(stale));
        ClientEndpointPool.PoolEntry young = pool.register(target, endpoint(fresh));
        pool.release(old);
        pool.release(young);
        old.lastUsed = 0L;
        ClientEndpointPool.PoolEntry got = pool.tryAcquire(target);
        assertSame(young, got);
        assertEquals(1, stale.closes);
        assertEquals(0, fresh.closes);
    }

    @Test
    public void acquireDropsEntriesWhoseEndpointClosedWhileIdle() {
        State s = new State();
        ClientEndpointPool.PoolEntry entry = pool.register(target, endpoint(s));
        pool.release(entry);
        s.open = false;
        assertNull(pool.tryAcquire(target));
        assertEquals(0, pool.getTotalEndpointCount());
        assertEquals(0, s.closes);
    }

    @Test
    public void cleanupClosesOnlyExpiredIdleEndpoints() {
        State stale = new State();
        State fresh = new State();
        State busy = new State();
        State dead = new State();
        ClientEndpointPool.PoolEntry a = pool.register(target, endpoint(stale));
        ClientEndpointPool.PoolEntry b = pool.register(target, endpoint(fresh));
        pool.register(target, endpoint(busy));
        ClientEndpointPool.PoolEntry d = pool.register(target, endpoint(dead));
        pool.setMaxEndpointsPerTarget(10);
        pool.release(a);
        pool.release(b);
        pool.release(d);
        a.lastUsed = 0L;
        d.lastUsed = 0L;
        dead.open = false;
        pool.cleanupIdleEndpoints();
        assertEquals(1, stale.closes);
        assertEquals(0, fresh.closes);
        assertEquals(0, busy.closes);
        assertEquals(0, dead.closes);
        assertEquals(2, pool.getTotalEndpointCount());
        assertEquals(1, pool.getIdleEndpointCount());
    }

    @Test
    public void cleanupWithNothingExpiredLeavesThePoolAlone() {
        State s = new State();
        ClientEndpointPool.PoolEntry entry = pool.register(target, endpoint(s));
        pool.release(entry);
        pool.cleanupIdleEndpoints();
        assertEquals(0, s.closes);
        assertEquals(1, pool.getIdleEndpointCount());
        State none = new State();
        pool.shutdown();
        pool.cleanupIdleEndpoints();
        assertEquals(0, none.closes);
        assertEquals(0, pool.getTotalEndpointCount());
    }

    @Test
    public void releaseBeyondTheLimitRemovesTheEntry() {
        pool.setMaxEndpointsPerTarget(1);
        State first = new State();
        State second = new State();
        ClientEndpointPool.PoolEntry a = pool.register(target, endpoint(first));
        ClientEndpointPool.PoolEntry b = pool.register(target, endpoint(second));
        pool.release(b);
        assertEquals(1, second.closes);
        assertEquals(1, pool.getTotalEndpointCount());
        assertTrue(a.isBusy());
    }

    @Test
    public void releaseOfAnEntryWhoseTargetWasClearedRemovesIt() {
        State s = new State();
        ClientEndpointPool.PoolEntry entry = pool.register(target, endpoint(s));
        pool.shutdown();
        assertEquals(1, s.closes);
        s.open = true;
        pool.release(entry);
        assertEquals(2, s.closes);
        s.open = true;
        pool.remove(entry);
        assertEquals(3, s.closes);
    }

    @Test
    public void entriesWithoutAnEndpointAreRemovedQuietly() {
        ClientEndpointPool.PoolEntry entry = pool.register(target, null);
        assertEquals(1, pool.getTotalEndpointCount());
        pool.release(entry);
        assertEquals(0, pool.getTotalEndpointCount());
        ClientEndpointPool.PoolEntry another = pool.register(target, null);
        pool.remove(another);
        pool.shutdown();
        assertFalse(another.isBusy() && pool.getTotalEndpointCount() > 0);
        pool.release(null);
        pool.remove(null);
    }
}
