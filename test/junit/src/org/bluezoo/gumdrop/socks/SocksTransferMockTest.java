/*
 * SocksTransferMockTest.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.socks.server.SocksServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.StubSocketChannel;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.util.CidrNetwork;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SocksConstants.*;

/**
 * Drives {@link SocksProtocolHandler} through CONNECT relaying (SOCKS4, 4a
 * and 5), BIND and UDP ASSOCIATE with the resolver, the upstream connector,
 * the BIND listener and the UDP ports all supplied by a {@link
 * MockSocksTransport}: no socket, resolver, loop or timer is involved. The
 * test completes each recorded operation and fires the captured timers by
 * hand.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksTransferMockTest {

    /** The client's control connection, capturing timers. */
    private static final class Control extends StubEndpoint {
        final List<Runnable> timers = new ArrayList<Runnable>();
        SelectorLoop loop;

        @Override
        public SelectorLoop getSelectorLoop() {
            return loop;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers.add(callback);
            return super.scheduleTimer(delayMs, callback);
        }
    }

    private SocksServer server;
    private SocksListener listener;
    private SocksProtocolHandler handler;
    private Control control;
    private MockSocksTransport transport;
    private Gumdrop gumdrop;

    @Before
    public void setUp() {
        gumdrop = TestGumdrop.create();
        server = new SocksServer();
        listener = new SocksListener();
        listener.setServer(server);
        gumdrop.telemetryConfig(telemetryConfig());
        listener.start(gumdrop);
        newSession(gumdrop.nextWorkerLoop());
    }

    /** Telemetry configuration for the listener; subclasses enable metrics. */
    TelemetryConfig telemetryConfig() {
        return new TelemetryConfig();
    }

    private void newSession(SelectorLoop loop) {
        handler = server.createProtocolHandler(listener);
        transport = new MockSocksTransport();
        handler.setTransport(transport);
        control = new Control();
        control.loop = loop;
        handler.connected(control);
    }

    private void feed(byte[] data) {
        handler.receive(ByteBuffer.wrap(data));
    }

    private byte[] lastReply() {
        return control.getLastSent();
    }

    private void greet5() {
        feed(new byte[] {5, 1, 0});
        byte[] r = lastReply();
        assertEquals(SOCKS5_AUTH_NONE, r[1]);
    }

    private static byte[] request5(byte cmd, byte[] addr4, int port) {
        return new byte[] {5, cmd, 0, 1, addr4[0], addr4[1], addr4[2],
            addr4[3], (byte) (port >> 8), (byte) port};
    }

    private static byte[] domain5(byte cmd, String host, int port) {
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        byte[] req = new byte[5 + name.length + 2];
        req[0] = 5;
        req[1] = cmd;
        req[3] = SOCKS5_ATYP_DOMAINNAME;
        req[4] = (byte) name.length;
        System.arraycopy(name, 0, req, 5, name.length);
        req[5 + name.length] = (byte) (port >> 8);
        req[6 + name.length] = (byte) port;
        return req;
    }

    private static byte[] request4(byte cmd, byte[] addr4, int port) {
        return new byte[] {4, cmd, (byte) (port >> 8), (byte) port,
            addr4[0], addr4[1], addr4[2], addr4[3], 'u', 0};
    }

    private static byte[] request4a(String host, int port) {
        byte[] head = new byte[] {4, 1, (byte) (port >> 8), (byte) port,
            0, 0, 0, 1, 'u', 0};
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        byte[] req = new byte[head.length + name.length + 1];
        System.arraycopy(head, 0, req, 0, head.length);
        System.arraycopy(name, 0, req, head.length, name.length);
        return req;
    }

    private static byte[] ip(int a, int b, int c, int d) {
        return new byte[] {(byte) a, (byte) b, (byte) c, (byte) d};
    }

    private static int portOf(byte[] reply) {
        int n = reply.length;
        return ((reply[n - 2] & 0xFF) << 8) | (reply[n - 1] & 0xFF);
    }

    private static InetAddress addr(int a, int b, int c, int d) throws IOException {
        return InetAddress.getByAddress(ip(a, b, c, d));
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }

    private StubSocketChannel peerChannel(String host) {
        return new StubSocketChannel(new InetSocketAddress("127.0.0.1", 45000),
                new InetSocketAddress(host, 41000));
    }

    // ── CONNECT ──

    @Test
    public void socks5ConnectRelaysBothWays() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_CONNECT, ip(192, 0, 2, 10), 8080));
        assertEquals(1, transport.connects.size());
        MockSocksTransport.Connect c = transport.lastConnect();
        assertEquals("192.0.2.10", c.address.getHostAddress());
        assertEquals(8080, c.port);
        StubEndpoint upstream = new StubEndpoint();
        c.handler.connected(upstream);
        assertEquals(SOCKS5_REPLY_SUCCEEDED, lastReply()[1]);
        assertEquals(1, server.getActiveRelayCount());

        feed("hello".getBytes(StandardCharsets.US_ASCII));
        assertEquals("hello", text(upstream.getLastSent()));
        c.handler.receive(ByteBuffer.wrap("world".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("world", text(lastReply()));

        c.handler.securityEstablished(null);
        handler.disconnected();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void upstreamDisconnectEndsTheRelay() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_CONNECT, ip(192, 0, 2, 10), 80));
        MockSocksTransport.Connect c = transport.lastConnect();
        StubEndpoint upstream = new StubEndpoint();
        c.handler.connected(upstream);
        c.handler.disconnected();
        assertEquals(0, server.getActiveRelayCount());
        assertFalse(control.isOpen());
    }

    @Test
    public void socks4ConnectRelays() throws Exception {
        feed(request4(SOCKS4_CMD_CONNECT, ip(192, 0, 2, 11), 25));
        MockSocksTransport.Connect c = transport.lastConnect();
        assertEquals(25, c.port);
        StubEndpoint upstream = new StubEndpoint();
        c.handler.connected(upstream);
        assertEquals(SOCKS4_REPLY_GRANTED, lastReply()[1]);
        feed(new byte[] {'x'});
        assertEquals('x', upstream.getLastSent()[0]);
        c.handler.receive(ByteBuffer.wrap(new byte[] {'y'}));
        assertEquals('y', lastReply()[0]);
    }

    @Test
    public void socks4aHostnameIsResolvedBeforeConnecting() throws Exception {
        feed(request4a("host.example", 443));
        assertEquals(0, transport.connects.size());
        MockSocksTransport.Resolve r = transport.lastResolve();
        assertEquals("host.example", r.host);
        List<InetAddress> found = new ArrayList<InetAddress>();
        found.add(addr(192, 0, 2, 20));
        found.add(addr(192, 0, 2, 21));
        r.callback.onResolved(found);
        MockSocksTransport.Connect c = transport.lastConnect();
        assertEquals("192.0.2.20", c.address.getHostAddress());
        assertEquals(443, c.port);
        c.handler.connected(new StubEndpoint());
        assertEquals(SOCKS4_REPLY_GRANTED, lastReply()[1]);
    }

    @Test
    public void socks5DomainIsResolvedBeforeConnecting() throws Exception {
        greet5();
        feed(domain5(SOCKS5_CMD_CONNECT, "host.example", 443));
        MockSocksTransport.Resolve r = transport.lastResolve();
        List<InetAddress> found = new ArrayList<InetAddress>();
        found.add(addr(192, 0, 2, 30));
        r.callback.onResolved(found);
        assertEquals("192.0.2.30", transport.lastConnect().address.getHostAddress());
    }

    @Test
    public void socks5DomainResolvedToBlockedAddressIsRejected() throws Exception {
        server.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        greet5();
        feed(domain5(SOCKS5_CMD_CONNECT, "internal.example", 80));
        List<InetAddress> found = new ArrayList<InetAddress>();
        found.add(addr(192, 0, 2, 1));
        found.add(addr(10, 1, 2, 3));
        transport.lastResolve().callback.onResolved(found);
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, lastReply()[1]);
        assertFalse(control.isOpen());
        assertTrue("nothing connected", transport.connects.isEmpty());
    }

    @Test
    public void socks4aResolvedToBlockedAddressIsRejected() throws Exception {
        server.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        feed(request4a("internal.example", 80));
        List<InetAddress> found = new ArrayList<InetAddress>();
        found.add(addr(10, 1, 2, 3));
        transport.lastResolve().callback.onResolved(found);
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
        assertFalse(control.isOpen());
    }

    @Test
    public void socks5ResolutionFailureReportsHostUnreachable() throws Exception {
        greet5();
        feed(domain5(SOCKS5_CMD_CONNECT, "nowhere.example", 80));
        transport.lastResolve().callback.onError("NXDOMAIN");
        assertEquals(SOCKS5_REPLY_HOST_UNREACHABLE, lastReply()[1]);
        assertFalse(control.isOpen());
    }

    @Test
    public void socks4aResolutionFailureIsRejected() throws Exception {
        feed(request4a("nowhere.example", 80));
        transport.lastResolve().callback.onError("NXDOMAIN");
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
    }

    @Test
    public void nameRequestWithoutALoopIsRefused() throws Exception {
        newSession(null);
        greet5();
        feed(domain5(SOCKS5_CMD_CONNECT, "host.example", 80));
        assertEquals(SOCKS5_REPLY_HOST_UNREACHABLE, lastReply()[1]);
        newSession(null);
        feed(request4a("host.example", 80));
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
        assertTrue(transport.resolves.isEmpty());
    }

    @Test
    public void connectFailureBeforeEstablishedReportsRefused() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_CONNECT, ip(192, 0, 2, 10), 80));
        transport.lastConnect().handler.error(new IOException("refused"));
        assertEquals(SOCKS5_REPLY_CONNECTION_REFUSED, lastReply()[1]);
        assertEquals(0, server.getActiveRelayCount());
        assertFalse(control.isOpen());
    }

    @Test
    public void socks4ConnectFailureIsRejected() throws Exception {
        feed(request4(SOCKS4_CMD_CONNECT, ip(192, 0, 2, 11), 25));
        transport.lastConnect().handler.error(new IOException("refused"));
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void errorAfterEstablishedEndsTheRelay() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_CONNECT, ip(192, 0, 2, 10), 80));
        MockSocksTransport.Connect c = transport.lastConnect();
        c.handler.connected(new StubEndpoint());
        c.handler.error(new IOException("reset"));
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void connectThatCannotStartReportsGeneralFailure() throws Exception {
        transport.failConnect(new IOException("no route"));
        greet5();
        feed(request5(SOCKS5_CMD_CONNECT, ip(192, 0, 2, 10), 80));
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE, lastReply()[1]);
        assertEquals(0, server.getActiveRelayCount());
        assertFalse(control.isOpen());
    }

    @Test
    public void socks4ConnectThatCannotStartIsRejected() throws Exception {
        transport.failConnect(new IOException("no route"));
        feed(request4(SOCKS4_CMD_CONNECT, ip(192, 0, 2, 11), 25));
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
    }

    // ── BIND ──

    @Test
    public void bindAcceptsPeerAndRelays() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_BIND, new byte[4], 0));
        byte[] first = lastReply();
        assertEquals(SOCKS5_REPLY_SUCCEEDED, first[1]);
        MockSocksTransport.MockBindListener l = transport.lastListener();
        assertEquals(l.address.getPort(), portOf(first));
        l.accept(peerChannel("127.0.0.1"));
        byte[] second = lastReply();
        assertEquals(SOCKS5_REPLY_SUCCEEDED, second[1]);
        assertEquals("peer endpoint adopted", 1, transport.peers.size());
        StubEndpoint peer = transport.peers.get(0);

        transport.peerHandlers.get(0).receive(ByteBuffer.wrap(new byte[] {'p'}));
        assertEquals('p', lastReply()[0]);
        feed(new byte[] {'c'});
        assertEquals('c', peer.getLastSent()[0]);

        transport.peerHandlers.get(0).securityEstablished(null);
        transport.peerHandlers.get(0).disconnected();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void bindPeerErrorEndsTheRelay() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_BIND, new byte[4], 0));
        transport.lastListener().accept(peerChannel("127.0.0.1"));
        transport.peerHandlers.get(0).error(new IOException("reset"));
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void bindFromUnexpectedPeerIsRejected() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_BIND, ip(192, 0, 2, 7), 0));
        transport.lastListener().accept(peerChannel("127.0.0.1"));
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, lastReply()[1]);
        assertFalse(control.isOpen());
        assertTrue(transport.peers.isEmpty());
    }

    @Test
    public void bindFromBlockedPeerIsRejected() throws Exception {
        server.setBlockedDestinations(CidrNetwork.parseList("127.0.0.0/8"));
        greet5();
        feed(request5(SOCKS5_CMD_BIND, new byte[4], 0));
        transport.lastListener().accept(peerChannel("127.0.0.1"));
        assertEquals(SOCKS5_REPLY_NOT_ALLOWED, lastReply()[1]);
    }

    @Test
    public void socks4BindAcceptsPeer() throws Exception {
        feed(request4(SOCKS4_CMD_BIND, new byte[4], 0));
        byte[] first = lastReply();
        assertEquals(SOCKS4_REPLY_GRANTED, first[1]);
        int port = ((first[2] & 0xFF) << 8) | (first[3] & 0xFF);
        assertEquals(transport.lastListener().address.getPort(), port);
        transport.lastListener().accept(peerChannel("127.0.0.1"));
        byte[] second = lastReply();
        assertEquals(SOCKS4_REPLY_GRANTED, second[1]);
        assertEquals(1, transport.peers.size());
    }

    @Test
    public void bindTimeoutReportsTtlExpired() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_BIND, new byte[4], 0));
        assertFalse(control.timers.isEmpty());
        control.timers.get(0).run();
        assertEquals(SOCKS5_REPLY_TTL_EXPIRED, lastReply()[1]);
        assertTrue(transport.lastListener().closed);
    }

    @Test
    public void socks4BindTimeoutIsRejected() throws Exception {
        feed(request4(SOCKS4_CMD_BIND, new byte[4], 0));
        control.timers.get(0).run();
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
    }

    @Test
    public void bindListenFailureReportsGeneralFailure() throws Exception {
        transport.failListen(new IOException("no ports"));
        greet5();
        feed(request5(SOCKS5_CMD_BIND, new byte[4], 0));
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE, lastReply()[1]);
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void socks4BindListenFailureIsRejected() throws Exception {
        transport.failListen(new IOException("no ports"));
        feed(request4(SOCKS4_CMD_BIND, new byte[4], 0));
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
    }

    @Test
    public void bindPeerThatCannotBeAdoptedReportsGeneralFailure() throws Exception {
        transport.failAdopt(new IOException("channel lost"));
        greet5();
        feed(request5(SOCKS5_CMD_BIND, new byte[4], 0));
        transport.lastListener().accept(peerChannel("127.0.0.1"));
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE, lastReply()[1]);
        assertFalse(control.isOpen());
    }

    @Test
    public void socks4BindPeerThatCannotBeAdoptedIsRejected() throws Exception {
        transport.failAdopt(new IOException("channel lost"));
        feed(request4(SOCKS4_CMD_BIND, new byte[4], 0));
        transport.lastListener().accept(peerChannel("127.0.0.1"));
        assertEquals(SOCKS4_REPLY_REJECTED, lastReply()[1]);
    }

    @Test
    public void controlDisconnectDuringBindReleasesTheListener() throws Exception {
        greet5();
        feed(request5(SOCKS5_CMD_BIND, new byte[4], 0));
        MockSocksTransport.MockBindListener l = transport.lastListener();
        handler.disconnected();
        assertTrue(l.closed);
        assertEquals(0, server.getActiveRelayCount());
    }

    // ── UDP ASSOCIATE ──

    private static byte[] udpHeader(byte frag, byte[] addr4, int port, byte[] payload) {
        byte[] d = new byte[10 + payload.length];
        d[2] = frag;
        d[3] = SOCKS5_ATYP_IPV4;
        System.arraycopy(addr4, 0, d, 4, 4);
        d[8] = (byte) (port >> 8);
        d[9] = (byte) port;
        System.arraycopy(payload, 0, d, 10, payload.length);
        return d;
    }

    private static byte[] udpDomain(String host, int port, byte[] payload) {
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        byte[] d = new byte[7 + name.length + payload.length];
        d[3] = SOCKS5_ATYP_DOMAINNAME;
        d[4] = (byte) name.length;
        System.arraycopy(name, 0, d, 5, name.length);
        d[5 + name.length] = (byte) (port >> 8);
        d[6 + name.length] = (byte) port;
        System.arraycopy(payload, 0, d, 7 + name.length, payload.length);
        return d;
    }

    private InetSocketAddress clientSource() {
        return new InetSocketAddress("127.0.0.1", 40000);
    }

    private void associate() {
        control.setRemoteAddress(clientSource());
        greet5();
        feed(request5(SOCKS5_CMD_UDP_ASSOCIATE, new byte[4], 0));
        assertEquals(SOCKS5_REPLY_SUCCEEDED, lastReply()[1]);
        assertEquals(2, transport.udpPorts.size());
        assertEquals(transport.clientPort().local.getPort(), portOf(lastReply()));
    }

    @Test
    public void udpDatagramIsForwardedAndAnswered() throws Exception {
        associate();
        assertEquals(1, server.getActiveRelayCount());
        byte[] good = udpHeader((byte) 0, ip(192, 0, 2, 50), 5353, new byte[] {'g', 'o'});
        transport.clientPort().deliver(good, clientSource());
        assertEquals(1, transport.upstreamPort().sent.size());
        MockSocksTransport.Datagram out = transport.upstreamPort().sent.get(0);
        assertEquals("go", text(out.bytes));
        assertEquals("192.0.2.50", out.destination.getAddress().getHostAddress());
        assertEquals(5353, out.destination.getPort());

        transport.upstreamPort().deliver(new byte[] {'r'},
                new InetSocketAddress("192.0.2.50", 5353));
        assertEquals(1, transport.clientPort().sent.size());
        MockSocksTransport.Datagram back = transport.clientPort().sent.get(0);
        assertEquals(11, back.bytes.length);
        assertEquals('r', back.bytes[10]);
        assertEquals(clientSource(), back.destination);
    }

    @Test
    public void udpFragmentedBlockedEmptyAndForeignDatagramsAreDropped() throws Exception {
        server.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        associate();
        transport.clientPort().deliver(
                udpHeader((byte) 1, ip(192, 0, 2, 50), 9, new byte[] {'f'}), clientSource());
        transport.clientPort().deliver(
                udpHeader((byte) 0, ip(10, 0, 0, 1), 9, new byte[] {'b'}), clientSource());
        transport.clientPort().deliver(
                udpHeader((byte) 0, ip(192, 0, 2, 50), 9, new byte[0]), clientSource());
        transport.clientPort().deliver(
                udpHeader((byte) 0, ip(192, 0, 2, 50), 9, new byte[] {'z'}),
                new InetSocketAddress("127.0.0.2", 40000));
        assertTrue(transport.upstreamPort().sent.isEmpty());
    }

    @Test
    public void udpUpstreamBeforeAnyClientDatagramIsDropped() throws Exception {
        associate();
        transport.upstreamPort().deliver(new byte[] {'r'},
                new InetSocketAddress("192.0.2.50", 5353));
        assertTrue(transport.clientPort().sent.isEmpty());
    }

    @Test
    public void udpDomainDestinationIsResolvedThenForwarded() throws Exception {
        associate();
        transport.clientPort().deliver(
                udpDomain("host.example", 5353, new byte[] {'n'}), clientSource());
        MockSocksTransport.Resolve r = transport.lastResolve();
        assertEquals("host.example", r.host);
        List<InetAddress> found = new ArrayList<InetAddress>();
        found.add(addr(192, 0, 2, 60));
        r.callback.onResolved(found);
        assertEquals(1, transport.upstreamPort().sent.size());
        assertEquals("192.0.2.60", transport.upstreamPort().sent.get(0)
                .destination.getAddress().getHostAddress());
    }

    @Test
    public void udpDomainResolvedToBlockedAddressIsDropped() throws Exception {
        server.setBlockedDestinations(CidrNetwork.parseList("10.0.0.0/8"));
        associate();
        transport.clientPort().deliver(
                udpDomain("internal.example", 53, new byte[] {'n'}), clientSource());
        List<InetAddress> found = new ArrayList<InetAddress>();
        found.add(addr(10, 0, 0, 5));
        transport.lastResolve().callback.onResolved(found);
        assertTrue(transport.upstreamPort().sent.isEmpty());
    }

    @Test
    public void udpDomainResolutionFailureIsDropped() throws Exception {
        associate();
        transport.clientPort().deliver(
                udpDomain("nowhere.example", 53, new byte[] {'n'}), clientSource());
        transport.lastResolve().callback.onError("NXDOMAIN");
        assertTrue(transport.upstreamPort().sent.isEmpty());
    }

    @Test
    public void udpIdleTimerClosesTheRelay() throws Exception {
        associate();
        assertFalse(control.timers.isEmpty());
        control.timers.get(control.timers.size() - 1).run();
        assertEquals(0, server.getActiveRelayCount());
        assertFalse(transport.clientPort().open);
        assertFalse(transport.upstreamPort().open);
        // a datagram after close is ignored
        transport.clientPort().deliver(
                udpHeader((byte) 0, ip(192, 0, 2, 50), 9, new byte[] {'z'}), clientSource());
        assertTrue(transport.upstreamPort().sent.isEmpty());
    }

    @Test
    public void controlDisconnectClosesTheUdpRelay() throws Exception {
        associate();
        handler.disconnected();
        assertEquals(0, server.getActiveRelayCount());
        assertFalse(transport.clientPort().open);
    }

    @Test
    public void udpPortErrorAndDisconnectCloseTheRelay() throws Exception {
        associate();
        transport.clientPort().handler.error(new IOException("boom"));
        assertEquals(0, server.getActiveRelayCount());
        // further events on a closed relay are ignored
        transport.clientPort().handler.disconnected();
        transport.upstreamPort().handler.disconnected();
        transport.upstreamPort().handler.error(new IOException("again"));
        transport.clientPort().handler.securityEstablished(null);
        transport.clientPort().handler.connected(null);
        transport.upstreamPort().handler.connected(null);
        transport.upstreamPort().handler.securityEstablished(null);
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void upstreamPortDisconnectClosesTheRelay() throws Exception {
        associate();
        transport.upstreamPort().handler.disconnected();
        assertEquals(0, server.getActiveRelayCount());
    }

    @Test
    public void udpPortThatCannotBeOpenedReportsGeneralFailure() throws Exception {
        transport.failUdp(new IOException("no ports"));
        control.setRemoteAddress(clientSource());
        greet5();
        feed(request5(SOCKS5_CMD_UDP_ASSOCIATE, new byte[4], 0));
        assertEquals(SOCKS5_REPLY_GENERAL_FAILURE, lastReply()[1]);
        assertEquals(0, server.getActiveRelayCount());
        assertFalse(control.isOpen());
    }

    @Test
    public void udpAssociateWithExplicitClientAddressChecksThatSource() throws Exception {
        control.setRemoteAddress(clientSource());
        greet5();
        feed(request5(SOCKS5_CMD_UDP_ASSOCIATE, ip(127, 0, 0, 9), 0));
        transport.clientPort().deliver(
                udpHeader((byte) 0, ip(192, 0, 2, 50), 9, new byte[] {'a'}), clientSource());
        assertTrue("source differs from the announced client address",
                transport.upstreamPort().sent.isEmpty());
        transport.clientPort().deliver(
                udpHeader((byte) 0, ip(192, 0, 2, 50), 9, new byte[] {'a'}),
                new InetSocketAddress("127.0.0.9", 1234));
        assertEquals(1, transport.upstreamPort().sent.size());
    }

    @Test
    public void bytesOfManyDatagramsAreCounted() throws Exception {
        associate();
        byte[] payload = new byte[300];
        Arrays.fill(payload, (byte) 7);
        transport.clientPort().deliver(
                udpHeader((byte) 0, ip(192, 0, 2, 50), 9, payload), clientSource());
        assertEquals(300, transport.upstreamPort().sent.get(0).bytes.length);
    }
}
