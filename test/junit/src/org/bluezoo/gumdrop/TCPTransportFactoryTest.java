/*
 * TCPTransportFactoryTest.java
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

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TCPTransportFactory}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TCPTransportFactoryTest {

    /**
     * RFC 7413: TCP Fast Open flag can be set and queried.
     */
    @Test
    public void testTcpFastOpenDefault() {
        TCPTransportFactory factory = new TCPTransportFactory();
        assertFalse(factory.isTcpFastOpen());
    }

    @Test
    public void testTcpFastOpenEnabled() {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.setTcpFastOpen(true);
        assertTrue(factory.isTcpFastOpen());
    }

    @Test
    public void testTcpFastOpenDisabled() {
        TCPTransportFactory factory = new TCPTransportFactory();
        factory.setTcpFastOpen(true);
        factory.setTcpFastOpen(false);
        assertFalse(factory.isTcpFastOpen());
    }

    /**
     * RFC 5077 / RFC 7858 section 3.4: TLS session cache is configured
     * when a secure factory is started (integration-level; here we
     * verify the factory is constructable).
     */
    @Test
    public void testSessionCacheConfigurationAccessible() throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        assertNotNull(factory);
    }

    @Test
    public void tlsServerNameForIpv6LoopbackUsesLocalhost() throws Exception {
        InetAddress loopback = InetAddress.getByName("::1");
        assertEquals("localhost", TCPTransportFactory.tlsServerNameFor(loopback, null));
        assertEquals("localhost", TCPTransportFactory.tlsServerNameFor(null, "::1"));
        assertEquals("localhost", TCPTransportFactory.tlsServerNameFor(null, "127.0.0.1"));
    }
}
