/*
 * Http2ListenerLifecycleTest.java
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

package org.bluezoo.gumdrop.http.server;

import org.junit.Test;

import org.bluezoo.gumdrop.TcpTransportFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Port defaulting, ALPN advertisement and handler creation of
 * {@link Http2Listener}, and the value semantics of {@link HttpPrincipal}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http2ListenerLifecycleTest {

    @Test
    public void boundPortIsAdoptedOnlyWhenNoneWasConfigured() {
        Http2Listener unset = new Http2Listener();
        unset.setPort(0);
        unset.applyBoundTcpPort(4711);
        assertEquals(4711, unset.getPort());

        Http2Listener fixed = new Http2Listener();
        fixed.setPort(8080);
        fixed.applyBoundTcpPort(4711);
        assertEquals(8080, fixed.getPort());
    }

    @Test
    public void secureListenersAdvertiseH2ThenHttp11() {
        Http2Listener secure = new Http2Listener();
        secure.setSecure(true);
        TcpTransportFactory factory = new TcpTransportFactory();
        secure.configureTransportFactory(factory);
        assertArrayEquals(new String[] {"h2", "http/1.1"}, factory.getApplicationProtocols());

        Http2Listener plain = new Http2Listener();
        TcpTransportFactory plainFactory = new TcpTransportFactory();
        plain.configureTransportFactory(plainFactory);
        assertNull(plainFactory.getApplicationProtocols());
    }

    @Test
    public void createsAProtocolHandlerPerConnection() {
        Http2Listener listener = new Http2Listener();
        assertNotNull(listener.createHandler());
    }

    @Test
    public void principalHasValueSemantics() {
        HttpPrincipal alice = new HttpPrincipal("alice");
        assertEquals("alice", alice.getName());
        assertEquals("alice", alice.toString());
        assertEquals(alice, new HttpPrincipal("alice"));
        assertEquals(alice.hashCode(), new HttpPrincipal("alice").hashCode());
        assertFalse(alice.equals(new HttpPrincipal("bob")));
        assertFalse(alice.equals("alice"));
        assertTrue(alice.equals(alice));
    }
}
