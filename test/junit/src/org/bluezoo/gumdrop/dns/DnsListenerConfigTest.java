/*
 * DnsListenerConfigTest.java
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

package org.bluezoo.gumdrop.dns;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.Collections;

import org.junit.Test;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.CidrNetwork;

import static org.junit.Assert.*;

/**
 * Configuration surface of the DNS listeners: the fluent setters return the
 * listener itself, and the defaults and descriptions are stable.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsListenerConfigTest {

    @Test
    public void doqFluentSettersReturnTheListener() throws Exception {
        DoQListener l = new DoQListener();
        assertSame(l, l.port(8853));
        assertEquals(8853, l.getPort());
        assertSame(l, l.bindWildcard());
        assertSame(l, l.addresses(InetAddress.getByName("127.0.0.1")));
        assertSame(l, l.secure(true));
        assertSame(l, l.tls(TlsConfig.pem(Paths.get("c.pem"), Paths.get("k.pem"))));
        assertSame(l, l.maxConnections(10));
        assertSame(l, l.maxConnectionsPerIP(2));
        assertSame(l, l.rateLimit("5/10s"));
        assertSame(l, l.maxAuthFailures(3));
        assertSame(l, l.authLockoutTimeMs(1000L));
        assertSame(l, l.allowedNetworks(Collections.<CidrNetwork>emptyList()));
        assertSame(l, l.blockedNetworks(Collections.<CidrNetwork>emptyList()));
        assertSame(l, l.name("doq"));
        assertSame(l, l.maxNetInSize(2048));
        assertSame(l, l.maxNetOutSize(2048));
        assertSame(l, l.idleTimeoutMs(1000L));
        assertSame(l, l.readTimeoutMs(1000L));
        assertSame(l, l.connectionTimeoutMs(1000L));
        assertSame(l, l.maxDtlsPeers(4));
        assertSame(l, l.requireRetry(false));
        assertSame(l, l.selectorLoop(null));
        assertNull(l.getSelectorLoop());
    }

    @Test
    public void doqServerAndStreamHandlers() {
        DoQListener l = new DoQListener();
        assertEquals("dns-quic", l.getDescription());
        assertFalse(l.requiresTcpAccept());
        assertNull(l.getServer());
        DnsServer server = new DnsServer();
        assertSame(l, l.server(server));
        assertSame(server, l.getServer());
        assertNull("connections are not handled; streams are", l.createHandler());
        ProtocolHandler stream = l.acceptStream(null);
        assertTrue(stream instanceof DoQStreamHandler);
    }

    @Test
    public void doqTransportFactoryAcceptsCertificateAndKeyPaths() {
        DoQListener l = new DoQListener();
        l.setCertFile(java.nio.file.Paths.get("cert.pem"));
        l.setKeyFile(java.nio.file.Paths.get("key.pem"));
        assertNotNull(l.createTransportFactory());
    }

    @Test(expected = IllegalStateException.class)
    public void doqStartWithoutSelectorLoopFails() {
        new DoQListener().start(null);
    }

    @Test
    public void dotFluentSettersReturnTheListener() throws Exception {
        DoTListener l = new DoTListener();
        assertEquals(853, l.getPort());
        assertSame(l, l.port(8853));
        assertEquals(8853, l.getPort());
        assertSame(l, l.bindWildcard());
        assertSame(l, l.addresses(InetAddress.getByName("127.0.0.1")));
        assertSame(l, l.secure(true));
        assertSame(l, l.tls(TlsConfig.pem(Paths.get("c.pem"), Paths.get("k.pem"))));
        assertSame(l, l.maxConnections(10));
        assertSame(l, l.maxConnectionsPerIP(2));
        assertSame(l, l.rateLimit("5/10s"));
        assertSame(l, l.maxAuthFailures(3));
        assertSame(l, l.authLockoutTimeMs(1000L));
        assertSame(l, l.allowedNetworks(Collections.<CidrNetwork>emptyList()));
        assertSame(l, l.blockedNetworks(Collections.<CidrNetwork>emptyList()));
        assertSame(l, l.name("dot"));
        assertSame(l, l.maxNetInSize(2048));
        assertSame(l, l.maxNetOutSize(2048));
        assertSame(l, l.idleTimeoutMs(1000L));
        assertSame(l, l.readTimeoutMs(1000L));
        assertSame(l, l.connectionTimeoutMs(1000L));
        assertSame(l, l.maxDtlsPeers(4));
    }

    @Test
    public void dotServerAndHandler() {
        DoTListener l = new DoTListener();
        assertEquals("dns-tls", l.getDescription());
        assertNull(l.getServer());
        DnsServer server = new DnsServer();
        assertSame(l, l.server(server));
        assertSame(server, l.getServer());
        assertTrue(l.createHandler() instanceof DoTProtocolHandler);
    }

    @Test
    public void plainDnsListenerDescriptionAndDefaults() {
        DnsListener l = new DnsListener();
        assertEquals("dns", l.getDescription());
        assertEquals(53, l.getPort());
        assertNull(l.getServer());
        assertNull("not bound, so no loop", l.getSelectorLoop());
        assertNotNull(l.createProtocolHandler());
    }

    @Test
    public void datagramWithoutServerIsDropped() {
        DnsListener l = new DnsListener();
        ProtocolHandler handler = l.createProtocolHandler();
        handler.connected(null);
        handler.receive(ByteBuffer.allocate(12));
        handler.securityEstablished(null);
        handler.disconnected();
        handler.error(new java.io.IOException("x"));
    }
}
