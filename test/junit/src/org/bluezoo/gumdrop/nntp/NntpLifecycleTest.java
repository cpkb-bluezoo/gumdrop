/*
 * NntpLifecycleTest.java
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

package org.bluezoo.gumdrop.nntp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.nntp.server.ClientConnected;
import org.bluezoo.gumdrop.nntp.server.DefaultNntpHandler;
import org.bluezoo.gumdrop.nntp.server.MailboxStoreNntpSessionProvider;
import org.bluezoo.gumdrop.nntp.server.NntpServer;
import org.bluezoo.gumdrop.nntp.server.NntpServerSessionProvider;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The listener and the server that holds it: defaults, wiring, starting and
 * stopping, with no sockets (starting a listener only prepares it).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpLifecycleTest {

    private static final class Exposed extends NntpListener {
        ProtocolHandler handler() {
            return createHandler();
        }

        boolean starttls() {
            return isSTARTTLSAvailable();
        }
    }

    @Test
    public void testDescriptionAndDefaultPorts() {
        NntpListener plain = new NntpListener();
        assertEquals("nntp", plain.getDescription());
        assertEquals(-1, plain.getPort());
        plain.start();
        assertEquals(119, plain.getPort());

        NntpListener secure = new NntpListener().secure(true);
        assertEquals("nntps", secure.getDescription());
        secure.start();
        assertEquals(563, secure.getPort());

        NntpListener explicit = new NntpListener().port(1119);
        explicit.start();
        assertEquals(1119, explicit.getPort());
    }

    @Test
    public void testSettingsAreKept() throws Exception {
        NntpListener l = new NntpListener()
                .bindWildcard()
                .addresses(InetAddress.getLoopbackAddress())
                .realm(null)
                .anonymousUser("guest")
                .postingAllowed(true)
                .authenticationRequired(true);
        assertEquals("guest", l.getAnonymousUser());
        assertTrue(l.isPostingAllowed());
        assertTrue(l.isAuthenticationRequired());
        assertNull(l.getRealm());
        assertEquals("news", new NntpListener().getAnonymousUser());
        assertFalse(new NntpListener().isPostingAllowed());
        assertFalse(new NntpListener().isAuthenticationRequired());
    }

    @Test
    public void testHandlersAreMadeAndStarttlsFollowsTlsConfiguration() {
        Exposed l = new Exposed();
        assertTrue(l.handler() instanceof NntpProtocolHandler);
        assertFalse(l.starttls());
    }

    @Test
    public void testMetricsFollowTheTelemetryConfiguration() {
        TelemetryConfig on = new TelemetryConfig();
        Gumdrop g = TestGumdrop.create();
        g.telemetryConfig(on);
        NntpListener l = new NntpListener();
        assertNull(l.getMetrics());
        l.start(g);
        assertNotNull(l.getMetrics());

        TelemetryConfig off = new TelemetryConfig();
        off.metricsEnabled(false);
        Gumdrop g2 = TestGumdrop.create();
        g2.telemetryConfig(off);
        NntpListener l2 = new NntpListener();
        l2.start(g2);
        assertNull(l2.getMetrics());
    }

    private static final class FailingProvider implements NntpServerSessionProvider {
        @Override
        public ClientConnected openSession(TcpListener listener) {
            throw new IllegalStateException("broken provider");
        }
    }

    @Test
    public void testApplicationSessionFromTheListenersProvider() {
        final ClientConnected session = new DefaultNntpHandler("hi", false);
        NntpListener l = new NntpListener().sessionProvider(new NntpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return session;
            }
        });
        assertSame(session, l.openApplicationSession());
        assertNotNull(l.getSessionProvider());
    }

    @Test
    public void testBrokenProviderFallsBackToTheServer() {
        NntpListener l = new NntpListener().sessionProvider(new FailingProvider());
        assertNull(l.openApplicationSession());

        NntpListener viaServer = new NntpListener();
        NntpServer server = NntpServer.compose().listener(viaServer)
                .sessionProvider(new FailingProvider()).server();
        viaServer.server(server);
        assertSame(server, viaServer.getServer());
        assertNull(viaServer.openApplicationSession());
    }

    @Test
    public void testServerSessionComesFromItsProvider() {
        final ClientConnected session = new DefaultNntpHandler("hi", false);
        NntpListener l = new NntpListener();
        NntpServer server = NntpServer.compose().listener(l)
                .sessionProvider(new NntpServerSessionProvider() {
                    @Override
                    public ClientConnected openSession(TcpListener listener) {
                        return session;
                    }
                }).server();
        l.server(server);
        assertSame(session, l.openApplicationSession());
    }

    // ── the server ──

    /** Notes what it is told. */
    private static final class Recording implements NntpServerSessionProvider {
        final List<String> events = new ArrayList<String>();

        @Override
        public ClientConnected openSession(TcpListener listener) {
            return null;
        }

        @Override
        public void start() {
            events.add("start");
        }

        @Override
        public void stop() {
            events.add("stop");
        }
    }

    @Test
    public void testServerWiresItsSettingsIntoItsListenersWhenStarted() {
        org.bluezoo.gumdrop.auth.Realm realm = new org.bluezoo.gumdrop.auth.BasicRealm();
        Recording provider = new Recording();
        NntpListener a = new NntpListener().port(11119);
        NntpListener b = new NntpListener().port(11563).secure(true);
        NntpServer server = NntpServer.compose().listener(a).listener(b)
                .sessionProvider(provider)
                .anonymousUser("guest")
                .postingAllowed(true)
                .authenticationRequired(true)
                .realm(realm)
                .server();
        Gumdrop g = TestGumdrop.create();
        server.start(g);
        assertEquals(java.util.Arrays.asList("start"), provider.events);
        for (NntpListener l : new NntpListener[] {a, b}) {
            assertEquals("guest", l.getAnonymousUser());
            assertTrue(l.isPostingAllowed());
            assertTrue(l.isAuthenticationRequired());
            assertSame(realm, l.getRealm());
            assertSame(provider, l.getSessionProvider());
            assertSame(server, l.getServer());
        }
        server.stop();
        assertEquals(java.util.Arrays.asList("start", "stop"), provider.events);
    }

    @Test
    public void testServerLeavesListenerSettingsAloneWhenItHasNone() {
        NntpListener l = new NntpListener().anonymousUser("mine");
        NntpServer server = NntpServer.compose().listener(l).server();
        server.start(TestGumdrop.create());
        assertEquals("mine", l.getAnonymousUser());
        assertFalse(l.isPostingAllowed());
        server.stop();
    }

    @Test
    public void testAListenerThatFailsToStartOrStopDoesNotStopTheRest() {
        final boolean[] started = {false};
        NntpListener bad = new NntpListener() {
            @Override
            public void start(Gumdrop gumdrop) {
                throw new IllegalStateException("cannot bind");
            }

            @Override
            public void stop() {
                throw new IllegalStateException("cannot stop");
            }
        };
        NntpListener good = new NntpListener() {
            @Override
            public void start(Gumdrop gumdrop) {
                started[0] = true;
            }
        };
        Recording provider = new Recording();
        NntpServer server = NntpServer.compose().listener(bad).listener(good).sessionProvider(provider).server();
        server.start(TestGumdrop.create());
        assertTrue(started[0]);
        server.stop();
        assertEquals(java.util.Arrays.asList("start", "stop"), provider.events);
    }

    @Test
    public void testSessionProviderForAMailboxStore() {
        MailboxFactory factory = new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return null;
            }
        };
        MailboxStoreNntpSessionProvider provider = new MailboxStoreNntpSessionProvider();
        assertNull(provider.getMailboxFactory());
        provider.start(); // warns that there is no factory
        provider.mailboxFactory(factory);
        assertSame(factory, provider.getMailboxFactory());
        provider.start();
    }
}
