/*
 * SmtpServerLifecycleTest
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

package org.bluezoo.gumdrop.smtp.server;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.smtp.SmtpListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Lifecycle behaviour of {@link SmtpServer} and the relay and local delivery
 * session providers that the composition tests do not reach: servers without a
 * session provider, listener lists, provider start and stop with explicit DNS
 * servers, and opening sessions before start.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpServerLifecycleTest {

    /** Minimal server with no session provider. */
    private static final class BareServer extends SmtpServer {
        @Override
        public ClientConnected openSession(TcpListener listener) {
            return null;
        }
    }

    /** Listener that records start and stop instead of binding. */
    private static final class RecordingListener extends SmtpListener {
        final List<String> events = new ArrayList<String>();

        @Override
        public void start(org.bluezoo.gumdrop.Gumdrop gumdrop) {
            events.add("start");
        }

        @Override
        public void stop() {
            events.add("stop");
        }
    }

    @Test
    public void serverWithoutProviderStartsWiresAndStopsListeners() {
        BareServer server = new BareServer();
        RecordingListener listener = new RecordingListener();
        server.addListener(listener);
        server.setMaxRecipients(7);
        server.setMaxTransactionsPerSession(3);
        server.setAuthRequired(true);
        server.setMaxMessageSize(1234L);
        server.start(null);
        assertEquals(7, listener.getMaxRecipients());
        assertEquals(3, listener.getMaxTransactionsPerSession());
        assertTrue(listener.isAuthRequired());
        assertEquals(1234L, listener.getMaxMessageSize());
        assertSame(server, listener.getServer());
        assertNull(listener.getSessionProvider());
        assertNull(listener.getRealm());
        assertNull(listener.getMailboxFactory());
        server.stop();
        assertEquals(2, listener.events.size());
        assertEquals("stop", listener.events.get(1));
    }

    @Test
    public void setListenersKeepsOnlySmtpListeners() {
        BareServer server = new BareServer();
        RecordingListener listener = new RecordingListener();
        List<Object> mixed = new ArrayList<Object>();
        mixed.add("not a listener");
        mixed.add(listener);
        mixed.add(Integer.valueOf(1));
        server.setListeners(mixed);
        assertEquals(1, server.getListeners().size());
        assertSame(listener, server.getListeners().get(0));
    }

    @Test
    public void listenersViewCannotBeModified() {
        BareServer server = new BareServer();
        try {
            server.getListeners().add(new RecordingListener());
            fail("view is read only");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedEntryPointsDelegate() {
        RecordingListener listener = new RecordingListener();
        SmtpServer.Composer composer = SmtpServer.builder();
        SmtpServer server = composer.listener(listener)
                .sessionProvider(SmtpServerSessionProviders.relay())
                .build();
        assertNotNull(server);
        assertNull(new BareServer().createHandler(listener));
    }

    @Test
    public void relaySessionCannotBeOpenedBeforeStart() {
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay();
        try {
            provider.openSession(new SmtpListener());
            fail("not started");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not started"));
        }
    }

    @Test
    public void relayProviderStartsWithExplicitDnsServersAndOpensSessions() throws Exception {
        InetAddress a = InetAddress.getByAddress(new byte[] {10, 0, 0, 1});
        InetAddress b = InetAddress.getByAddress(new byte[] {10, 0, 0, 2});
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay()
                .hostname("relay.example.com")
                .timeoutMs(250L)
                .deliveryPort(2525)
                .servers(a, b)
                .server(a, 5353);
        provider.start();
        assertNotNull(provider.openSession(new SmtpListener()));
        // starting again is a no-op while the resolver exists
        provider.start();
        provider.stop();
        try {
            provider.openSession(new SmtpListener());
            fail("stopped");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        // stopping twice is harmless
        provider.stop();
    }

    @Test
    public void relayServerRejectsNullAddress() {
        try {
            SmtpServerSessionProviders.relay().server(null);
            fail("null address");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void relayServersNullClearsTheList() throws Exception {
        InetAddress a = InetAddress.getByAddress(new byte[] {10, 0, 0, 1});
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay()
                .server(a)
                .servers((InetAddress[]) null)
                .hostname("h.example.com")
                .dnsResolver(new org.bluezoo.gumdrop.dns.client.DnsResolver());
        provider.start();
        assertNotNull(provider.openSession(new SmtpListener()));
        provider.stop();
    }
}
