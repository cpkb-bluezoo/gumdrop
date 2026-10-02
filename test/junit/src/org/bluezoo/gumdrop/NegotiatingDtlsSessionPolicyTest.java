/*
 * NegotiatingDtlsSessionPolicyTest.java
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

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The version policy of {@link NegotiatingDtlsSession}: a first flight whose
 * DTLS version the local policy forbids ends the session instead of
 * starting a handshake, and the pick between a DTLS 1.2 and a DTLS 1.3
 * session follows the first flight. The first flights come from real
 * endpoints, so no handshake bytes are forged.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NegotiatingDtlsSessionPolicyTest {

    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 5200);
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 5201);

    private static TestCertificates.Identity identity;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        identity = TestCertificates.ec256();
    }

    private static final class Recorder implements ProtocolHandler {
        final List<Exception> errors = new ArrayList<Exception>();
        boolean secure;

        @Override
        public void receive(ByteBuffer data) {
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

    private static UdpTransportFactory serverFactory(DtlsVersion version) {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setDtlsVersion(version);
        f.setServerCredentials(identity.credentials());
        f.start();
        return f;
    }

    private static UdpTransportFactory clientFactory(DtlsVersion version) {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setSecure(true);
        f.setDtlsVersion(version);
        f.setTrustManager(TestCertificates.trustAll());
        f.start();
        return f;
    }

    private static UdpEndpoint endpoint(Recorder h, UdpTransportFactory f, boolean clientMode) {
        UdpEndpoint ep = new UdpEndpoint(h);
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

    private static byte[] takeDatagram(UdpEndpoint ep) {
        UdpEndpoint.PendingDatagram p = ep.pendingDatagrams.poll();
        if (p == null) {
            return null;
        }
        byte[] b = new byte[p.data.remaining()];
        p.data.get(b);
        ep.onPendingDatagramFullySent(p);
        return b;
    }

    /** The datagrams of the first flight a client of the given version sends. */
    private static List<byte[]> clientHello(DtlsVersion version) {
        Recorder h = new Recorder();
        UdpEndpoint client = endpoint(h, clientFactory(version), true);
        client.startClientDtlsHandshake();
        List<byte[]> flight = new ArrayList<byte[]>();
        byte[] d = takeDatagram(client);
        while (d != null) {
            flight.add(d);
            d = takeDatagram(client);
        }
        return flight;
    }

    private static void feed(NegotiatingDtlsSession s, List<byte[]> flight) {
        for (int i = 0; i < flight.size(); i++) {
            s.receive(flight.get(i));
        }
    }

    /** The first datagram a DTLS 1.2 server answers a DTLS 1.2 ClientHello with. */
    private static byte[] dtls12ServerFlight() {
        UdpEndpoint server = endpoint(new Recorder(), serverFactory(DtlsVersion.DTLS_1_2), false);
        List<byte[]> hello = clientHello(DtlsVersion.DTLS_1_2);
        for (int i = 0; i < hello.size(); i++) {
            server.netReceive(ByteBuffer.wrap(hello.get(i)), CLIENT_ADDR);
        }
        return takeDatagram(server);
    }

    private static NegotiatingDtlsSession session(UdpEndpoint ep, UdpTransportFactory f,
            DtlsVersion policy, boolean client) {
        if (client) {
            return new NegotiatingDtlsSession(ep, SERVER_ADDR, policy,
                    f.buildClientConfig12("localhost"), f.buildClientConfig13("localhost"), true);
        }
        return new NegotiatingDtlsSession(ep, CLIENT_ADDR, policy,
                f.getSharedServerConfig(), f.getSharedServerConfig13(), false);
    }

    @Test
    public void serverRefusesADtls13FirstFlightWhenOnlyDtls12IsAllowed() {
        UdpTransportFactory f = serverFactory(DtlsVersion.NEGOTIATE);
        UdpEndpoint ep = endpoint(new Recorder(), f, false);
        NegotiatingDtlsSession s = session(ep, f, DtlsVersion.DTLS_1_2, false);
        feed(s, clientHello(DtlsVersion.DTLS_1_3));
        assertTrue(ep.pendingDatagrams.isEmpty());
        assertFalse(s.isHandshakeComplete());
        assertNull(s.getSecurityInfo());
    }

    @Test
    public void serverRefusesADtls12FirstFlightWhenOnlyDtls13IsAllowed() {
        UdpTransportFactory f = serverFactory(DtlsVersion.NEGOTIATE);
        UdpEndpoint ep = endpoint(new Recorder(), f, false);
        NegotiatingDtlsSession s = session(ep, f, DtlsVersion.DTLS_1_3, false);
        feed(s, clientHello(DtlsVersion.DTLS_1_2));
        assertTrue(ep.pendingDatagrams.isEmpty());
        assertFalse(s.isHandshakeComplete());
    }

    @Test
    public void serverAnswersAnAllowedDtls13FirstFlight() {
        DtlsVersion[] versions = new DtlsVersion[] {DtlsVersion.DTLS_1_3};
        for (int i = 0; i < versions.length; i++) {
            UdpTransportFactory f = serverFactory(DtlsVersion.NEGOTIATE);
            UdpEndpoint ep = endpoint(new Recorder(), f, false);
            NegotiatingDtlsSession s = session(ep, f, DtlsVersion.NEGOTIATE, false);
            feed(s, clientHello(versions[i]));
            assertFalse(versions[i].toString(), ep.pendingDatagrams.isEmpty());
            s.close();
        }
    }

    @Test
    public void serverKeepsWaitingWhenTheFirstFlightIsIncomplete() {
        UdpTransportFactory f = serverFactory(DtlsVersion.NEGOTIATE);
        UdpEndpoint ep = endpoint(new Recorder(), f, false);
        NegotiatingDtlsSession s = session(ep, f, DtlsVersion.NEGOTIATE, false);
        s.receive(new byte[] {22, (byte) 0xfe, (byte) 0xfd});
        assertTrue(ep.pendingDatagrams.isEmpty());
        assertFalse(s.isHandshakeComplete());
        assertNull(s.getSecurityInfo());
        s.close();
    }

    @Test
    public void clientRefusesADtls12ServerHelloWhenOnlyDtls13IsAllowed() {
        UdpTransportFactory f = clientFactory(DtlsVersion.NEGOTIATE);
        Recorder h = new Recorder();
        UdpEndpoint ep = endpoint(h, f, true);
        NegotiatingDtlsSession s = session(ep, f, DtlsVersion.DTLS_1_3, true);
        s.beginHandshake();
        assertNotNull(takeDatagram(ep));
        s.receive(dtls12ServerFlight());
        assertEquals(1, h.errors.size());
        assertFalse(h.secure);
    }

    @Test
    public void clientContinuesAsDtls12WhenTheServerPicksIt() {
        UdpTransportFactory f = clientFactory(DtlsVersion.NEGOTIATE);
        Recorder h = new Recorder();
        UdpEndpoint ep = endpoint(h, f, true);
        NegotiatingDtlsSession s = session(ep, f, DtlsVersion.NEGOTIATE, true);
        s.beginHandshake();
        takeDatagram(ep);
        s.receive(dtls12ServerFlight());
        assertFalse(s.isHandshakeComplete());
        s.close();
    }
}
