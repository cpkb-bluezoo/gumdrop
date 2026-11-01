/*
 * ImapClientFacadeTest.java
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

package org.bluezoo.gumdrop.imap.client;

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
 * Tests the {@link ImapClient} facade's configuration surface (constructors,
 * fluent setters, target validation, close before connect). None of it
 * opens a connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapClientFacadeTest {

    private static final class NullGreeting implements RemoteGreeting {
        @Override
        public void handleGreeting(ClientNotAuthenticatedState auth, String greeting,
                List<String> preAuthCapabilities) {
        }

        @Override
        public void handlePreAuthenticated(ClientAuthenticatedState auth, String greeting) {
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
        ImapClient client = new ImapClient();
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
        ImapClient client = new ImapClient();
        assertSame(client, client.selectorLoop(loop));
        assertSame(client, client.host("other.example.com"));
        assertSame(client, client.port(1234));
        assertSame(client, client.secure(true));
        assertSame(client, client.tls(new TlsConfig()));
        assertSame(client, client.host(InetAddress.getLoopbackAddress()));
        assertSame(client, client.socketPath("/tmp/imap.sock"));
        assertSame(client, client.dnsResolver(null));
    }

    @Test
    public void allConstructorsAcceptTheirTargets() {
        Gumdrop gumdrop = TestGumdrop.create();
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        assertFalse(new ImapClient("h.example", 1234).isOpen());
        assertFalse(new ImapClient(loop, "h.example", 1234).isOpen());
        assertFalse(new ImapClient(loopback, 1234).isOpen());
        assertFalse(new ImapClient(loop, loopback, 1234).isOpen());
        assertFalse(new ImapClient("/tmp/imap.sock").isOpen());
        assertFalse(new ImapClient(loop, "/tmp/imap.sock").isOpen());
    }

    @Test
    public void mailboxEventListenerCanBeSetBeforeConnect() {
        ImapClient client = new ImapClient("h.example", 143);
        client.setMailboxEventListener(new MailboxEventListener() {
            @Override
            public void onExists(int count) {
            }

            @Override
            public void onRecent(int count) {
            }

            @Override
            public void onExpunge(int messageNumber) {
            }

            @Override
            public void onFlagsUpdate(int messageNumber, String[] flags) {
            }

            @Override
            public void onMailboxStatus(String mailbox, int messages, long uidNext) {
            }
        });
        assertFalse(client.isOpen());
    }
}
