/*
 * DoQConnectionPoolTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoQConnectionPool}.
 * RFC 9250 section 5.5.1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQConnectionPoolTest {

    @After
    public void tearDown() {
        DoQConnectionPool.closeAll();
        DoQConnectionPool.setMaxIdleTimeMs(30_000);
    }

    @Test
    public void testDefaultMaxIdleTime() {
        assertEquals(30_000, DoQConnectionPool.getMaxIdleTimeMs());
    }

    @Test
    public void testSetMaxIdleTime() {
        DoQConnectionPool.setMaxIdleTimeMs(60_000);
        assertEquals(60_000, DoQConnectionPool.getMaxIdleTimeMs());
    }

    @Test
    public void testPoolSizeInitiallyZero() {
        assertEquals(0, DoQConnectionPool.poolSize());
    }

    @Test
    public void testCloseAllClearsPool() {
        DoQConnectionPool.closeAll();
        assertEquals(0, DoQConnectionPool.poolSize());
    }

    @Test
    public void testCloseWithoutOpenDoesNotThrow() {
        DoQConnectionPool pool = new DoQConnectionPool();
        pool.close();
    }

    @Test(expected = IllegalStateException.class)
    public void testSendWithoutOpenThrows() {
        DoQConnectionPool pool = new DoQConnectionPool();
        pool.send(java.nio.ByteBuffer.allocate(10));
    }

    @Test(expected = IllegalStateException.class)
    public void testScheduleTimerWithoutOpenThrows() {
        DoQConnectionPool pool = new DoQConnectionPool();
        pool.scheduleTimer(1000, () -> {});
    }

    @Test
    public void testEvictStaleRemovesExpired() {
        DoQConnectionPool.setMaxIdleTimeMs(0);
        DoQConnectionPool.evictStale();
        assertEquals(0, DoQConnectionPool.poolSize());
    }
}
