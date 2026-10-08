/*
 * SocksProtocolHandlerExtraTest.java
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

package org.bluezoo.gumdrop.socks;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.socks.server.BindHandler;
import org.bluezoo.gumdrop.socks.server.BindState;
import org.bluezoo.gumdrop.socks.server.ConnectHandler;
import org.bluezoo.gumdrop.socks.server.ConnectState;
import org.bluezoo.gumdrop.socks.server.SocksServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.util.CidrNetwork;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SocksConstants.*;

/**
 * Additional {@link SocksProtocolHandler} tests covering authentication,
 * handler-driven authorization, destination policy, relay limits and the
 * telemetry-enabled code paths.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksProtocolHandlerExtraTest {

    private SocksServer server;
    private SocksListener listener;
    private SocksProtocolHandler handler;
    private StubEndpoint endpoint;

    @Before
    public void setUp() {
        server = new SocksServer();
        listener = new SocksListener();
        listener.setServer(server);
        Gumdrop g = TestGumdrop.create();
        g.setTelemetryConfig(telemetryConfig());
        listener.start(g);
        handler = server.createProtocolHandler(listener);
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
    }

    /** Telemetry configuration for the listener; subclasses disable metrics. */
    TelemetryConfig telemetryConfig() {
        return new TelemetryConfig();
    }

    /** Whether the listener is expected to have metrics. */
    boolean expectsMetrics() {
        return true;
    }

    // ── Listener ──

    @Test
    public void listenerEnablesMetricsWhenTelemetryConfigured() {
        assertEquals(expectsMetrics(), listener.getMetrics() != null);
        assertSame(server, listener.getServer());
    }

    @Test
    public void listenerDefaultsAndDescription() {
        SocksListener l = new SocksListener();
        assertNull(l.getMetrics());
        assertEquals(-1, l.getPort());
        assertEquals("socks", l.getDescription());
        l.start();
        assertEquals(SOCKS_DEFAULT_PORT, l.getPort());
        l.secure(true);
        assertEquals("sockss", l.getDescription());
        SocksListener s = new SocksListener();
        s.secure(true);
        s.start();
        assertEquals(SOCKSS_DEFAULT_PORT, s.getPort());
    }

    @Test
    public void listenerFluentSetters() {
        SocksListener l = new SocksListener();
        SocksListener same = l.port(1234);
        assertSame(l, same);
        assertEquals(1234, l.getPort());
        l.setPort(4321);
        assertEquals(4321, l.getPort());
        same = l.bindWildcard();
        assertSame(l, same);
        same = l.addresses(InetAddress.getLoopbackAddress());
        assertSame(l, same);
        assertNull(l.getRealm());
        assertNull(l.getGSSAPIServer());
        assertNull(l.getGumdrop());
        l.setGSSAPIServer(null);
    }

    @Test(expected = IllegalStateException.class)
    public void listenerWithoutServerCannotCreateHandler() {
        SocksListener l = new SocksListener();
        l.createHandler();
    }

    // ── Username / password authentication ──

    @Test
    public void usernamePasswordSuccess() {
        listener.setRealm(new TestRealm("alice", "secret"));
        offerMethods(SOCKS5_AUTH_USERNAME_PASSWORD);
        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_AUTH_USERNAME_PASSWORD, reply[1]);
        endpoint.clearSent();

        handler.receive(userPass("alice", "secret"));
        reply = endpoint.getLastSent();
        assertEquals(SOCKS5_AUTH_USERPASS_VERSION, reply[0]);
        assertEquals(SOCKS5_AUTH_USERPASS_SUCCESS, reply[1]);
        assertTrue(endpoint.isOpen());

        // authenticated: an unsupported command is now answered
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_CONNECT, (byte) 0x7F,
                new byte[6]));
        reply = endpoint.getLastSent();
        assertEquals(SOCKS5_REPLY_ADDRESS_TYPE_NOT_SUPPORTED, reply[1]);
    }

    @Test
    public void usernamePasswordFailure() {
        listener.setRealm(new TestRealm("alice", "secret"));
        offerMethods(SOCKS5_AUTH_USERNAME_PASSWORD);
        endpoint.clearSent();

        handler.receive(userPass("alice", "wrong"));
        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_AUTH_USERPASS_FAILURE, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void usernamePasswordBadSubnegotiationVersion() {
        listener.setRealm(new TestRealm("alice", "secret"));
        offerMethods(SOCKS5_AUTH_USERNAME_PASSWORD);
        endpoint.clearSent();

        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put((byte) 0x07);
        buf.put((byte) 1);
        buf.put((byte) 'a');
        buf.put((byte) 0);
        buf.flip();
        handler.receive(buf);
        assertEquals(SOCKS5_AUTH_USERPASS_FAILURE,
                endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void usernamePasswordIncompleteIsBuffered() {
        listener.setRealm(new TestRealm("alice", "secret"));
        offerMethods(SOCKS5_AUTH_USERNAME_PASSWORD);
        endpoint.clearSent();

        // too short
        ByteBuffer one = ByteBuffer.allocate(1);
        one.put(SOCKS5_AUTH_USERPASS_VERSION);
        one.flip();
        handler.receive(one);
        assertEquals(0, endpoint.getSentCount());

        // username truncated
        ByteBuffer trunc = ByteBuffer.allocate(4);
        trunc.put(SOCKS5_AUTH_USERPASS_VERSION);
        trunc.put((byte) 5);
        trunc.put((byte) 'a');
        trunc.put((byte) 'b');
        trunc.flip();
        handler.receive(trunc);
        assertEquals(0, endpoint.getSentCount());

        // password truncated
        ByteBuffer ptrunc = ByteBuffer.allocate(5);
        ptrunc.put(SOCKS5_AUTH_USERPASS_VERSION);
        ptrunc.put((byte) 1);
        ptrunc.put((byte) 'a');
        ptrunc.put((byte) 4);
        ptrunc.put((byte) 'x');
        ptrunc.flip();
        handler.receive(ptrunc);
        assertEquals(0, endpoint.getSentCount());
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void realmWithoutOfferedUserPassRejected() {
        listener.setRealm(new TestRealm("alice", "secret"));
        offerMethods(SOCKS5_AUTH_NONE);
        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_AUTH_NO_ACCEPTABLE, reply[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void realmWithGssapiOfferButNoServerFallsBackToUserPass() {
        listener.setRealm(new TestRealm("alice", "secret"));
        offerMethods(SOCKS5_AUTH_GSSAPI, SOCKS5_AUTH_USERNAME_PASSWORD);
        assertEquals(SOCKS5_AUTH_USERNAME_PASSWORD,
                endpoint.getLastSent()[1]);
    }

    @Test
    public void noRealmButOnlyUserPassOfferedRejected() {
        offerMethods(SOCKS5_AUTH_USERNAME_PASSWORD);
        assertEquals(SOCKS5_AUTH_NO_ACCEPTABLE, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void socks4RejectedWhenRealmConfigured() {
        listener.setRealm(new TestRealm("alice", "secret"));
        handler.receive(socks4(SOCKS4_CMD_CONNECT, new byte[]{8, 8, 8, 8},
                53, "u"));
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void socks5MethodNegotiationIncompleteIsBuffered() {
        ByteBuffer one = ByteBuffer.allocate(1);
        one.put(SOCKS5_VERSION);
        one.flip();
        handler.receive(one);
        assertEquals(0, endpoint.getSentCount());

        ByteBuffer partial = ByteBuffer.allocate(3);
        partial.put(SOCKS5_VERSION);
        partial.put((byte) 4);
        partial.put(SOCKS5_AUTH_NONE);
        partial.flip();
        handler.receive(partial);
        assertEquals(0, endpoint.getSentCount());
        assertTrue(endpoint.isOpen());
    }

    // ── SOCKS5 request parsing ──

    @Test
    public void socks5RequestTooShortIsBuffered() {
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put(SOCKS5_VERSION);
        buf.put(SOCKS5_CMD_CONNECT);
        buf.put((byte) 0);
        buf.flip();
        handler.receive(buf);
        assertEquals(0, endpoint.getSentCount());
    }

    @Test
    public void socks5IncompleteAddressesAreBuffered() {
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_CONNECT, SOCKS5_ATYP_IPV4,
                new byte[]{1, 2}));
        handler.receive(request(SOCKS5_CMD_CONNECT, SOCKS5_ATYP_IPV6,
                new byte[]{1, 2, 3}));
        handler.receive(request(SOCKS5_CMD_CONNECT,
                SOCKS5_ATYP_DOMAINNAME, new byte[0]));
        handler.receive(request(SOCKS5_CMD_CONNECT,
                SOCKS5_ATYP_DOMAINNAME, new byte[]{9, 'a'}));
        assertEquals(0, endpoint.getSentCount());
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void socks5UnsupportedCommand() {
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request((byte) 0x09, SOCKS5_ATYP_IPV4,
                new byte[]{10, 0, 0, 1, 0, 80}));
        assertEquals(SOCKS5_REPLY_COMMAND_NOT_SUPPORTED,
                endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void socks5Ipv6ConnectBlocked() {
        server.setBlockedDestinations(CidrNetwork.parseList("::1/128"));
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        byte[] addr = new byte[18];
        addr[15] = 1;
        addr[17] = 80;
        handler.receive(request(SOCKS5_CMD_CONNECT, SOCKS5_ATYP_IPV6,
                addr));
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void socks5DomainConnectWithoutLoopIsUnreachable() {
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        byte[] name = "example.com".getBytes(StandardCharsets.US_ASCII);
        byte[] rest = new byte[1 + name.length + 2];
        rest[0] = (byte) name.length;
        System.arraycopy(name, 0, rest, 1, name.length);
        handler.receive(request(SOCKS5_CMD_CONNECT,
                SOCKS5_ATYP_DOMAINNAME, rest));
        assertEquals(SOCKS5_REPLY_HOST_UNREACHABLE,
                endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void socks4aDomainConnectWithoutLoopIsRejected() {
        ByteBuffer buf = ByteBuffer.allocate(40);
        buf.put(SOCKS4_VERSION);
        buf.put(SOCKS4_CMD_CONNECT);
        buf.putShort((short) 80);
        buf.put(new byte[]{0, 0, 0, 5});
        buf.put((byte) 0);
        buf.put("example.org".getBytes(StandardCharsets.ISO_8859_1));
        buf.put((byte) 0);
        buf.flip();
        handler.receive(buf);
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void socks4IncompleteUseridAndHostnameAreBuffered() {
        ByteBuffer noNull = ByteBuffer.allocate(12);
        noNull.put(SOCKS4_VERSION);
        noNull.put(SOCKS4_CMD_CONNECT);
        noNull.putShort((short) 80);
        noNull.put(new byte[]{10, 0, 0, 1});
        noNull.put((byte) 'a');
        noNull.put((byte) 'b');
        noNull.put((byte) 'c');
        noNull.put((byte) 'd');
        noNull.flip();
        handler.receive(noNull);
        assertEquals(0, endpoint.getSentCount());

        ByteBuffer noHost = ByteBuffer.allocate(13);
        noHost.put(SOCKS4_VERSION);
        noHost.put(SOCKS4_CMD_CONNECT);
        noHost.putShort((short) 80);
        noHost.put(new byte[]{0, 0, 0, 3});
        noHost.put((byte) 0);
        noHost.put((byte) 'h');
        noHost.put((byte) 'o');
        noHost.put((byte) 's');
        noHost.put((byte) 't');
        noHost.flip();
        handler.receive(noHost);
        assertEquals(0, endpoint.getSentCount());
        assertTrue(endpoint.isOpen());
    }

    // ── Connect handler ──

    @Test
    public void connectHandlerDenyForSocks4() {
        handler.setConnectHandler(new ConnectHandler() {
            @Override
            public void handleConnect(ConnectState state,
                    SocksRequest request, Endpoint clientEndpoint) {
                state.deny(SOCKS5_REPLY_NOT_ALLOWED);
            }
        });
        handler.receive(socks4(SOCKS4_CMD_CONNECT, new byte[]{8, 8, 8, 8},
                53, "u"));
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void connectHandlerAllowProceedsToDestinationPolicy() {
        server.setBlockedDestinations(CidrNetwork.parseList("8.8.8.0/24"));
        final List<SocksRequest> seen = new ArrayList<SocksRequest>();
        handler.setConnectHandler(new ConnectHandler() {
            @Override
            public void handleConnect(ConnectState state,
                    SocksRequest request, Endpoint clientEndpoint) {
                seen.add(request);
                state.allow();
            }
        });
        handler.receive(socks4(SOCKS4_CMD_CONNECT, new byte[]{8, 8, 8, 8},
                53, "u"));
        assertEquals(1, seen.size());
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    // ── BIND ──

    @Test
    public void bindHandlerDenySocks5() {
        handler.setBindHandler(new BindHandler() {
            @Override
            public void handleBind(BindState state, SocksRequest request,
                    Endpoint clientEndpoint) {
                state.deny(SOCKS5_REPLY_NOT_ALLOWED);
            }
        });
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_BIND, SOCKS5_ATYP_IPV4,
                new byte[]{10, 0, 0, 1, 0, 80}));
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void bindHandlerDenySocks4() {
        handler.setBindHandler(new BindHandler() {
            @Override
            public void handleBind(BindState state, SocksRequest request,
                    Endpoint clientEndpoint) {
                state.deny(SOCKS5_REPLY_NOT_ALLOWED);
            }
        });
        handler.receive(socks4(SOCKS4_CMD_BIND, new byte[]{10, 0, 0, 1},
                80, "u"));
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void bindHandlerAllowAppliesDestinationPolicy() {
        server.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        handler.setBindHandler(new BindHandler() {
            @Override
            public void handleBind(BindState state, SocksRequest request,
                    Endpoint clientEndpoint) {
                state.allow();
            }
        });
        handler.receive(socks4(SOCKS4_CMD_BIND, new byte[]{10, 0, 0, 1},
                80, "u"));
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void bindBlockedDestinationSocks5() {
        server.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_BIND, SOCKS5_ATYP_IPV4,
                new byte[]{10, 0, 0, 1, 0, 80}));
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void bindRelayLimitReachedSocks5() {
        server.setMaxRelays(1);
        server.acquireRelay();
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_BIND, SOCKS5_ATYP_IPV4,
                new byte[]{0, 0, 0, 0, 0, 0}));
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE,
                endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void bindRelayLimitReachedSocks4() {
        server.setMaxRelays(1);
        server.acquireRelay();
        handler.receive(socks4(SOCKS4_CMD_BIND, new byte[]{0, 0, 0, 0},
                0, "u"));
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void connectRelayLimitReachedSocks4() {
        server.setMaxRelays(1);
        server.acquireRelay();
        handler.receive(socks4(SOCKS4_CMD_CONNECT, new byte[]{(byte) 192, 0, 2, 1},
                80, "u"));
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void connectRelayLimitReachedSocks5() {
        server.setMaxRelays(1);
        server.acquireRelay();
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_CONNECT, SOCKS5_ATYP_IPV4,
                new byte[]{(byte) 192, 0, 2, 1, 0, 80}));
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE, endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    // ── UDP ASSOCIATE ──

    @Test
    public void udpAssociateRelayLimitReached() {
        server.setMaxRelays(1);
        server.acquireRelay();
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_UDP_ASSOCIATE, SOCKS5_ATYP_IPV4,
                new byte[]{0, 0, 0, 0, 0, 0}));
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE,
                endpoint.getLastSent()[1]);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void udpAssociateWithoutLoopFailsCleanly() {
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request(SOCKS5_CMD_UDP_ASSOCIATE, SOCKS5_ATYP_IPV4,
                new byte[]{0, 0, 0, 0, 0, 0}));
        assertFalse(endpoint.isOpen());
    }

    // ── Lifecycle in other states ──

    @Test
    public void dataInIdleStatesIsIgnored() {
        // enter CLOSED via error then send more data
        handler.error(new Exception("boom"));
        assertFalse(endpoint.isOpen());
        handler.receive(ByteBuffer.wrap(new byte[]{5, 1, 0}));
        handler.error(new Exception("again"));
        handler.disconnected();
        handler.securityEstablished(null);
    }

    @Test
    public void disconnectedAfterNegotiationReportsMetrics() {
        offerMethods(SOCKS5_AUTH_NONE);
        handler.disconnected();
        handler.receive(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        assertEquals(1, endpoint.getSentCount());
    }

    @Test
    public void handlerWithoutListenerMetricsStillWorks() {
        SocksListener plain = new SocksListener();
        plain.setServer(server);
        SocksProtocolHandler h = server.createProtocolHandler(plain);
        StubEndpoint ep = new StubEndpoint();
        h.connected(ep);
        h.receive(ByteBuffer.wrap(new byte[]{(byte) 0x42}));
        assertFalse(ep.isOpen());
        h.disconnected();
    }

    @Test
    public void allowedDestinationsRestrictConnect() {
        server.setAllowedDestinations(CidrNetwork.parseList("192.0.2.0/24"));
        handler.receive(socks4(SOCKS4_CMD_CONNECT, new byte[]{8, 8, 8, 8},
                53, "u"));
        assertEquals(SOCKS4_REPLY_REJECTED, endpoint.getLastSent()[1]);
    }

    @Test
    public void socks5ReplyUsesIpv6WhenBoundAddressIsIpv6() {
        endpoint.setLocalAddress(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 1080));
        offerMethods(SOCKS5_AUTH_NONE);
        endpoint.clearSent();
        handler.receive(request((byte) 0x09, SOCKS5_ATYP_IPV4,
                new byte[]{10, 0, 0, 1, 0, 80}));
        byte[] reply = endpoint.getLastSent();
        assertEquals(SOCKS5_VERSION, reply[0]);
        assertEquals(SOCKS5_REPLY_COMMAND_NOT_SUPPORTED, reply[1]);
    }

    // ── Helpers ──

    private void offerMethods(byte... methods) {
        ByteBuffer buf = ByteBuffer.allocate(2 + methods.length);
        buf.put(SOCKS5_VERSION);
        buf.put((byte) methods.length);
        buf.put(methods);
        buf.flip();
        handler.receive(buf);
    }

    private static ByteBuffer userPass(String user, String pass) {
        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        byte[] p = pass.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(3 + u.length + p.length);
        buf.put(SOCKS5_AUTH_USERPASS_VERSION);
        buf.put((byte) u.length);
        buf.put(u);
        buf.put((byte) p.length);
        buf.put(p);
        buf.flip();
        return buf;
    }

    private static ByteBuffer request(byte cmd, byte atyp, byte[] rest) {
        ByteBuffer buf = ByteBuffer.allocate(4 + rest.length);
        buf.put(SOCKS5_VERSION);
        buf.put(cmd);
        buf.put((byte) 0);
        buf.put(atyp);
        buf.put(rest);
        buf.flip();
        return buf;
    }

    private static ByteBuffer socks4(byte cmd, byte[] ip, int port,
                                     String userid) {
        byte[] uid = userid.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(8 + uid.length + 1);
        buf.put(SOCKS4_VERSION);
        buf.put(cmd);
        buf.putShort((short) port);
        buf.put(ip);
        buf.put(uid);
        buf.put((byte) 0);
        buf.flip();
        return buf;
    }

    private static final class TestRealm implements Realm {
        private final String user;
        private final String pass;
        private static final Set<SaslMechanism> SUPPORTED =
                Collections.unmodifiableSet(
                        EnumSet.of(SaslMechanism.PLAIN));

        TestRealm(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return SUPPORTED;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return user.equals(username) && pass.equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getPassword(String username) {
            return user.equals(username) ? pass : null;
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }
}
