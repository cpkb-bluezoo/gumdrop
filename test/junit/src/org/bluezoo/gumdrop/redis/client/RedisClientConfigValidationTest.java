/*
 * RedisClientConfigValidationTest.java
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

import java.time.Duration;

import org.junit.Test;

import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * What the two clients accept as options and what they refuse, with no
 * connection attempted.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientConfigValidationTest {

    @Test
    public void theSingleNodeClientRefusesWhatCannotWork() {
        RedisClient c = new RedisClient("localhost", 6379);
        assertSame(c, c.protocol(2));
        assertSame(c, c.protocol(3));
        assertSame(c, c.commandTimeout(Duration.ofSeconds(1)));
        assertSame(c, c.maxPendingCommands(0));
        assertSame(c, c.healthCheckInterval(Duration.ZERO));
        try {
            c.protocol(4);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            c.commandTimeout(Duration.ofMillis(-1));
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            c.maxPendingCommands(-1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            c.healthCheckInterval(Duration.ofMillis(-1));
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void theClusterClientAcceptsItsOptionsAndRefusesWhatCannotWork() {
        RedisClusterClient c = new RedisClusterClient();
        assertSame(c, c.seed("localhost", 7000));
        assertSame(c, c.selectorLoop(null));
        assertSame(c, c.dnsResolver(null));
        assertSame(c, c.secure(false));
        assertSame(c, c.tls(new TlsConfig()));
        assertSame(c, c.protocol(3));
        assertSame(c, c.healthCheckInterval(Duration.ofSeconds(1)));
        assertSame(c, c.auth("secret"));
        assertSame(c, c.auth("user", "secret"));
        try {
            c.protocol(1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            c.healthCheckInterval(Duration.ofMillis(-5));
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void theNodeConnectorConfiguresAClientForEachNode() {
        TcpNodeConnector connector = new TcpNodeConnector(null, null, null, false, new TlsConfig(),
                "user", "secret", 3, 4096, 256, 1024L);
        RedisClient client = connector.newClient(new ClusterNode("10.0.0.7", 7005, null));
        assertEquals("10.0.0.7", client.getDial().getHost());
        assertEquals(7005, client.getDial().getPort());
        assertFalse(client.isOpen());
        TcpNodeConnector withResolver = new TcpNodeConnector(null, null,
                (org.bluezoo.gumdrop.dns.client.DnsResolver) null, true, new TlsConfig(), null, null, 2,
                4096, 256, 1024L);
        assertEquals(7001, withResolver.newClient(new ClusterNode("h", 7001, null)).getDial().getPort());
    }

    @Test
    public void aListenerThatSaysNothingMoreIgnoresTheRest() {
        RedirectListener l = new RedirectListener() {
            @Override
            public boolean handleError(SentCommand command, String error) {
                return false;
            }
        };
        l.connectionLost(new java.util.ArrayList<SentCommand>());
        l.healthCheckFailed();
    }

}
