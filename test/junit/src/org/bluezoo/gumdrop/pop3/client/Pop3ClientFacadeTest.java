/*
 * Pop3ClientFacadeTest.java
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

package org.bluezoo.gumdrop.pop3.client;

import java.net.InetAddress;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the {@link Pop3Client} facade's configuration surface (constructors,
 * fluent setters, target validation, close before connect). None of it
 * opens a connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Pop3ClientFacadeTest {

    private static final class NullGreeting implements RemoteGreeting {
        @Override
        public void handleGreeting(ClientAuthorizationState auth, String message) {
        }
        @Override
        public void handleServiceUnavailable(String message) {
        }

        @Override
        public void onConnected(Endpoint ep) {
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception e) {
        }
    }

    @Test
    public void connectWithoutATargetIsRejected() {
        Gumdrop gumdrop = TestGumdrop.create();
        Pop3Client client = new Pop3Client();
        try {
            client.connect(gumdrop, new NullGreeting());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("required"));
        }
        assertFalse(client.isOpen());
        client.close();
        assertFalse(client.isOpen());
    }

    @Test
    public void fluentSettersReturnTheClient() {
        Gumdrop gumdrop = TestGumdrop.create();
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        Pop3Client client = new Pop3Client();
        assertSame(client, client.selectorLoop(loop));
        assertSame(client, client.host("other.example.com"));
        assertSame(client, client.port(1234));
        assertSame(client, client.secure(true));
        assertSame(client, client.tls(new TlsConfig()));
        assertSame(client, client.host(InetAddress.getLoopbackAddress()));
        assertSame(client, client.socketPath("/tmp/pop3.sock"));
        assertSame(client, client.dnsResolver(null));
    }

    @Test
    public void allConstructorsAcceptTheirTargets() {
        Gumdrop gumdrop = TestGumdrop.create();
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        assertFalse(new Pop3Client("h.example", 1234).isOpen());
        assertFalse(new Pop3Client(loop, "h.example", 1234).isOpen());
        assertFalse(new Pop3Client(loopback, 1234).isOpen());
        assertFalse(new Pop3Client(loop, loopback, 1234).isOpen());
        assertFalse(new Pop3Client("/tmp/pop3.sock").isOpen());
        assertFalse(new Pop3Client(loop, "/tmp/pop3.sock").isOpen());
    }
}
