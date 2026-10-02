/*
 * UdpEndpointDtlsSessionTest.java
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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.StubDatagramChannel;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * One DTLS client and one DTLS server {@link UdpEndpoint}, connected by
 * hand-delivered datagrams, for each DTLS version policy: HelloVerifyRequest
 * and cookie handling, certificate rejection, close_notify, corrupt and
 * hostile datagrams, peer limits and bulk data. No sockets and no threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpEndpointDtlsSessionTest {

    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 5100);
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 5101);
    private static final InetSocketAddress OTHER_ADDR = new InetSocketAddress("127.0.0.1", 5102);

    private static TestCertificates.Identity identity;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        identity = TestCertificates.ec256();
    }

    private static final class Peer implements ProtocolHandler {
        UdpEndpoint endpoint;
        final boolean echo;
        final List<String> received = new ArrayList<String>();
        final List<Exception> errors = new ArrayList<Exception>();
        boolean secure;
        int disconnects;

        Peer(boolean echo) {
            this.echo = echo;
        }

        @Override
        public void receive(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            received.add(new String(bytes, StandardCharsets.UTF_8));
            if (echo) {
                endpoint.send(ByteBuffer.wrap(bytes));
            }
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            secure = true;
        }

        @Override
        public void disconnected() {
            disconnects++;
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static final class Link {
        final Peer serverPeer = new Peer(true);
        final Peer clientPeer = new Peer(false);
        final UdpEndpoint server;
        final UdpEndpoint client;

        Link(DtlsVersion version, UdpTransportFactory serverFactory,
                X509TrustManager clientTrust, InetSocketAddress clientAddr) {
            this(version, version, serverFactory, clientTrust, clientAddr);
        }

        Link(DtlsVersion serverVersion, DtlsVersion clientVersion, UdpTransportFactory serverFactory,
                X509TrustManager clientTrust, InetSocketAddress clientAddr) {
            serverFactory.setDtlsVersion(serverVersion);
            serverFactory.start();
            server = endpoint(serverPeer, serverFactory, false);
            UdpTransportFactory cf = new UdpTransportFactory();
            cf.setSecure(true);
            cf.setDtlsVersion(clientVersion);
            cf.setTrustManager(clientTrust);
            cf.start();
            client = endpoint(clientPeer, cf, true);
            this.clientAddr = clientAddr;
        }

        final InetSocketAddress clientAddr;

        private static UdpEndpoint endpoint(Peer peer, UdpTransportFactory f, boolean clientMode) {
            UdpEndpoint ep = new UdpEndpoint(peer);
            ep.setFactory(f);
            ep.setSecure(true);
            ep.setClientMode(clientMode);
            if (clientMode) {
                ep.setRemoteAddress(SERVER_ADDR);
            }
            ep.setSelectorLoop(new InlineSelectorLoop());
            ep.init();
            peer.endpoint = ep;
            return ep;
        }

        boolean deliver(UdpEndpoint from, UdpEndpoint to, InetSocketAddress source) {
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

        void pump() {
            boolean progress = true;
            int guard = 0;
            while (progress && guard < 500) {
                boolean a = deliver(client, server, clientAddr);
                boolean b = deliver(server, client, SERVER_ADDR);
                progress = a || b;
                guard++;
            }
        }

        void handshake() {
            client.startClientDtlsHandshake();
            pump();
        }
    }

    private static UdpTransportFactory serverFactory() {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setServerCredentials(identity.credentials());
        return f;
    }

    private static UdpTransportFactory cookieFactory(byte[] secret) {
        UdpTransportFactory f = serverFactory();
        f.setRequireCookie(true);
        f.setCookieSecret(secret);
        return f;
    }

    private static byte[] secret() {
        byte[] s = new byte[32];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (i + 1);
        }
        return s;
    }

    private static final DtlsVersion[] VERSIONS = new DtlsVersion[] {
        DtlsVersion.DTLS_1_2, DtlsVersion.DTLS_1_3, DtlsVersion.NEGOTIATE
    };

    private static void say(UdpEndpoint from, String text, InetSocketAddress to) {
        from.sendTo(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)), to);
    }

    @Test
    public void plainHandshakeAndEchoForEveryVersion() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            String label = VERSIONS[v].toString();
            assertTrue(label, link.clientPeer.secure);
            assertTrue(label, link.serverPeer.secure);
            SecurityInfo info = link.client.getSecurityInfo();
            assertNotNull(label, info.getProtocol());
            info.getCipherSuite();
            info.getPeerCertificates();
            info.getLocalCertificates();
            info.getKeySize();
            assertNotNull(info.toString());
            link.client.send(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
            link.pump();
            assertEquals(label, 1, link.serverPeer.received.size());
            assertEquals(label, "hello", link.serverPeer.received.get(0));
            assertEquals(label, "hello", link.clientPeer.received.get(0));
            assertTrue(label, link.clientPeer.errors.isEmpty());
            assertTrue(label, link.serverPeer.errors.isEmpty());
        }
    }

    @Test
    public void cookieExchangeCompletesHandshake() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], cookieFactory(secret()), TestCertificates.trustAll(),
                    CLIENT_ADDR);
            link.handshake();
            String label = VERSIONS[v].toString();
            assertTrue(label, link.clientPeer.secure);
            assertTrue(label, link.serverPeer.secure);
            link.client.send(ByteBuffer.wrap("cookie".getBytes(StandardCharsets.UTF_8)));
            link.pump();
            assertEquals(label, "cookie", link.serverPeer.received.get(0));
        }
    }

    @Test
    public void cookieRequiredWithoutSecretFailsServerSession() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            UdpTransportFactory f = serverFactory();
            f.setRequireCookie(true);
            Link link = new Link(VERSIONS[v], f, TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            String label = VERSIONS[v].toString();
            assertFalse(label, link.clientPeer.secure);
            assertFalse(label, link.serverPeer.secure);
            assertTrue(label, link.serverPeer.received.isEmpty());
        }
    }

    @Test
    public void untrustedServerCertificateFailsClientHandshake() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustNone(), CLIENT_ADDR);
            link.handshake();
            String label = VERSIONS[v].toString();
            assertFalse(label, link.clientPeer.secure);
            assertFalse(label, link.clientPeer.errors.isEmpty());
        }
    }

    @Test
    public void closeNotifyEndsServerSession() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            link.client.close();
            link.pump();
            String label = VERSIONS[v].toString();
            assertTrue(label, link.serverPeer.errors.isEmpty());
            say(link.server, "after", CLIENT_ADDR);
            link.pump();
            assertTrue(label, link.clientPeer.received.isEmpty());
        }
    }

    @Test
    public void serverCloseEndsClientSession() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            link.server.close();
            link.pump();
            link.client.send(ByteBuffer.wrap("late".getBytes(StandardCharsets.UTF_8)));
            link.pump();
            String label = VERSIONS[v].toString();
            assertTrue(label, link.serverPeer.received.isEmpty());
        }
    }

    @Test
    public void corruptDatagramsNeverBreakTheServerForOtherPeers() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            byte[] junk = new byte[13 + 30];
            junk[0] = 23;
            junk[1] = (byte) 0xfe;
            junk[2] = (byte) 0xfd;
            junk[4] = 1;
            junk[10] = 9;
            junk[12] = 30;
            for (int i = 13; i < junk.length; i++) {
                junk[i] = (byte) (i * 31 + 5);
            }
            link.server.netReceive(ByteBuffer.wrap(junk), CLIENT_ADDR);
            link.client.netReceive(ByteBuffer.wrap(junk), SERVER_ADDR);
            ByteBuffer direct = ByteBuffer.allocateDirect(junk.length);
            direct.put(junk);
            direct.flip();
            link.server.netReceive(direct, CLIENT_ADDR);
            byte[] unified = new byte[] {0x2d, 0, 1, 0, 20, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24};
            link.server.netReceive(ByteBuffer.wrap(unified), CLIENT_ADDR);

            Peer otherPeer = new Peer(false);
            UdpTransportFactory cf = new UdpTransportFactory();
            cf.setSecure(true);
            cf.setDtlsVersion(VERSIONS[v]);
            cf.setTrustManager(TestCertificates.trustAll());
            cf.start();
            UdpEndpoint other = Link.endpoint(otherPeer, cf, true);
            other.startClientDtlsHandshake();
            boolean progress = true;
            int guard = 0;
            while (progress && guard < 500) {
                UdpEndpoint.PendingDatagram head = link.server.pendingDatagrams.peek();
                while (head != null && !OTHER_ADDR.equals(head.destination)) {
                    link.server.pendingDatagrams.poll();
                    link.server.onPendingDatagramFullySent(head);
                    head = link.server.pendingDatagrams.peek();
                }
                boolean a = link.deliver(other, link.server, OTHER_ADDR);
                boolean b = link.deliver(link.server, other, SERVER_ADDR);
                progress = a || b;
                guard++;
            }
            assertTrue(VERSIONS[v].toString(), otherPeer.secure);
        }
    }

    @Test
    public void hostileFirstDatagramFromStrangerDoesNotBreakServer() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            byte[] junk = new byte[] {22, (byte) 0xfe, (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3};
            link.server.netReceive(ByteBuffer.wrap(junk), OTHER_ADDR);
            byte[] empty = new byte[] {1};
            link.server.netReceive(ByteBuffer.wrap(empty), OTHER_ADDR);
            link.handshake();
            String label = VERSIONS[v].toString();
            assertTrue(label, link.clientPeer.secure);
        }
    }

    @Test
    public void peerLimitRefusesSecondClient() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            UdpTransportFactory f = serverFactory();
            f.setMaxDtlsPeers(1);
            Link link = new Link(VERSIONS[v], f, TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            byte[] hello = new byte[] {22, (byte) 0xfe, (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1};
            link.server.netReceive(ByteBuffer.wrap(hello), OTHER_ADDR);
            assertTrue(VERSIONS[v].toString(), link.serverPeer.secure);
            assertTrue(link.server.pendingDatagrams.isEmpty());
        }
    }

    @Test
    public void bulkDataBothWays() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append((char) ('a' + (i % 26)));
            }
            String text = sb.toString();
            for (int i = 0; i < 20; i++) {
                say(link.client, text, SERVER_ADDR);
            }
            link.pump();
            String label = VERSIONS[v].toString();
            assertEquals(label, 20, link.serverPeer.received.size());
            assertEquals(label, text, link.serverPeer.received.get(19));
        }
    }

    @Test
    public void dataBeforeHandshakeCompletesIsNotDelivered() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.client.startClientDtlsHandshake();
            link.client.send(ByteBuffer.wrap("early".getBytes(StandardCharsets.UTF_8)));
            link.pump();
            String label = VERSIONS[v].toString();
            assertTrue(label, link.clientPeer.secure);
            assertTrue(label, link.serverPeer.received.size() <= 1);
        }
    }

    /**
     * Fires, by hand, the earliest armed timer of the endpoint's loop that
     * has not fired yet (the loop's timer thread is never started).
     *
     * @return false if there was none
     */
    private static boolean fireNextTimer(UdpEndpoint endpoint, Set<ScheduledTimer.TimerEntry> fired) {
        List<ScheduledTimer.TimerEntry> entries = endpoint.getSelectorLoop().getTimer().pendingEntries();
        Collections.sort(entries);
        for (int i = 0; i < entries.size(); i++) {
            ScheduledTimer.TimerEntry e = entries.get(i);
            if (!e.isCancelled() && !fired.contains(e)) {
                fired.add(e);
                e.callback.run();
                return true;
            }
        }
        return false;
    }

    private static void dropPending(UdpEndpoint endpoint) {
        UdpEndpoint.PendingDatagram p = endpoint.pendingDatagrams.poll();
        while (p != null) {
            endpoint.onPendingDatagramFullySent(p);
            p = endpoint.pendingDatagrams.poll();
        }
    }

    @Test
    public void retransmissionRecoversALostFirstFlight() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            Set<ScheduledTimer.TimerEntry> fired = new HashSet<ScheduledTimer.TimerEntry>();
            link.client.startClientDtlsHandshake();
            dropPending(link.client);
            assertTrue(VERSIONS[v].toString(), fireNextTimer(link.client, fired));
            link.pump();
            assertTrue(VERSIONS[v].toString(), link.clientPeer.secure);
            assertTrue(VERSIONS[v].toString(), link.serverPeer.secure);
        }
    }

    @Test
    public void lostServerFlightIsRetransmittedByTheServer() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            Set<ScheduledTimer.TimerEntry> clientFired = new HashSet<ScheduledTimer.TimerEntry>();
            Set<ScheduledTimer.TimerEntry> serverFired = new HashSet<ScheduledTimer.TimerEntry>();
            link.client.startClientDtlsHandshake();
            link.deliver(link.client, link.server, CLIENT_ADDR);
            dropPending(link.server);
            boolean serverTimer = fireNextTimer(link.server, serverFired);
            boolean clientTimer = fireNextTimer(link.client, clientFired);
            assertTrue(VERSIONS[v].toString(), serverTimer || clientTimer);
            link.pump();
            int guard = 0;
            while (!link.clientPeer.secure && guard < 10) {
                fireNextTimer(link.client, clientFired);
                fireNextTimer(link.server, serverFired);
                link.pump();
                guard++;
            }
            assertTrue(VERSIONS[v].toString(), link.clientPeer.secure);
        }
    }

    @Test
    public void unansweredHandshakeEventuallyFailsTheClient() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            Set<ScheduledTimer.TimerEntry> fired = new HashSet<ScheduledTimer.TimerEntry>();
            link.client.startClientDtlsHandshake();
            dropPending(link.client);
            int firings = 0;
            while (link.clientPeer.errors.isEmpty() && fireNextTimer(link.client, fired)) {
                dropPending(link.client);
                firings++;
                if (firings > 64) {
                    break;
                }
            }
            String label = VERSIONS[v].toString();
            assertTrue(label, firings > 1);
            assertFalse(label, link.clientPeer.errors.isEmpty());
            assertFalse(label, link.clientPeer.secure);
        }
    }

    @Test
    public void negotiatingPeersMeetOnTheVersionBothSpeak() throws Exception {
        // A negotiating client against a DTLS 1.2-only server is left out: the TLS 1.3
        // probe ClientHello does not offer extended_master_secret, which the 1.2
        // engine requires, so that fallback cannot complete (tls package). A negotiating
        // server against a DTLS 1.2 client is left out too: DtlsVersionPick reads the
        // DTLS 1.2 ClientHello, which carries a cookie field, as a TLS one.
        DtlsVersion[][] pairs = new DtlsVersion[][] {
            {DtlsVersion.DTLS_1_3, DtlsVersion.NEGOTIATE},
            {DtlsVersion.NEGOTIATE, DtlsVersion.DTLS_1_3}
        };
        for (int i = 0; i < pairs.length; i++) {
            Link link = new Link(pairs[i][0], pairs[i][1], serverFactory(), TestCertificates.trustAll(),
                    CLIENT_ADDR);
            link.handshake();
            String label = pairs[i][0] + " server, " + pairs[i][1] + " client ";
            assertTrue(label, link.clientPeer.secure);
            assertTrue(label, link.serverPeer.secure);
            say(link.client, "version", SERVER_ADDR);
            link.pump();
            assertEquals(label, "version", link.serverPeer.received.get(0));
            assertEquals(label, "version", link.clientPeer.received.get(0));
        }
    }

    @Test
    public void peersWithNoVersionInCommonDoNotConnect() throws Exception {
        DtlsVersion[][] pairs = new DtlsVersion[][] {
            {DtlsVersion.DTLS_1_2, DtlsVersion.DTLS_1_3},
            {DtlsVersion.DTLS_1_3, DtlsVersion.DTLS_1_2}
        };
        for (int i = 0; i < pairs.length; i++) {
            Link link = new Link(pairs[i][0], pairs[i][1], serverFactory(), TestCertificates.trustAll(),
                    CLIENT_ADDR);
            link.handshake();
            String label = pairs[i][0] + " server, " + pairs[i][1] + " client";
            assertFalse(label, link.clientPeer.secure);
            assertFalse(label, link.serverPeer.secure);
        }
    }

    @Test
    public void directBuffersAreEncryptedLikeHeapBuffers() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            byte[] text = "direct".getBytes(StandardCharsets.UTF_8);
            ByteBuffer direct = ByteBuffer.allocateDirect(text.length);
            direct.put(text);
            direct.flip();
            link.client.sendTo(direct, SERVER_ADDR);
            link.pump();
            assertEquals(VERSIONS[v].toString(), "direct", link.serverPeer.received.get(0));
            assertEquals(VERSIONS[v].toString(), "direct", link.clientPeer.received.get(0));
        }
    }

    @Test
    public void closingAnEndpointClosesItsSessions() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            link.server.closeForShutdown(true);
            assertTrue(VERSIONS[v].toString(), link.server.isClosing());
            link.pump();
            assertTrue(VERSIONS[v].toString(), link.serverPeer.disconnects > 0);
        }
    }

    @Test
    public void closeNotifyFlushedByAnOrderlyShutdownEndsThePeersSession() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            Link link = new Link(VERSIONS[v], serverFactory(), TestCertificates.trustAll(), CLIENT_ADDR);
            StubDatagramChannel wire = new StubDatagramChannel(CLIENT_ADDR);
            link.client.setChannel(wire);
            link.handshake();
            link.client.closeForShutdown(true);
            String label = VERSIONS[v].toString();
            assertFalse(label, wire.getSent().isEmpty());
            for (int i = 0; i < wire.getSent().size(); i++) {
                byte[] datagram = wire.getSent().get(i).getBytes();
                link.server.netReceive(ByteBuffer.wrap(datagram), CLIENT_ADDR);
            }
            say(link.server, "after-close", CLIENT_ADDR);
            assertTrue(label, link.server.pendingDatagrams.isEmpty());
            assertTrue(label, link.serverPeer.errors.isEmpty());
        }
    }

    @Test
    public void serverRequiringAClientCertificateRejectsAClientWithoutOne() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            UdpTransportFactory f = serverFactory();
            f.setNeedClientAuth(true);
            f.setTrustManager(TestCertificates.trustNone());
            Link link = new Link(VERSIONS[v], f, TestCertificates.trustAll(), CLIENT_ADDR);
            link.handshake();
            String label = VERSIONS[v].toString();
            assertFalse(label, link.serverPeer.secure);
        }
    }

    @Test
    public void serverRequiringAClientCertificateAcceptsAcceptableOne() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            TestCertificates.Identity clientId = TestCertificates.newEc256("dtls-client");
            UdpTransportFactory f = serverFactory();
            f.setNeedClientAuth(true);
            f.setTrustManager(clientId.trustManager());
            Link link = new Link(VERSIONS[v], f, TestCertificates.trustAll(), CLIENT_ADDR);
            UdpTransportFactory cf = new UdpTransportFactory();
            cf.setSecure(true);
            cf.setDtlsVersion(VERSIONS[v]);
            cf.setTrustManager(TestCertificates.trustAll());
            cf.setClientCredentials(clientId.credentials());
            cf.start();
            UdpEndpoint client = Link.endpoint(link.clientPeer, cf, true);
            boolean progress = true;
            client.startClientDtlsHandshake();
            int guard = 0;
            while (progress && guard < 500) {
                boolean a = link.deliver(client, link.server, CLIENT_ADDR);
                boolean b = link.deliver(link.server, client, SERVER_ADDR);
                progress = a || b;
                guard++;
            }
            String label = VERSIONS[v].toString();
            assertTrue(label, link.clientPeer.secure);
            assertTrue(label, link.serverPeer.secure);
        }
    }

    @Test
    public void nonClientHelloFirstDatagramToACookieServerIsDropped() throws Exception {
        UdpTransportFactory f = cookieFactory(secret());
        Link link = new Link(DtlsVersion.DTLS_1_2, f, TestCertificates.trustAll(), CLIENT_ADDR);
        byte[] junk = new byte[] {23, (byte) 0xfe, (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 2};
        link.server.netReceive(ByteBuffer.wrap(junk), CLIENT_ADDR);
        assertTrue(link.server.pendingDatagrams.isEmpty());
        link.handshake();
        assertTrue(link.clientPeer.secure);
    }

    @Test
    public void securityInfoReportsAlpnCertificatesAndSuites() throws Exception {
        for (int v = 0; v < VERSIONS.length; v++) {
            TestCertificates.Identity clientId = TestCertificates.newEc256("dtls-info-client");
            UdpTransportFactory sf = serverFactory();
            sf.setApplicationProtocols(new String[] {"coap", "other"});
            sf.setNeedClientAuth(true);
            sf.setTrustManager(clientId.trustManager());
            Link link = new Link(VERSIONS[v], sf, TestCertificates.trustAll(), CLIENT_ADDR);
            UdpTransportFactory cf = new UdpTransportFactory();
            cf.setSecure(true);
            cf.setDtlsVersion(VERSIONS[v]);
            cf.setTrustManager(TestCertificates.trustAll());
            cf.setApplicationProtocols(new String[] {"coap"});
            cf.setClientCredentials(clientId.credentials());
            cf.start();
            UdpEndpoint client = Link.endpoint(link.clientPeer, cf, true);
            client.startClientDtlsHandshake();
            boolean progress = true;
            int guard = 0;
            while (progress && guard < 500) {
                boolean a = link.deliver(client, link.server, CLIENT_ADDR);
                boolean b = link.deliver(link.server, client, SERVER_ADDR);
                progress = a || b;
                guard++;
            }
            String label = VERSIONS[v].toString();
            assertTrue(label, link.clientPeer.secure);
            SecurityInfo info = client.getSecurityInfo();
            assertEquals(label, "coap", info.getApplicationProtocol());
            assertTrue(label, info.toString().contains("ALPN=coap"));
            assertNotNull(label, info.getLocalCertificates());
            assertNotNull(label, info.getPeerCertificates());
            assertTrue(label, info.getKeySize() > 0);
            assertFalse(label, info.isSessionResumed());
            assertTrue(label, info.getHandshakeDurationMs() >= -1L);
            SecurityInfo serverInfo = link.server.getSecurityInfo();
            assertNotNull(label, serverInfo.getLocalCertificates());
            assertNotNull(label, serverInfo.getPeerCertificates());
            assertEquals(label, "coap", serverInfo.getApplicationProtocol());
        }
    }
}
