/*
 * RedisClientConfigTest.java
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

package org.bluezoo.gumdrop.redis.client;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.*;

/**
 * Tests for the configuration surface of {@link RedisClient}: constructors,
 * fluent setters and bean-style setters. No connection is attempted.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientConfigTest {

    @Test
    public void hostPortConstructor() {
        RedisClient c = new RedisClient("redis.example.org", 6380);
        assertEquals("redis.example.org", c.getDial().getHost());
        assertEquals(6380, c.getDial().getPort());
        assertFalse(c.isOpen());
    }

    @Test
    public void defaultPortWhenUnset() {
        RedisClient c = new RedisClient();
        assertEquals(6379, c.getDial().getPort());
        assertNotNull(c.getTls());
    }

    @Test
    public void addressConstructors() {
        InetAddress lo = InetAddress.getLoopbackAddress();
        RedisClient c = new RedisClient(lo, 7000);
        assertEquals(7000, c.getDial().getPort());
        RedisClient d = new RedisClient(null, lo, 7001);
        assertEquals(7001, d.getDial().getPort());
        RedisClient e = new RedisClient(null, "localhost", 7002);
        assertEquals(7002, e.getDial().getPort());
    }

    @Test
    public void socketPathConstructors() {
        RedisClient c = new RedisClient("/tmp/redis.sock");
        assertEquals("/tmp/redis.sock", c.getDial().getSocketPath());
        RedisClient d = new RedisClient(null, "/tmp/other.sock");
        assertEquals("/tmp/other.sock", d.getDial().getSocketPath());
    }

    @Test
    public void fluentSettersReturnSameInstance() {
        RedisClient c = new RedisClient();
        Path p = Paths.get("keystore.p12");
        assertSame(c, c.host("h"));
        assertSame(c, c.host(InetAddress.getLoopbackAddress()));
        assertSame(c, c.port(1234));
        assertSame(c, c.socketPath("/tmp/s"));
        assertSame(c, c.selectorLoop(null));
        assertSame(c, c.dnsResolver(null));
        assertSame(c, c.secure(true));
        assertSame(c, c.tls(new TlsConfig()));
    }

    @Test
    public void beanSettersDoNotThrow() {
        RedisClient c = new RedisClient();
        c.secure(true);
        c.tls(new TlsConfig());
    }

    @Test
    public void closeBeforeConnectIsSafe() {
        RedisClient c = new RedisClient();
        c.close();
        assertFalse(c.isOpen());
    }
}
