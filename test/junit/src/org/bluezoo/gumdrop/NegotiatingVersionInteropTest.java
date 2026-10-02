/*
 * NegotiatingVersionInteropTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Test;

/**
 * Version negotiation between peers with different version policies, run
 * in memory: a negotiating DTLS server against a DTLS 1.2 client (with and
 * without the HelloVerifyRequest cookie exchange), and a negotiating TLS
 * or DTLS client against a 1.2-only and a 1.3-only server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NegotiatingVersionInteropTest {

    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 5200);
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 5201);

    private static final class Peer implements ProtocolHandler {
        final StringBuilder received = new StringBuilder();
        final List<Exception> errors = new ArrayList<Exception>();
        boolean secure;

        @Override
        public void receive(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            received.append(new String(b, StandardCharsets.UTF_8));
        }

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            secure = true;
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    // ---- DTLS ----

    private static UdpEndpoint udp(Peer peer, UdpTransportFactory f, boolean client) {
        UdpEndpoint ep = new UdpEndpoint(peer);
        ep.setFactory(f);
        ep.setSecure(true);
        ep.setClientMode(client);
        if (client) {
            ep.setRemoteAddress(SERVER_ADDR);
        }
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
    }

    private static boolean deliver(UdpEndpoint from, UdpEndpoint to, InetSocketAddress source) {
        UdpEndpoint.PendingDatagram pending = from.pendingDatagrams.poll();
        if (pending == null) {
            return false;
        }
        byte[] bytes = new byte[pending.data.remaining()];
        pending.data.get(bytes);
        from.onPendingDatagramFullySent(pending);
        to.netReceive(ByteBuffer.wrap(bytes), source);
        return true;
    }

    private static void pumpUdp(UdpEndpoint client, UdpEndpoint server) {
        boolean progress = true;
        int guard = 0;
        while (progress && guard < 500) {
            boolean a = deliver(client, server, CLIENT_ADDR);
            boolean b = deliver(server, client, SERVER_ADDR);
            progress = a || b;
            guard++;
        }
    }

    private static void dtlsRoundTrip(DtlsVersion serverVersion, DtlsVersion clientVersion,
            boolean cookie) throws Exception {
        String label = serverVersion + "/" + clientVersion + "/" + cookie;
        TestCertificates.Identity identity = TestCertificates.ec256();
        UdpTransportFactory sf = new UdpTransportFactory();
        sf.setSecure(true);
        sf.setServerCredentials(identity.credentials());
        sf.setDtlsVersion(serverVersion);
        if (cookie) {
            byte[] secret = new byte[32];
            for (int i = 0; i < secret.length; i++) {
                secret[i] = (byte) (i + 1);
            }
            sf.setRequireCookie(true);
            sf.setCookieSecret(secret);
        }
        sf.start();
        UdpTransportFactory cf = new UdpTransportFactory();
        cf.setSecure(true);
        cf.setDtlsVersion(clientVersion);
        cf.setTrustManager(TestCertificates.trustAll());
        cf.start();
        Peer sp = new Peer();
        Peer cp = new Peer();
        UdpEndpoint server = udp(sp, sf, false);
        UdpEndpoint client = udp(cp, cf, true);
        client.startClientDtlsHandshake();
        pumpUdp(client, server);
        assertTrue(label + " c=" + cp.errors + " s=" + sp.errors, cp.secure);
        assertTrue(label, sp.secure);
        assertTrue(label, cp.errors.isEmpty());
        assertTrue(label, sp.errors.isEmpty());
        client.send(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
        pumpUdp(client, server);
        assertEquals(label, "hello", sp.received.toString());
    }

    @Test
    public void negotiatingDtlsServerAcceptsDtls12Client() throws Exception {
        dtlsRoundTrip(DtlsVersion.NEGOTIATE, DtlsVersion.DTLS_1_2, false);
    }

    @Test
    public void negotiatingDtlsServerAcceptsDtls12ClientWithCookieExchange() throws Exception {
        dtlsRoundTrip(DtlsVersion.NEGOTIATE, DtlsVersion.DTLS_1_2, true);
    }

    @Test
    public void negotiatingDtlsServerAcceptsDtls13Client() throws Exception {
        dtlsRoundTrip(DtlsVersion.NEGOTIATE, DtlsVersion.DTLS_1_3, false);
        dtlsRoundTrip(DtlsVersion.NEGOTIATE, DtlsVersion.DTLS_1_3, true);
    }

    @Test
    public void negotiatingDtlsClientAgainstDtls12OnlyServer() throws Exception {
        dtlsRoundTrip(DtlsVersion.DTLS_1_2, DtlsVersion.NEGOTIATE, false);
        dtlsRoundTrip(DtlsVersion.DTLS_1_2, DtlsVersion.NEGOTIATE, true);
    }

    @Test
    public void negotiatingDtlsClientAgainstDtls13OnlyServer() throws Exception {
        dtlsRoundTrip(DtlsVersion.DTLS_1_3, DtlsVersion.NEGOTIATE, false);
    }

    @Test
    public void negotiatingDtlsBothSides() throws Exception {
        dtlsRoundTrip(DtlsVersion.NEGOTIATE, DtlsVersion.NEGOTIATE, false);
    }

    // ---- TLS ----

    private static TcpEndpoint tcp(Peer h, HandshakeConfig c13, Tls12HandshakeConfig c12,
            TlsVersion policy, boolean client) throws IOException {
        TcpEndpoint ep = new TcpEndpoint(h, c13, c12, policy, false);
        ep.setClientMode(client);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
    }

    private static boolean move(TcpEndpoint from, TcpEndpoint to) throws IOException {
        ByteBuffer out = from.getNetOut();
        if (out == null || out.position() == 0) {
            return false;
        }
        out.flip();
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        out.clear();
        int off = 0;
        while (off < bytes.length) {
            ByteBuffer in = to.prepareNetInForRead();
            int n = Math.min(in.remaining(), bytes.length - off);
            in.put(bytes, off, n);
            in.flip();
            to.processInbound();
            off += n;
        }
        return true;
    }

    private static void pumpTcp(TcpEndpoint a, TcpEndpoint b) throws IOException {
        boolean moved = true;
        int guard = 0;
        while (moved && guard < 200) {
            boolean x = move(a, b);
            boolean y = move(b, a);
            moved = x || y;
            guard++;
        }
    }

    private static HandshakeConfig server13() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TestCertificates.ec256().credentials());
        return c;
    }

    private static HandshakeConfig client13() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.ec256().trustManager());
        c.setOfferTls12Fallback(true);
        return c;
    }

    private static Tls12HandshakeConfig server12() throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TestCertificates.ec256().credentials());
        return c;
    }

    private static Tls12HandshakeConfig client12() throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.ec256().trustManager());
        return c;
    }

    private static void tlsRoundTrip(HandshakeConfig s13, Tls12HandshakeConfig s12, TlsVersion sv)
            throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = tcp(cp, client13(), client12(), TlsVersion.NEGOTIATE, true);
        TcpEndpoint server = tcp(sp, s13, s12, sv, false);
        server.startTLS();
        client.startTLS();
        pumpTcp(client, server);
        assertTrue(sv + " c=" + cp.errors + " s=" + sp.errors, cp.secure);
        assertTrue(sv.toString(), sp.secure);
        assertTrue(sv.toString(), cp.errors.isEmpty());
        assertTrue(sv.toString(), sp.errors.isEmpty());
        client.send(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8)));
        pumpTcp(client, server);
        assertEquals("ping", sp.received.toString());
        server.send(ByteBuffer.wrap("pong".getBytes(StandardCharsets.UTF_8)));
        pumpTcp(client, server);
        assertEquals("pong", cp.received.toString());
        assertFalse(cp.received.length() == 0);
    }

    @Test
    public void negotiatingTlsClientAgainstTls12OnlyServer() throws Exception {
        tlsRoundTrip(null, server12(), TlsVersion.TLS_1_2);
    }

    @Test
    public void negotiatingTlsClientAgainstTls13OnlyServer() throws Exception {
        tlsRoundTrip(server13(), null, TlsVersion.TLS_1_3);
    }
}
