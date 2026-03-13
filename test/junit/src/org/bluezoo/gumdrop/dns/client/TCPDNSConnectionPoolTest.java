/*
 * TCPDNSConnectionPoolTest.java
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

import org.junit.Test;

import java.net.InetSocketAddress;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TCPDNSConnectionPool}.
 * RFC 7766: TCP connection reuse for DNS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TCPDNSConnectionPoolTest {

    @Test
    public void testDefaultConfiguration() {
        TCPDNSConnectionPool pool = new TCPDNSConnectionPool();
        assertEquals(0, pool.pooledConnectionCount(
                new InetSocketAddress("127.0.0.1", 53)));
    }

    @Test
    public void testCloseEmpty() {
        TCPDNSConnectionPool pool = new TCPDNSConnectionPool();
        pool.close();
    }

    @Test
    public void testSetMaxConnectionsPerServer() {
        TCPDNSConnectionPool pool = new TCPDNSConnectionPool();
        pool.setMaxConnectionsPerServer(5);
        pool.close();
    }

    @Test
    public void testSetIdleTimeout() {
        TCPDNSConnectionPool pool = new TCPDNSConnectionPool();
        pool.setIdleTimeoutMs(60_000);
        pool.close();
    }

    @Test
    public void testSetMaxLifetime() {
        TCPDNSConnectionPool pool = new TCPDNSConnectionPool();
        pool.setMaxLifetimeMs(600_000);
        pool.close();
    }

    @Test
    public void testSetSocketTimeout() {
        TCPDNSConnectionPool pool = new TCPDNSConnectionPool();
        pool.setSocketTimeoutMs(10_000);
        pool.close();
    }

    @Test
    public void testConstants() {
        assertEquals(2,
                TCPDNSConnectionPool.DEFAULT_MAX_CONNECTIONS_PER_SERVER);
        assertEquals(30_000,
                TCPDNSConnectionPool.DEFAULT_IDLE_TIMEOUT_MS);
        assertEquals(300_000,
                TCPDNSConnectionPool.DEFAULT_MAX_LIFETIME_MS);
        assertEquals(5000,
                TCPDNSConnectionPool.DEFAULT_SOCKET_TIMEOUT_MS);
    }
}
