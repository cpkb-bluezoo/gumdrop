/*
 * SocksServerComposeTest.java
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

package org.bluezoo.gumdrop.socks.server;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.socks.SocksListener;
import org.bluezoo.gumdrop.socks.SocksProtocolHandler;
import org.bluezoo.gumdrop.socks.SocksRequest;
import org.bluezoo.gumdrop.util.CidrNetwork;

import static org.junit.Assert.*;

/**
 * Tests for {@link SocksServer} composition, listener management and
 * session-provider wiring.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksServerComposeTest {

    /** Session handler that allows everything. */
    private static final class AllowAll implements SocksSessionHandler {
        @Override
        public void handleConnect(ConnectState state, SocksRequest request,
                                  Endpoint clientEndpoint) {
            state.allow();
        }

        @Override
        public void handleBind(BindState state, SocksRequest request,
                               Endpoint clientEndpoint) {
            state.allow();
        }
    }

    /** Provider that counts lifecycle calls. */
    private static final class CountingProvider
            implements SocksServerSessionProvider {
        int started;
        int stopped;
        int opened;

        @Override
        public SocksSessionHandler openSession(TcpListener listener) {
            opened++;
            return new AllowAll();
        }

        @Override
        public void start() {
            started++;
        }

        @Override
        public void stop() {
            stopped++;
        }
    }

    @Test
    public void composeBuildsConfiguredServer() {
        SocksListener l1 = new SocksListener();
        SocksListener l2 = new SocksListener();
        List<CidrNetwork> allowed = CidrNetwork.parseList("10.0.0.0/8");
        List<CidrNetwork> blocked = CidrNetwork.parseList("10.9.0.0/16");
        CountingProvider provider = new CountingProvider();
        SocksServer server = SocksServer.compose()
                .listener(l1)
                .listener(l2)
                .sessionProvider(provider)
                .allowedDestinations(allowed)
                .blockedDestinations(blocked)
                .maxRelays(3)
                .relayIdleTimeoutMs(1234L)
                .server();
        assertEquals(2, server.getListeners().size());
        assertEquals(3, server.getMaxRelays());
        assertEquals(1234L, server.getRelayIdleTimeoutMs());
        assertNull(server.getRealm());
        InetAddressHolder h = new InetAddressHolder();
        assertTrue(server.isDestinationAllowed(h.addr(10, 1, 0, 1)));
        assertFalse(server.isDestinationAllowed(h.addr(10, 9, 0, 1)));
        assertFalse(server.isDestinationAllowed(h.addr(8, 8, 8, 8)));
    }

    @Test(expected = IllegalStateException.class)
    public void composeRequiresListener() {
        SocksServer.compose().server();
    }

    @Test(expected = NullPointerException.class)
    public void composeRejectsNullListener() {
        SocksServer.compose().listener(null);
    }

    @Test(expected = NullPointerException.class)
    public void composeRejectsNullProvider() {
        SocksServer.compose().sessionProvider(null);
    }

    @Test
    public void composeWithRealmAndPerSession() {
        SocksListener l = new SocksListener();
        Realm realm = null;
        SocksServer server = SocksServer.compose()
                .listener(l)
                .realm(realm)
                .sessionPerConnection(new Supplier<SocksSessionHandler>() {
                    @Override
                    public SocksSessionHandler get() {
                        return new AllowAll();
                    }
                })
                .server();
        SocksSessionHandler session = server.openSession(l);
        assertNotNull(session);
        SocksProtocolHandler handler = server.createProtocolHandler(l);
        assertNotNull(handler);
    }

    @Test(expected = NullPointerException.class)
    public void perSessionRejectsNullSupplier() {
        SocksServerSessionProviders.perSession(null);
    }

    @Test
    public void openSessionWithoutProviderIsNull() {
        SocksServer server = new SocksServer();
        assertNull(server.openSession(new SocksListener()));
        SocksProtocolHandler handler =
                server.createProtocolHandler(new SocksListener());
        assertNotNull(handler);
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void setListenersFiltersNonListeners() {
        SocksServer server = new SocksServer();
        List items = new ArrayList();
        items.add(new SocksListener());
        items.add("not a listener");
        server.setListeners(items);
        assertEquals(1, server.getListeners().size());
    }

    @Test
    public void startAndStopDriveProviderLifecycle() {
        CountingProvider provider = new CountingProvider();
        SocksListener l = new SocksListener();
        SocksServer server = SocksServer.compose()
                .listener(l)
                .sessionProvider(provider)
                .server();
        server.start(null);
        assertEquals(1, provider.started);
        assertSame(server, l.getServer());
        server.stop();
        assertEquals(1, provider.stopped);
    }

    @Test
    public void relayAccounting() {
        SocksServer server = new SocksServer();
        assertEquals(0, server.getMaxRelays());
        assertTrue(server.acquireRelay());
        assertTrue(server.acquireRelay());
        assertEquals(2, server.getActiveRelayCount());
        server.releaseRelay();
        assertEquals(1, server.getActiveRelayCount());
        server.setMaxRelays(1);
        assertFalse(server.acquireRelay());
        server.releaseRelay();
        assertTrue(server.acquireRelay());
    }

    @Test
    public void emptyDestinationListsClearFilters() {
        SocksServer server = new SocksServer();
        server.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        server.setBlockedDestinations(new ArrayList<CidrNetwork>());
        server.setAllowedDestinations(null);
        InetAddressHolder h = new InetAddressHolder();
        assertTrue(server.isDestinationAllowed(h.addr(10, 0, 0, 1)));
    }

    /** Small helper avoiding checked exceptions in tests. */
    private static final class InetAddressHolder {
        java.net.InetAddress addr(int a, int b, int c, int d) {
            byte[] raw = new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
            try {
                return java.net.InetAddress.getByAddress(raw);
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
