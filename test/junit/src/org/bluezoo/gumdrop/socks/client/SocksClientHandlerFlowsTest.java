/*
 * SocksClientHandlerFlowsTest.java
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

package org.bluezoo.gumdrop.socks.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.UdpTransportFactory;

import static org.junit.Assert.*;

/**
 * Handshake-flow tests for {@link SocksClientHandler}: authentication,
 * SOCKS4/4a, address-type variants, malformed and split replies, state
 * dependent callbacks, and a UDP ASSOCIATE association over loopback UDP.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksClientHandlerFlowsTest {

    /** Inner handler that records everything. */
    private static final class Inner implements ProtocolHandler {
        Endpoint connected;
        Exception error;
        boolean disconnected;
        boolean secured;
        final List<String> received = new ArrayList<String>();

        @Override
        public void connected(Endpoint endpoint) {
            connected = endpoint;
        }

        @Override
        public void receive(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            received.add(new String(b, StandardCharsets.ISO_8859_1));
        }

        @Override
        public void disconnected() {
            disconnected = true;
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            secured = true;
        }

        @Override
        public void error(Exception cause) {
            error = cause;
        }
    }

    private static final class UdpListener
            implements SocksClientHandler.UdpAssociateListener {
        final BlockingQueue<InetSocketAddress> associated =
                new LinkedBlockingQueue<InetSocketAddress>();
        final BlockingQueue<String> datagrams =
                new LinkedBlockingQueue<String>();
        final BlockingQueue<Exception> errors =
                new LinkedBlockingQueue<Exception>();

        @Override
        public void associated(InetSocketAddress relayAddress) {
            associated.add(relayAddress);
        }

        @Override
        public void receive(InetSocketAddress source, ByteBuffer payload) {
            byte[] b = new byte[payload.remaining()];
            payload.get(b);
            datagrams.add(source.getPort() + ":"
                    + new String(b, StandardCharsets.ISO_8859_1));
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private StubEndpoint endpoint;
    private Inner inner;

    @Before
    public void setUp() {
        endpoint = new StubEndpoint();
        inner = new Inner();
    }

    private static ByteBuffer bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) values[i];
        }
        return ByteBuffer.wrap(b);
    }

    private SocksClientHandler connect5(String host, SocksClientConfig cfg) {
        SocksClientHandler h = new SocksClientHandler(host, 8080, cfg, inner);
        h.connected(endpoint);
        return h;
    }

    // ── SOCKS5 method negotiation and authentication ──

    @Test
    public void credentialsOfferTwoMethodsAndAuthenticate() {
        SocksClientConfig cfg = new SocksClientConfig("bob", "pw");
        SocksClientHandler h = connect5("10.1.2.3", cfg);
        byte[] greeting = endpoint.getLastSent();
        assertEquals(4, greeting.length);
        assertEquals(2, greeting[1]);
        endpoint.clearSent();

        h.receive(bytes(5, 2));
        byte[] auth = endpoint.getLastSent();
        assertEquals(1, auth[0]);
        assertEquals(3, auth[1]);
        endpoint.clearSent();

        h.receive(bytes(1, 0));
        byte[] request = endpoint.getLastSent();
        assertEquals(10, request.length);
        assertEquals(1, request[3]);
        assertNull(inner.error);
    }

    @Test
    public void authFailureIsReported() {
        SocksClientHandler h = connect5("10.1.2.3",
                new SocksClientConfig("bob", "bad"));
        h.receive(bytes(5, 2));
        h.receive(bytes(1, 1));
        assertNotNull(inner.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void noAcceptableMethodIsReported() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        h.receive(bytes(5, 0xFF));
        assertNotNull(inner.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void unsupportedMethodIsReported() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        h.receive(bytes(5, 1));
        assertNotNull(inner.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void incompleteMethodSelectionWaits() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        endpoint.clearSent();
        h.receive(bytes(5));
        assertEquals(0, endpoint.getSentCount());
        h.receive(bytes(0));
        assertEquals(1, endpoint.getSentCount());
    }

    // ── SOCKS5 request address types ──

    @Test
    public void ipv6DestinationUsesIpv6AddressType() {
        SocksClientHandler h = connect5("::1", new SocksClientConfig());
        endpoint.clearSent();
        h.receive(bytes(5, 0));
        byte[] request = endpoint.getLastSent();
        assertEquals(22, request.length);
        assertEquals(4, request[3]);
    }

    @Test
    public void unresolvableNameUsesDomainAddressType() {
        SocksClientHandler h = connect5("no-such-host.invalid",
                new SocksClientConfig());
        endpoint.clearSent();
        h.receive(bytes(5, 0));
        byte[] request = endpoint.getLastSent();
        assertEquals(3, request[3]);
        assertEquals("no-such-host.invalid".length(), request[4]);
    }

    // ── SOCKS5 replies ──

    private void toReply(SocksClientHandler h) {
        h.receive(bytes(5, 0));
    }

    @Test
    public void connectReplyWithDomainAddressEstablishesTunnel() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        toReply(h);
        h.receive(bytes(5, 0, 0, 3, 3, 'a', 'b', 'c', 0, 80));
        assertSame(endpoint, inner.connected);
    }

    @Test
    public void connectReplyWithIpv6AddressEstablishesTunnel() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        toReply(h);
        byte[] reply = new byte[22];
        reply[0] = 5;
        reply[3] = 4;
        h.receive(ByteBuffer.wrap(reply));
        assertSame(endpoint, inner.connected);
    }

    @Test
    public void connectReplyUnknownAddressTypeIsRejected() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        toReply(h);
        h.receive(bytes(5, 0, 0, 9, 0, 0, 0, 0, 0, 0));
        assertNotNull(inner.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void connectReplyFailureIsReported() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        toReply(h);
        h.receive(bytes(5, 5, 0, 1, 0, 0, 0, 0, 0, 0));
        assertNotNull(inner.error);
        assertNull(inner.connected);
    }

    @Test
    public void partialRepliesAreBufferedAcrossReads() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        toReply(h);
        h.receive(bytes(5, 0));
        h.receive(bytes(0, 3));
        h.receive(bytes(2, 'a'));
        assertNull(inner.connected);
        h.receive(bytes('b', 0, 80));
        assertSame(endpoint, inner.connected);
    }

    @Test
    public void tunnelDataAndCallbacksReachInnerHandler() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        toReply(h);
        // Reply and first tunnel bytes in one read: the loop hands on the rest.
        h.receive(bytes(5, 0, 0, 1, 0, 0, 0, 0, 0, 0, 'h', 'i'));
        assertSame(endpoint, inner.connected);
        assertEquals(1, inner.received.size());
        assertEquals("hi", inner.received.get(0));
        h.receive(bytes('x'));
        assertEquals(2, inner.received.size());
        h.securityEstablished(null);
        assertTrue(inner.secured);
        IOException boom = new IOException("boom");
        h.error(boom);
        assertSame(boom, inner.error);
        h.disconnected();
        assertTrue(inner.disconnected);
    }

    @Test
    public void callbacksBeforeTunnelDoNotReachInnerHandler() {
        SocksClientHandler h = connect5("10.1.2.3", new SocksClientConfig());
        h.securityEstablished(null);
        assertFalse(inner.secured);
        h.disconnected();
        assertFalse(inner.disconnected);
        h.error(new IOException("early"));
        assertNotNull(inner.error);
        assertTrue(inner.error.getCause() != null);
    }

    // ── SOCKS4 / 4a ──

    private SocksClientConfig socks4() {
        return new SocksClientConfig().version(
                SocksClientConfig.Version.SOCKS4);
    }

    @Test
    public void socks4RequestCarriesIpAndUserid() {
        SocksClientConfig cfg = socks4().username("al");
        SocksClientHandler h = new SocksClientHandler("192.0.2.9", 25, cfg,
                inner);
        h.connected(endpoint);
        byte[] req = endpoint.getLastSent();
        assertEquals(4, req[0]);
        assertEquals(1, req[1]);
        assertEquals(192, req[4] & 0xFF);
        assertEquals('a', req[8]);
        assertEquals(0, req[req.length - 1]);
        h.receive(bytes(0, 0x5a, 0, 25, 192, 0, 2, 9));
        assertSame(endpoint, inner.connected);
    }

    @Test
    public void socks4aRequestCarriesHostname() {
        SocksClientHandler h = new SocksClientHandler("mail.invalid", 25,
                socks4(), inner);
        h.connected(endpoint);
        byte[] req = endpoint.getLastSent();
        assertEquals(1, req[7]);
        String tail = new String(req, 8, req.length - 8,
                StandardCharsets.ISO_8859_1);
        assertTrue(tail.endsWith("mail.invalid\0"));
    }

    @Test
    public void socks4ipv6UsesSocks4a() {
        SocksClientHandler h = new SocksClientHandler("::1", 25, socks4(),
                inner);
        h.connected(endpoint);
        byte[] req = endpoint.getLastSent();
        assertEquals(1, req[7]);
    }

    @Test
    public void socks4RejectionIsReported() {
        SocksClientHandler h = new SocksClientHandler("192.0.2.9", 25,
                socks4(), inner);
        h.connected(endpoint);
        h.receive(bytes(0, 0x5b, 0, 0, 0, 0, 0, 0));
        assertNotNull(inner.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void socks4ShortReplyWaits() {
        SocksClientHandler h = new SocksClientHandler("192.0.2.9", 25,
                socks4(), inner);
        h.connected(endpoint);
        h.receive(bytes(0, 0x5a, 0));
        assertNull(inner.connected);
        h.receive(bytes(25, 192, 0, 2, 9));
        assertSame(endpoint, inner.connected);
    }

    // ── BIND address-type variants ──

    private static final class Bind implements SocksClientHandler.BindListener {
        InetSocketAddress bound;

        @Override
        public void bound(InetSocketAddress boundAddress) {
            bound = boundAddress;
        }
    }

    @Test
    public void bindReplyWithIpv6AddressIsReported() {
        Bind bind = new Bind();
        SocksClientHandler h = new SocksClientHandler("::", 0,
                new SocksClientConfig(), bind, inner);
        h.connected(endpoint);
        h.receive(bytes(5, 0));
        byte[] reply = new byte[22];
        reply[0] = 5;
        reply[3] = 4;
        reply[20] = 0x1f;
        reply[21] = (byte) 0x90;
        h.receive(ByteBuffer.wrap(reply));
        assertNotNull(bind.bound);
        assertEquals(8080, bind.bound.getPort());
    }

    @Test
    public void bindReplyShortIpv6Waits() {
        Bind bind = new Bind();
        SocksClientHandler h = new SocksClientHandler("::", 0,
                new SocksClientConfig(), bind, inner);
        h.connected(endpoint);
        h.receive(bytes(5, 0));
        h.receive(bytes(5, 0, 0, 4, 0, 0));
        assertNull(bind.bound);
        h.receive(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 80));
        assertNotNull(bind.bound);
    }

    @Test
    public void bindReplyUnknownAddressTypeIsRejected() {
        Bind bind = new Bind();
        SocksClientHandler h = new SocksClientHandler("::", 0,
                new SocksClientConfig(), bind, inner);
        h.connected(endpoint);
        h.receive(bytes(5, 0));
        h.receive(bytes(5, 0, 0, 7, 0, 0, 0, 0, 0, 0));
        assertNotNull(inner.error);
    }

    @Test
    public void bindReplyShortDomainWaits() {
        Bind bind = new Bind();
        SocksClientHandler h = new SocksClientHandler("::", 0,
                new SocksClientConfig(), bind, inner);
        h.connected(endpoint);
        h.receive(bytes(5, 0));
        h.receive(bytes(5, 0, 0, 3));
        h.receive(bytes(5, 0, 0, 3, 5, 'a'));
        assertNull(bind.bound);
        h.receive(bytes(5, 0, 0, 3, 1, 'a', 0, 80));
        assertNotNull(bind.bound);
    }

    @Test
    public void bindSocks4ShortAndSecondReply() {
        Bind bind = new Bind();
        SocksClientHandler h = new SocksClientHandler("192.0.2.9", 0, socks4(),
                bind, inner);
        h.connected(endpoint);
        byte[] req = endpoint.getLastSent();
        assertEquals(2, req[1]);
        h.receive(bytes(0, 0x5a, 0x1f));
        assertNull(bind.bound);
        h.receive(bytes(0x90, 10, 0, 0, 1));
        assertEquals(8080, bind.bound.getPort());
        h.receive(bytes(0, 0x5a, 0, 0, 1, 2, 3, 4));
        assertSame(endpoint, inner.connected);
    }

    // ── UDP ASSOCIATE failures that need no relay socket ──

    private void shortUdpReply(ByteBuffer reply) {
        UdpListener listener = new UdpListener();
        SocksClientHandler h = new SocksClientHandler(new SocksClientConfig(),
                new UdpTransportFactory(), listener);
        h.connected(new StubEndpoint());
        h.receive(bytes(5, 0));
        h.receive(reply);
        assertTrue(listener.errors.isEmpty());
        assertTrue(listener.associated.isEmpty());
    }

    @Test
    public void udpAssociateShortRepliesWait() {
        shortUdpReply(bytes(5, 0, 0, 1, 1, 2));
        shortUdpReply(bytes(5, 0, 0, 4, 1, 2));
        shortUdpReply(bytes(5, 0, 0, 3));
        shortUdpReply(bytes(5, 0, 0, 3, 9, 'a'));
        shortUdpReply(bytes(5, 0));
    }

    @Test
    public void udpAssociateUnknownAddressTypeIsRejected() {
        UdpListener listener = new UdpListener();
        SocksClientHandler h = new SocksClientHandler(new SocksClientConfig(),
                new UdpTransportFactory(), listener);
        h.connected(endpoint);
        h.receive(bytes(5, 0));
        h.receive(bytes(5, 0, 0, 9, 0, 0, 0, 0, 0, 0));
        assertEquals(1, listener.errors.size());
    }

    @Test
    public void udpAssociateStageErrorGoesToUdpListener() {
        UdpListener listener = new UdpListener();
        SocksClientHandler h = new SocksClientHandler(new SocksClientConfig(),
                new UdpTransportFactory(), listener);
        h.connected(endpoint);
        h.error(new IOException("early"));
        assertEquals(1, listener.errors.size());
    }
}
