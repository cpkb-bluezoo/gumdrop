/*
 * FtpClientFacadeTest.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.net.InetAddress;

import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the {@link FtpClient} facade's configuration surface (constructors,
 * plain and fluent setters, target validation, close before connect). None of
 * it opens a connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpClientFacadeTest {

    private static final class NullGreeting implements RemoteGreeting {
        @Override
        public void handleGreeting(ClientLoginState login, String message) {
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
        FtpClient client = new FtpClient();
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
    public void plainSettersAcceptValues() {
        FtpClient client = new FtpClient("ftp.example.com", 2121);
        client.setSecure(true);
        client.setClientCredentials(null);
        client.setTrustManager(null);
        client.setKeystoreFile(null);
        client.setKeystorePass("x");
        client.setKeystoreFormat(null);
        assertFalse(client.isOpen());
    }

    @Test
    public void fluentSettersReturnTheClient() {
        Gumdrop gumdrop = TestGumdrop.create();
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        FtpClient client = new FtpClient();
        assertSame(client, client.selectorLoop(loop));
        assertSame(client, client.host("other.example.com"));
        assertSame(client, client.port(990));
        assertSame(client, client.secure(false));
        assertSame(client, client.trustJvm());
        assertSame(client, client.clientCredentials(null));
        assertSame(client, client.trustManager(null));
        assertSame(client, client.keystoreFile(null));
        assertSame(client, client.keystorePass("y"));
        assertSame(client, client.keystoreFormat(null));
        assertSame(client, client.host(InetAddress.getLoopbackAddress()));
        assertSame(client, client.socketPath("/tmp/ftp.sock"));
        assertSame(client, client.dnsResolver(null));
    }

    @Test
    public void allConstructorsAcceptTheirTargets() {
        Gumdrop gumdrop = TestGumdrop.create();
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        assertFalse(new FtpClient(loop, "h.example", 21).isOpen());
        assertFalse(new FtpClient(loopback, 21).isOpen());
        assertFalse(new FtpClient(loop, loopback, 21).isOpen());
        assertFalse(new FtpClient("/tmp/ftp.sock").isOpen());
        assertFalse(new FtpClient(loop, "/tmp/ftp.sock").isOpen());
    }
}
