/*
 * TcpEndpointTlsPairTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.ClientAuthPolicy;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12CipherSuite;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Test;

/**
 * Drives two channel-less {@link TcpEndpoint}s against each other through
 * their network buffers, so complete TLS 1.3, TLS 1.2 and version-negotiated
 * handshakes, application data exchange and orderly close run on the real
 * record states with no sockets and no threads (the handshake runs inline
 * because there is no live Gumdrop crypto pool).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TcpEndpointTlsPairTest {

    private static final class Peer implements ProtocolHandler {
        final StringBuilder received = new StringBuilder();
        final List<String> events = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        SecurityInfo security;
        /** When positive, consume at most this many bytes per receive and leave the rest. */
        int consumeAtMost;

        @Override
        public void receive(ByteBuffer data) {
            int n = data.remaining();
            if (consumeAtMost > 0 && n > consumeAtMost) {
                n = consumeAtMost;
            }
            byte[] b = new byte[n];
            data.get(b);
            received.append(new String(b, StandardCharsets.UTF_8));
        }

        @Override
        public void connected(Endpoint endpoint) {
            events.add("connected");
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            security = info;
            events.add("secure");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static TcpEndpoint endpoint(Peer h, HandshakeConfig c13, Tls12HandshakeConfig c12,
            TlsVersion policy, boolean client) throws IOException {
        TcpEndpoint ep = new TcpEndpoint(h, c13, c12, policy, false);
        ep.setClientMode(client);
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        return ep;
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

    /** Moves everything queued in {@code from}'s netOut into {@code to}. */
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

    private static void pump(TcpEndpoint a, TcpEndpoint b) throws IOException {
        boolean moved = true;
        int guard = 0;
        while (moved && guard < 200) {
            boolean x = move(a, b);
            boolean y = move(b, a);
            moved = x || y;
            guard++;
        }
    }

    private static void handshake(TcpEndpoint client, TcpEndpoint server) throws IOException {
        server.startTLS();
        client.startTLS();
        pump(client, server);
    }

    private static void exchange(Peer cp, TcpEndpoint client, Peer sp, TcpEndpoint server)
            throws IOException {
        client.send(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("ping", sp.received.toString());
        server.send(ByteBuffer.wrap("pong".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("pong", cp.received.toString());
    }

    @Test
    public void tls13HandshakeExchangeAndClose() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        handshake(client, server);
        assertTrue(cp.events.contains("secure"));
        assertTrue(sp.events.contains("secure"));
        assertTrue(client.isSecure());
        SecurityInfo info = client.getSecurityInfo();
        assertNotNull(info.getProtocol());
        assertNotNull(info.getCipherSuite());
        info.getPeerCertificates();
        info.getLocalCertificates();
        info.getApplicationProtocol();
        info.isSessionResumed();
        info.getKeySize();
        assertNotNull(server.getSecurityInfo().toString());
        exchange(cp, client, sp, server);
        client.close();
        pump(client, server);
        assertTrue(sp.events.contains("disconnected"));
        assertTrue(cp.errors.isEmpty());
        assertTrue(sp.errors.isEmpty());
    }

    @Test
    public void tls13AppDataBeforeHandshakeIsBufferedAndFlushed() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        server.startTLS();
        client.startTLS();
        client.send(ByteBuffer.wrap("early".getBytes(StandardCharsets.UTF_8)));
        client.send(ByteBuffer.wrap("-more".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("early-more", sp.received.toString());
        server.close();
        pump(client, server);
    }

    @Test
    public void tls13CloseBeforeHandshakeCompletes() throws Exception {
        Peer cp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        client.startTLS();
        client.send(ByteBuffer.wrap("never".getBytes(StandardCharsets.UTF_8)));
        client.close();
        assertTrue(client.isClosing());
        client.send(ByteBuffer.wrap("dropped".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void tls13UntrustedServerFailsHandshake() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.trustNone());
        TcpEndpoint client = endpoint(cp, c, null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        handshake(client, server);
        assertFalse(cp.events.contains("secure"));
        assertFalse(cp.errors.isEmpty());
    }

    @Test
    public void tls12HandshakeExchangeAndClose() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, null, client12(), TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, null, server12(), TlsVersion.TLS_1_2, false);
        handshake(client, server);
        assertTrue(cp.events.contains("secure"));
        assertTrue(sp.events.contains("secure"));
        SecurityInfo info = client.getSecurityInfo();
        assertNotNull(info.getProtocol());
        info.getCipherSuite();
        info.getPeerCertificates();
        info.getLocalCertificates();
        info.getKeySize();
        assertNotNull(server.getSecurityInfo().toString());
        exchange(cp, client, sp, server);
        server.close();
        pump(client, server);
        assertTrue(cp.events.contains("disconnected"));
    }

    @Test
    public void tls12AppDataBeforeHandshakeIsBuffered() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, null, client12(), TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, null, server12(), TlsVersion.TLS_1_2, false);
        server.startTLS();
        client.startTLS();
        client.send(ByteBuffer.wrap("early".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("early", sp.received.toString());
        client.close();
        pump(client, server);
    }

    @Test
    public void tls12UntrustedServerFailsHandshake() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.trustNone());
        TcpEndpoint client = endpoint(cp, null, c, TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, null, server12(), TlsVersion.TLS_1_2, false);
        handshake(client, server);
        assertFalse(cp.events.contains("secure"));
        assertFalse(cp.errors.isEmpty());
    }

    @Test
    public void negotiatedPicksTls13() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), client12(), TlsVersion.NEGOTIATE, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        handshake(client, server);
        assertTrue(cp.events.contains("secure"));
        assertTrue(sp.events.contains("secure"));
        assertNotNull(client.getSecurityInfo().getProtocol());
        assertNotNull(server.getSecurityInfo().getProtocol());
        exchange(cp, client, sp, server);
        client.close();
        pump(client, server);
        assertTrue(sp.events.contains("disconnected"));
    }

    @Test
    public void negotiatedFallsBackToTls12WhenClientOnly12() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, null, client12(), TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        handshake(client, server);
        assertTrue(cp.events.contains("secure"));
        assertTrue(sp.events.contains("secure"));
        assertNotNull(server.getSecurityInfo().getCipherSuite());
        exchange(cp, client, sp, server);
        server.close();
        pump(client, server);
    }

    @Test
    public void negotiatedServerRejectsGarbage() throws Exception {
        Peer sp = new Peer();
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        server.startTLS();
        ByteBuffer in = server.prepareNetInForRead();
        in.put(new byte[] {0x16, 0x03, 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00});
        in.flip();
        server.processInbound();
        server.send(ByteBuffer.wrap(new byte[] {1}));
        server.close();
    }

    @Test
    public void negotiatedServerHandlesFragmentedClientHello() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), client12(), TlsVersion.NEGOTIATE, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        server.startTLS();
        client.startTLS();
        ByteBuffer out = client.getNetOut();
        out.flip();
        byte[] hello = new byte[out.remaining()];
        out.get(hello);
        out.clear();
        for (int i = 0; i < hello.length; i++) {
            ByteBuffer in = server.prepareNetInForRead();
            in.put(hello[i]);
            in.flip();
            server.processInbound();
        }
        pump(client, server);
        assertTrue(cp.events.contains("secure"));
        assertTrue(sp.events.contains("secure"));
    }

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }

    private static TcpEndpoint capped(Peer h, HandshakeConfig c13, Tls12HandshakeConfig c12,
            TlsVersion policy, boolean client, int maxOut, int maxIn) throws IOException {
        TcpEndpoint ep = endpoint(h, c13, c12, policy, client);
        TcpTransportFactory f = new TcpTransportFactory();
        f.setMaxNetOutSize(maxOut);
        f.setMaxNetInSize(maxIn);
        ep.setFactory(f);
        return ep;
    }

    @Test
    public void plainOutboundOverflowClosesEndpoint() throws Exception {
        Peer p = new Peer();
        TcpEndpoint ep = capped(p, null, null, TlsVersion.TLS_1_3, false, 40000, 0);
        ep.send(ByteBuffer.wrap(payload(500)));
        assertFalse(ep.isClosing());
        ep.send(ByteBuffer.wrap(payload(100000)));
        assertTrue(ep.isClosing());
    }

    @Test
    public void plainOutboundGrowsUnderCap() throws Exception {
        Peer p = new Peer();
        TcpEndpoint ep = capped(p, null, null, TlsVersion.TLS_1_3, false, 200000, 0);
        ep.send(ByteBuffer.wrap(payload(100000)));
        assertFalse(ep.isClosing());
        assertEquals(100000, ep.getNetOut().position());
    }

    @Test
    public void tls13LargeTransferGrowsBuffersBothDirections() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        handshake(client, server);
        byte[] big = payload(150000);
        ByteBuffer direct = ByteBuffer.allocateDirect(big.length);
        direct.put(big);
        direct.flip();
        client.send(direct);
        server.send(ByteBuffer.wrap(payload(90000)));
        pump(client, server);
        assertEquals(150000, sp.received.length());
        assertEquals(90000, cp.received.length());
    }

    @Test
    public void tls12LargeTransferGrowsBuffersBothDirections() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, null, client12(), TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, null, server12(), TlsVersion.TLS_1_2, false);
        handshake(client, server);
        byte[] big = payload(150000);
        ByteBuffer direct = ByteBuffer.allocateDirect(big.length);
        direct.put(big);
        direct.flip();
        client.send(direct);
        server.send(ByteBuffer.wrap(payload(90000)));
        pump(client, server);
        assertEquals(150000, sp.received.length());
        assertEquals(90000, cp.received.length());
    }

    @Test
    public void tls13TransferOverflowClosesClient() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = capped(cp, client13(), null, TlsVersion.TLS_1_3, true, 40000, 0);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        handshake(client, server);
        client.send(ByteBuffer.wrap(payload(200000)));
        assertTrue(cp.events.contains("disconnected"));
    }

    @Test
    public void tls12TransferOverflowClosesClient() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = capped(cp, null, client12(), TlsVersion.TLS_1_2, true, 40000, 0);
        TcpEndpoint server = endpoint(sp, null, server12(), TlsVersion.TLS_1_2, false);
        handshake(client, server);
        client.send(ByteBuffer.wrap(payload(200000)));
        assertTrue(cp.events.contains("disconnected"));
    }

    @Test
    public void tls13PendingAppDataOverflowClosesClient() throws Exception {
        Peer cp = new Peer();
        TcpEndpoint client = capped(cp, client13(), null, TlsVersion.TLS_1_3, true, 4000, 0);
        client.startTLS();
        client.send(ByteBuffer.wrap(payload(10)));
        client.send(ByteBuffer.wrap(payload(5000)));
        assertTrue(cp.events.contains("disconnected"));
    }

    @Test
    public void tls12PendingAppDataOverflowClosesClient() throws Exception {
        Peer cp = new Peer();
        TcpEndpoint client = capped(cp, null, client12(), TlsVersion.TLS_1_2, true, 4000, 0);
        client.startTLS();
        client.send(ByteBuffer.wrap(payload(10)));
        client.send(ByteBuffer.wrap(payload(5000)));
        assertTrue(cp.events.contains("disconnected"));
    }

    @Test
    public void tls13UnconsumedApplicationBytesAreRetained() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        sp.consumeAtMost = 3;
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        handshake(client, server);
        client.send(ByteBuffer.wrap("abcdefgh".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("abc", sp.received.toString());
        sp.consumeAtMost = 0;
        client.send(ByteBuffer.wrap("ij".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("abcdefghij", sp.received.toString());
    }

    @Test
    public void tls13RetainedBytesOverInputLimitReportError() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        sp.consumeAtMost = 1;
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = capped(sp, server13(), null, TlsVersion.TLS_1_3, false, 0, 4);
        handshake(client, server);
        client.send(ByteBuffer.wrap(payload(50)));
        pump(client, server);
        assertFalse(sp.errors.isEmpty());
    }

    @Test
    public void tls13SendAfterCloseIsDropped() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        handshake(client, server);
        client.close();
        client.send(ByteBuffer.wrap("late".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("", sp.received.toString());
        client.close();
    }

    @Test
    public void tls12SendAfterCloseIsDropped() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, null, client12(), TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, null, server12(), TlsVersion.TLS_1_2, false);
        handshake(client, server);
        client.close();
        client.send(ByteBuffer.wrap("late".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("", sp.received.toString());
    }

    private static void injectGarbageRecord(TcpEndpoint to) throws IOException {
        ByteBuffer in = to.prepareNetInForRead();
        byte[] rec = new byte[5 + 40];
        rec[0] = 0x17;
        rec[1] = 0x03;
        rec[2] = 0x03;
        rec[3] = 0;
        rec[4] = 40;
        for (int i = 5; i < rec.length; i++) {
            rec[i] = (byte) (i * 7);
        }
        in.put(rec);
        in.flip();
        to.processInbound();
    }

    @Test
    public void tls13CorruptRecordAfterHandshakeIsProtocolError() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), null, TlsVersion.TLS_1_3, false);
        handshake(client, server);
        injectGarbageRecord(server);
        assertFalse(sp.errors.isEmpty());
        assertTrue(sp.events.contains("disconnected"));
    }

    @Test
    public void tls12CorruptRecordAfterHandshakeIsProtocolError() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, null, client12(), TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, null, server12(), TlsVersion.TLS_1_2, false);
        handshake(client, server);
        injectGarbageRecord(server);
        assertFalse(sp.errors.isEmpty());
        assertTrue(sp.events.contains("disconnected"));
    }

    @Test
    public void negotiatedCorruptRecordAfterHandshakeIsProtocolError() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), client12(), TlsVersion.NEGOTIATE, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        handshake(client, server);
        injectGarbageRecord(server);
        assertFalse(sp.errors.isEmpty());
    }

    @Test
    public void negotiatedTls12CorruptRecordAfterHandshakeIsProtocolError() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, null, client12(), TlsVersion.TLS_1_2, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        handshake(client, server);
        injectGarbageRecord(server);
        assertFalse(sp.errors.isEmpty());
    }

    @Test
    public void negotiatedLargeTransferAfterHandshake() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), client12(), TlsVersion.NEGOTIATE, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        handshake(client, server);
        client.send(ByteBuffer.wrap(payload(70000)));
        pump(client, server);
        assertEquals(70000, sp.received.length());
        server.send(ByteBuffer.wrap(payload(70000)));
        pump(client, server);
        assertEquals(70000, cp.received.length());
    }

    @Test
    public void negotiatedTransferOverflowClosesClient() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = capped(cp, client13(), client12(), TlsVersion.NEGOTIATE, true, 40000, 0);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        handshake(client, server);
        client.send(ByteBuffer.wrap(payload(200000)));
        assertTrue(cp.events.contains("disconnected"));
    }

    @Test
    public void negotiatedAppDataBeforeVersionPickIsDropped() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), client12(), TlsVersion.NEGOTIATE, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        server.startTLS();
        client.startTLS();
        client.send(ByteBuffer.wrap("early".getBytes(StandardCharsets.UTF_8)));
        pump(client, server);
        assertEquals("", sp.received.toString());
        assertTrue(cp.events.contains("secure"));
    }

    @Test
    public void negotiatingServerAcceptsATls13OnlyClient() throws Exception {
        Peer cp = new Peer();
        Peer sp = new Peer();
        TcpEndpoint client = endpoint(cp, client13(), null, TlsVersion.TLS_1_3, true);
        TcpEndpoint server = endpoint(sp, server13(), server12(), TlsVersion.NEGOTIATE, false);
        handshake(client, server);
        assertTrue(cp.events.contains("secure"));
        assertTrue(sp.events.contains("secure"));
        exchange(cp, client, sp, server);
    }

    /** A client and server of one TLS version, optionally with output ceilings. */
    private static final class Duo {
        final Peer cp = new Peer();
        final Peer sp = new Peer();
        final TcpEndpoint client;
        final TcpEndpoint server;

        Duo(TlsVersion version, int clientMaxOut) throws Exception {
            HandshakeConfig c13 = null;
            HandshakeConfig s13 = null;
            Tls12HandshakeConfig c12 = null;
            Tls12HandshakeConfig s12 = null;
            if (version == TlsVersion.TLS_1_2) {
                c12 = client12();
                s12 = server12();
            } else if (version == TlsVersion.TLS_1_3) {
                c13 = client13();
                s13 = server13();
            } else {
                c13 = client13();
                s13 = server13();
                c12 = client12();
                s12 = server12();
            }
            client = capped(cp, c13, c12, version, true, clientMaxOut, 0);
            server = endpoint(sp, s13, s12, version, false);
        }
    }

    private static final TlsVersion[] VERSIONS = new TlsVersion[] {
        TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.NEGOTIATE
    };

    @Test
    public void usingAnEndpointAfterItsBuffersWereReleasedIsHarmless() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Duo d = new Duo(VERSIONS[v], 0);
            handshake(d.client, d.server);
            d.client.doClose();
            d.client.send(ByteBuffer.wrap("late".getBytes(StandardCharsets.UTF_8)));
            d.client.close();
            d.client.processInbound();
            assertTrue(VERSIONS[v].toString(), d.cp.events.contains("disconnected"));
        }
    }

    @Test
    public void bytesArrivingAfterThePeerClosedAreIgnored() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Duo d = new Duo(VERSIONS[v], 0);
            handshake(d.client, d.server);
            d.client.close();
            pump(d.client, d.server);
            assertTrue(VERSIONS[v].toString(), d.sp.events.contains("disconnected"));
            int before = d.sp.events.size();
            d.server.processInbound();
            assertEquals(VERSIONS[v].toString(), before, d.sp.events.size());
        }
    }

    @Test
    public void startingAClientHandshakeAfterCloseDoesNothing() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Duo d = new Duo(VERSIONS[v], 0);
            d.client.startTLS();
            d.client.doClose();
            d.client.initiateClientTLSHandshake();
            assertTrue(VERSIONS[v].toString(), d.cp.events.contains("disconnected"));
        }
    }

    @Test
    public void applicationDataQueuedBeforeTheHandshakeGrowsAndIsFlushed() throws Exception {
        for (int v = 0; v < 2; v++) {
            Duo d = new Duo(VERSIONS[v], 0);
            d.server.startTLS();
            d.client.startTLS();
            d.client.send(ByteBuffer.wrap(payload(20000)));
            d.client.send(ByteBuffer.wrap(payload(20000)));
            pump(d.client, d.server);
            assertEquals(VERSIONS[v].toString(), 40000, d.sp.received.length());
        }
    }

    @Test
    public void outputCeilingBoundsBufferGrowthWithoutClosing() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Duo d = new Duo(VERSIONS[v], 60000);
            handshake(d.client, d.server);
            d.client.send(ByteBuffer.wrap(payload(40000)));
            assertFalse(VERSIONS[v].toString(), d.client.isClosing());
            pump(d.client, d.server);
            assertEquals(VERSIONS[v].toString(), 40000, d.sp.received.length());
        }
    }

    @Test
    public void serverHandshakeProgressesWhenTheClientHelloIsDeliveredInTinyPieces() throws Exception {
        for (int v = 0; v < 2; v++) {
            Duo d = new Duo(VERSIONS[v], 0);
            d.server.startTLS();
            d.client.startTLS();
            ByteBuffer out = d.client.getNetOut();
            out.flip();
            byte[] hello = new byte[out.remaining()];
            out.get(hello);
            out.clear();
            for (int i = 0; i < hello.length; i += 7) {
                ByteBuffer in = d.server.prepareNetInForRead();
                int n = Math.min(7, hello.length - i);
                in.put(hello, i, n);
                in.flip();
                d.server.processInbound();
            }
            pump(d.client, d.server);
            assertTrue(VERSIONS[v].toString(), d.cp.events.contains("secure"));
        }
    }

    @Test
    public void tls13SecurityInfoReflectsSuiteProtocolAndClientCertificate() throws Exception {
        CipherSuite[] suites = new CipherSuite[] {
            CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256
        };
        int[] sizes = new int[] {128, 256, 256};
        for (int i = 0; i < suites.length; i++) {
            TestCertificates.Identity clientId = TestCertificates.newEc256("tls13-client");
            HandshakeConfig sc = server13();
            sc.setCipherSuites(Arrays.asList(suites[i]));
            sc.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
            sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            sc.setClientTrustManager(clientId.trustManager());
            HandshakeConfig cc = client13();
            cc.setCipherSuites(Arrays.asList(suites[i]));
            cc.setApplicationProtocols(Arrays.asList("h2"));
            cc.setClientCredentials(clientId.credentials());
            Peer cp = new Peer();
            Peer sp = new Peer();
            TcpEndpoint client = endpoint(cp, cc, null, TlsVersion.TLS_1_3, true);
            TcpEndpoint server = endpoint(sp, sc, null, TlsVersion.TLS_1_3, false);
            handshake(client, server);
            String label = suites[i].name();
            assertTrue(label, cp.events.contains("secure"));
            assertTrue(label, sp.events.contains("secure"));
            SecurityInfo info = client.getSecurityInfo();
            assertEquals(label, suites[i].name(), info.getCipherSuite());
            assertEquals(label, sizes[i], info.getKeySize());
            assertEquals(label, "h2", info.getApplicationProtocol());
            assertTrue(label, info.toString().contains("ALPN=h2"));
            assertNotNull(label, info.getLocalCertificates());
            SecurityInfo serverInfo = server.getSecurityInfo();
            assertNotNull(label, serverInfo.getPeerCertificates());
            assertTrue(label, serverInfo.getHandshakeDurationMs() >= -1L);
            assertFalse(label, serverInfo.isSessionResumed());
        }
    }

    @Test
    public void tls12SecurityInfoReflectsSuiteProtocolAndClientCertificate() throws Exception {
        Tls12CipherSuite[] suites = new Tls12CipherSuite[] {
            Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
        };
        int[] sizes = new int[] {128, 256, 256};
        for (int i = 0; i < suites.length; i++) {
            TestCertificates.Identity clientId = TestCertificates.newEc256("tls12-client");
            Tls12HandshakeConfig sc = server12();
            sc.setCipherSuites(Arrays.asList(suites[i]));
            sc.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
            sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            sc.setClientTrustManager(clientId.trustManager());
            Tls12HandshakeConfig cc = client12();
            cc.setCipherSuites(Arrays.asList(suites[i]));
            cc.setApplicationProtocols(Arrays.asList("h2"));
            cc.setClientCredentials(clientId.credentials());
            Peer cp = new Peer();
            Peer sp = new Peer();
            TcpEndpoint client = endpoint(cp, null, cc, TlsVersion.TLS_1_2, true);
            TcpEndpoint server = endpoint(sp, null, sc, TlsVersion.TLS_1_2, false);
            handshake(client, server);
            String label = suites[i].name();
            assertTrue(label, cp.events.contains("secure"));
            assertTrue(label, sp.events.contains("secure"));
            SecurityInfo info = client.getSecurityInfo();
            assertEquals(label, suites[i].name(), info.getCipherSuite());
            assertEquals(label, sizes[i], info.getKeySize());
            assertEquals(label, "h2", info.getApplicationProtocol());
            assertTrue(label, info.toString().contains("ALPN=h2"));
            assertNotNull(label, info.getLocalCertificates());
            SecurityInfo serverInfo = server.getSecurityInfo();
            assertNotNull(label, serverInfo.getPeerCertificates());
            assertFalse(label, serverInfo.isSessionResumed());
        }
    }

    @Test
    public void serverDemandingAClientCertificateRejectsAClientWithoutOne() throws Exception {
        TestCertificates.Identity clientId = TestCertificates.newEc256("expected-client");
        HandshakeConfig sc13 = server13();
        sc13.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc13.setClientTrustManager(clientId.trustManager());
        Tls12HandshakeConfig sc12 = server12();
        sc12.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc12.setClientTrustManager(clientId.trustManager());
        for (int v = 0; v < VERSIONS.length; v++) {
            Peer cp = new Peer();
            Peer sp = new Peer();
            TlsVersion version = VERSIONS[v];
            TcpEndpoint client = endpoint(cp,
                    version == TlsVersion.TLS_1_2 ? null : client13(),
                    version == TlsVersion.TLS_1_3 ? null : client12(), version, true);
            TcpEndpoint server = endpoint(sp,
                    version == TlsVersion.TLS_1_2 ? null : sc13,
                    version == TlsVersion.TLS_1_3 ? null : sc12, version, false);
            handshake(client, server);
            assertFalse(version.toString(), sp.events.contains("secure"));
            assertFalse(version.toString(), sp.errors.isEmpty());
        }
    }
}
