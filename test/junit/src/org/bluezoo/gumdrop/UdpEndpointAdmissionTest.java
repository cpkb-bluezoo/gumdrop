/*
 * UdpEndpointAdmissionTest.java
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
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Admission control and configuration checks of {@link UdpEndpoint} for each
 * DTLS version policy: the accepting listener is told about every session
 * it admits and releases, a listener at its connection cap refuses new
 * peers, a zero peer cap means unlimited, and a secure server without a
 * DTLS configuration refuses the first datagram. One client and one server
 * endpoint are joined by hand-delivered datagrams; there are no sockets and
 * no threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UdpEndpointAdmissionTest {

    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 5200);
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 5201);

    private static final DtlsVersion[] VERSIONS = new DtlsVersion[] {
        DtlsVersion.DTLS_1_2, DtlsVersion.DTLS_1_3, DtlsVersion.NEGOTIATE
    };

    private static TestCertificates.Identity identity;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        identity = TestCertificates.ec256();
    }

    private static final class Peer implements ProtocolHandler {
        boolean secure;
        int disconnects;

        @Override
        public void receive(ByteBuffer data) {
            data.position(data.limit());
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
        }
    }

    private static final class CountingUdpListener extends UdpListener {
        @Override
        protected ProtocolHandler createProtocolHandler() {
            return null;
        }

        @Override
        public String getDescription() {
            return "counting udp";
        }
    }

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
        return ep;
    }

    private static void pump(UdpEndpoint client, UdpEndpoint server) {
        boolean progress = true;
        int guard = 0;
        while (progress && guard < 500) {
            boolean a = deliver(client, server, CLIENT_ADDR);
            boolean b = deliver(server, client, SERVER_ADDR);
            progress = a || b;
            guard++;
        }
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

    private static UdpTransportFactory serverFactory(DtlsVersion version) {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setDtlsVersion(version);
        f.setServerCredentials(identity.credentials());
        f.start();
        return f;
    }

    private static UdpTransportFactory clientFactory(DtlsVersion version) {
        UdpTransportFactory cf = new UdpTransportFactory();
        cf.setSecure(true);
        cf.setDtlsVersion(version);
        cf.setTrustManager(TestCertificates.trustAll());
        cf.start();
        return cf;
    }

    @Test
    public void listenerCountsAdmittedSessionsAndReleasesThemOnClose() {
        for (int v = 0; v < VERSIONS.length; v++) {
            String label = VERSIONS[v].toString();
            CountingUdpListener listener = new CountingUdpListener();
            Peer serverPeer = new Peer();
            Peer clientPeer = new Peer();
            UdpEndpoint server = endpoint(serverPeer, serverFactory(VERSIONS[v]), false);
            server.setListener(listener);
            UdpEndpoint client = endpoint(clientPeer, clientFactory(VERSIONS[v]), true);
            client.startClientDtlsHandshake();
            pump(client, server);
            assertTrue(label, clientPeer.secure);
            assertTrue(label, serverPeer.secure);
            assertEquals(label, 1, listener.getActiveConnectionCount());
            server.close();
            assertEquals(label, 0, listener.getActiveConnectionCount());
        }
    }

    @Test
    public void listenerAtItsCapRefusesTheNewPeer() {
        for (int v = 0; v < VERSIONS.length; v++) {
            String label = VERSIONS[v].toString();
            CountingUdpListener listener = new CountingUdpListener();
            listener.setMaxConnections(1);
            listener.connectionOpened(new InetSocketAddress("127.0.0.1", 6000));
            Peer serverPeer = new Peer();
            Peer clientPeer = new Peer();
            UdpEndpoint server = endpoint(serverPeer, serverFactory(VERSIONS[v]), false);
            server.setListener(listener);
            UdpEndpoint client = endpoint(clientPeer, clientFactory(VERSIONS[v]), true);
            client.startClientDtlsHandshake();
            pump(client, server);
            assertFalse(label, clientPeer.secure);
            assertFalse(label, serverPeer.secure);
            assertEquals(label, 1, listener.getActiveConnectionCount());
        }
    }

    @Test
    public void zeroPeerCapMeansUnlimited() {
        for (int v = 0; v < VERSIONS.length; v++) {
            String label = VERSIONS[v].toString();
            UdpTransportFactory sf = serverFactory(VERSIONS[v]);
            sf.setMaxDtlsPeers(0);
            Peer serverPeer = new Peer();
            Peer clientPeer = new Peer();
            UdpEndpoint server = endpoint(serverPeer, sf, false);
            UdpEndpoint client = endpoint(clientPeer, clientFactory(VERSIONS[v]), true);
            client.startClientDtlsHandshake();
            pump(client, server);
            assertTrue(label, clientPeer.secure);
            assertTrue(label, serverPeer.secure);
        }
    }

    @Test
    public void secureServerWithoutCredentialsRefusesTheFirstDatagram() {
        for (int v = 0; v < VERSIONS.length; v++) {
            UdpTransportFactory f = new UdpTransportFactory();
            f.setSecure(true);
            f.setDtlsVersion(VERSIONS[v]);
            UdpEndpoint server = endpoint(new Peer(), f, false);
            try {
                server.netReceive(ByteBuffer.wrap(new byte[] {22, 1, 2, 3}), CLIENT_ADDR);
                fail("expected IllegalStateException for " + VERSIONS[v]);
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("no DTLS"));
            }
        }
    }

    @Test
    public void clientHandshakeIsOnlyStartedForSecureClients() {
        Peer p = new Peer();
        UdpEndpoint server = endpoint(p, serverFactory(DtlsVersion.DTLS_1_2), false);
        server.startClientDtlsHandshake();
        assertTrue(server.pendingDatagrams.isEmpty());
        UdpEndpoint noRemote = new UdpEndpoint(new Peer());
        noRemote.setFactory(clientFactory(DtlsVersion.DTLS_1_2));
        noRemote.setSecure(true);
        noRemote.setClientMode(true);
        noRemote.setSelectorLoop(new InlineSelectorLoop());
        noRemote.init();
        noRemote.startClientDtlsHandshake();
        assertTrue(noRemote.pendingDatagrams.isEmpty());
    }

    @Test
    public void nonUdpFactoryLeavesTheVersionPolicyAlone() {
        UdpEndpoint ep = new UdpEndpoint(new Peer());
        ep.setFactory(new TcpTransportFactory());
        ep.setSelectorLoop(new InlineSelectorLoop());
        ep.init();
        assertFalse(ep.isSecure());
        assertFalse(ep.isTelemetryEnabled());
    }

    @Test
    public void plainEndpointWithoutALoopQueuesAndIgnoresMissingChannel() {
        UdpEndpoint ep = new UdpEndpoint(new Peer());
        ep.init();
        ep.sendTo(ByteBuffer.wrap(new byte[] {1}), CLIENT_ADDR);
        assertEquals(1, ep.pendingDatagrams.size());
        ByteBuffer owned = ByteBuffer.wrap(new byte[] {2, 3});
        ep.sendOwnedRawDatagram(owned, CLIENT_ADDR);
        assertEquals(2, ep.pendingDatagrams.size());
        assertFalse(ep.isOpen());
        ep.close();
        assertTrue(ep.isClosing());
    }
}
