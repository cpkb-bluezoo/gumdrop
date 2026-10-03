/*
 * DtlsSessionLifecycleTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Lifecycle of {@link Dtls12Session} and {@link Dtls13Session} outside a
 * completed handshake: application data before the handshake is refused,
 * a server-role session never initiates, an unanswered client handshake
 * retransmits its flight on each (hand-fired) timer until the attempts are
 * exhausted and the endpoint reports the failure, and a closed session
 * ignores everything further.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsSessionLifecycleTest {

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 5300);

    private static TestCertificates.Identity identity;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        identity = TestCertificates.ec256();
    }

    /** Runs tasks inline; its timer thread is never started, so armed timers are fired by hand. */
    private static final class ManualLoop extends SelectorLoop {
        private final java.util.Set<Long> fired = new java.util.HashSet<Long>();

        ManualLoop() {
            super(0);
        }

        @Override
        public boolean tryInvokeLater(Runnable task) {
            task.run();
            return true;
        }

        /**
         * Runs the most recently armed timer that has not been cancelled.
         *
         * @return false when no armed timer is left
         */
        boolean fireLatest() {
            List<ScheduledTimer.TimerEntry> entries = getTimer().pendingEntries();
            ScheduledTimer.TimerEntry latest = null;
            for (int i = 0; i < entries.size(); i++) {
                ScheduledTimer.TimerEntry e = entries.get(i);
                if (e.isCancelled() || fired.contains(Long.valueOf(e.id))) {
                    continue;
                }
                if (latest == null || e.id > latest.id) {
                    latest = e;
                }
            }
            if (latest == null) {
                return false;
            }
            fired.add(Long.valueOf(latest.id));
            latest.callback.run();
            return true;
        }
    }

    private static final class Peer implements ProtocolHandler {
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void receive(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
            errors.add(cause);
        }
    }

    private static UdpTransportFactory clientFactory(DtlsVersion v) {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setDtlsVersion(v);
        f.setTrustManager(TestCertificates.trustAll());
        f.start();
        return f;
    }

    private static UdpTransportFactory serverFactory(DtlsVersion v) {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setDtlsVersion(v);
        f.setServerCredentials(identity.credentials());
        f.start();
        return f;
    }

    private static UdpEndpoint endpoint(Peer p, UdpTransportFactory f, ManualLoop loop, boolean client) {
        UdpEndpoint ep = new UdpEndpoint(p);
        ep.setFactory(f);
        ep.setSecure(true);
        ep.setClientMode(client);
        if (client) {
            ep.setRemoteAddress(PEER);
        }
        ep.setSelectorLoop(loop);
        ep.init();
        return ep;
    }

    private static void clear(UdpEndpoint ep) {
        while (ep.pendingDatagrams.poll() != null) {
            continue;
        }
    }

    @Test
    public void dtls12RefusesDataAndIgnoresServerRoleBeforeHandshake() {
        ManualLoop loop = new ManualLoop();
        UdpTransportFactory cf = clientFactory(DtlsVersion.DTLS_1_2);
        UdpEndpoint ep = endpoint(new Peer(), cf, loop, true);
        Dtls12Session session = new Dtls12Session(cf.buildClientConfig12("localhost"), ep, PEER);
        assertNull(session.send(ByteBuffer.wrap(new byte[] {1})));
        session.sendApplicationData(new byte[] {1, 2});
        assertTrue(ep.pendingDatagrams.isEmpty());
        assertFalse(session.isHandshakeComplete());
        assertNull(session.getSecurityInfo());
        assertNotNull(session.getRecordEngine());

        UdpTransportFactory sf = serverFactory(DtlsVersion.DTLS_1_2);
        UdpEndpoint server = endpoint(new Peer(), sf, loop, false);
        Dtls12Session serverSession = new Dtls12Session(sf.getSharedServerConfig(), server, PEER);
        serverSession.beginHandshake();
        assertTrue(server.pendingDatagrams.isEmpty());
    }

    @Test
    public void dtls13RefusesDataAndIgnoresServerRoleBeforeHandshake() {
        ManualLoop loop = new ManualLoop();
        UdpTransportFactory cf = clientFactory(DtlsVersion.DTLS_1_3);
        UdpEndpoint ep = endpoint(new Peer(), cf, loop, true);
        Dtls13Session session = new Dtls13Session(cf.buildClientConfig13("localhost"), ep, PEER);
        session.sendApplicationData(new byte[] {1, 2});
        assertTrue(ep.pendingDatagrams.isEmpty());
        assertFalse(session.isHandshakeComplete());
        assertNull(session.getSecurityInfo());
        assertNotNull(session.getRecordEngine());

        UdpTransportFactory sf = serverFactory(DtlsVersion.DTLS_1_3);
        UdpEndpoint server = endpoint(new Peer(), sf, loop, false);
        Dtls13Session serverSession = new Dtls13Session(sf.getSharedServerConfig13(), server, PEER);
        serverSession.beginHandshake();
        assertTrue(server.pendingDatagrams.isEmpty());
    }

    @Test
    public void dtls12UnansweredHandshakeRetransmitsThenFails() {
        ManualLoop loop = new ManualLoop();
        Peer peer = new Peer();
        UdpTransportFactory cf = clientFactory(DtlsVersion.DTLS_1_2);
        UdpEndpoint ep = endpoint(peer, cf, loop, true);
        Dtls12Session session = new Dtls12Session(cf.buildClientConfig12("localhost"), ep, PEER);
        session.beginHandshake();
        int first = ep.pendingDatagrams.size();
        assertTrue(first > 0);
        clear(ep);
        int fired = 0;
        while (loop.fireLatest() && fired < 100) {
            fired++;
            assertTrue("each timeout resends the flight", fired == 0 || !ep.pendingDatagrams.isEmpty()
                    || !peer.errors.isEmpty());
            clear(ep);
        }
        assertTrue(fired > 1);
        assertEquals(1, peer.errors.size());
        session.receive(new byte[] {22, 1, 2, 3});
        session.close();
        session.close();
        session.beginHandshake();
        assertTrue(ep.pendingDatagrams.isEmpty());
        assertFalse(loop.fireLatest());
    }

    @Test
    public void dtls13UnansweredHandshakeRetransmitsThenFails() {
        ManualLoop loop = new ManualLoop();
        Peer peer = new Peer();
        UdpTransportFactory cf = clientFactory(DtlsVersion.DTLS_1_3);
        UdpEndpoint ep = endpoint(peer, cf, loop, true);
        Dtls13Session session = new Dtls13Session(cf.buildClientConfig13("localhost"), ep, PEER);
        session.beginHandshake();
        assertTrue(ep.pendingDatagrams.size() > 0);
        clear(ep);
        int fired = 0;
        while (loop.fireLatest() && fired < 100) {
            fired++;
            clear(ep);
        }
        assertTrue(fired > 1);
        assertEquals(1, peer.errors.size());
        session.receive(new byte[] {22, 1, 2, 3});
        session.close();
        session.close();
        session.beginHandshake();
        assertTrue(ep.pendingDatagrams.isEmpty());
        assertFalse(loop.fireLatest());
    }

    @Test
    public void dtls12ServerSessionSlicesTheDatagramForTheCookieExchange() {
        ManualLoop loop = new ManualLoop();
        UdpTransportFactory sf = new UdpTransportFactory();
        sf.setSecure(true);
        sf.setDtlsVersion(DtlsVersion.DTLS_1_2);
        sf.setServerCredentials(identity.credentials());
        sf.setRequireCookie(true);
        sf.setCookieSecret(new byte[32]);
        sf.start();
        UdpEndpoint server = endpoint(new Peer(), sf, loop, false);
        Dtls12Session session = new Dtls12Session(sf.getSharedServerConfig(), server, PEER);
        byte[] padded = new byte[40];
        session.receive(padded, 3, 20);
        assertTrue("an unparseable hello is dropped silently", server.pendingDatagrams.isEmpty());
        session.close();
    }

    @Test
    public void peerClosedRemovesTheSession() {
        ManualLoop loop = new ManualLoop();
        UdpTransportFactory cf = clientFactory(DtlsVersion.DTLS_1_2);
        UdpEndpoint ep = endpoint(new Peer(), cf, loop, true);
        Dtls12Session session = new Dtls12Session(cf.buildClientConfig12("localhost"), ep, PEER);
        session.peerClosed();
        session.receive(new byte[] {1});
        assertTrue(ep.pendingDatagrams.isEmpty());
    }
}
