/*
 * ClientConnectApplyTest.java
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

package org.bluezoo.gumdrop.quic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Acceptor;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * Mutual TLS over QUIC: a server that requires client certificates sees the
 * chain the client presented, on the connection and on every stream of it,
 * and refuses a client with none or with one not usable for client
 * authentication.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackClientCertificateTest {

    private static List<Certificate> asList(Certificate[] chain) {
        return Arrays.asList(chain);
    }

    private static final class Pair {
        final QuicLoopback lb;
        final ConnCapture server = new ConnCapture();
        final ConnCapture client = new ConnCapture();
        final TestCertificates.Identity clientId;

        Pair(TestCertificates.ExtendedKeyUsage usage, boolean present, boolean require)
                throws Exception {
            lb = new QuicLoopback();
            TestCertificates.Identity ca =
                    TestCertificates.newCa(TestCertificates.KeyKind.EC_P256, "quic-ca");
            clientId = TestCertificates.newIssued(ca, TestCertificates.KeyKind.EC_P256,
                    "quic-client", usage);
            if (require) {
                lb.serverFactory.setNeedClientAuth(true);
                lb.serverFactory.setTrustManager(ca.trustManager());
            }
            if (present) {
                // an endpoint's own credentials are those it presents
                lb.clientFactory.setServerCredentials(clientId.credentials());
            }
            lb.startFactories();
            lb.startServer(server);
            lb.startClient(null, client);
            lb.pump();
            lb.pump();
        }
    }

    @Test
    public void theServerSeesTheChainTheClientPresented() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, true, true);
        assertTrue("handshake completed", p.server.conn.isEstablished());
        SecurityInfo info = p.server.conn.getSecurityInfo();
        assertNotNull("a chain", info.getPeerCertificates());
        assertEquals(p.clientId.getChain(), asList(info.getPeerCertificates()));
    }

    @Test
    public void theChainIsOneListNotACopyPerCall() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, true, true);
        SecurityInfo info = p.server.conn.getSecurityInfo();
        assertNotNull(info.getPeerCertificateChain());
        assertSame(info.getPeerCertificateChain(), info.getPeerCertificateChain());
        assertEquals(p.clientId.getChain(), info.getPeerCertificateChain());
    }

    @Test
    public void everyStreamOfTheConnectionReportsTheSameChain() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, true, true);
        for (int i = 0; i < 3; i++) {
            p.client.conn.openStream(new Rec()).send(java.nio.ByteBuffer.wrap(new byte[] {'q'}));
            p.lb.pump();
        }
        Acceptor streams = p.server.bidi;
        assertEquals(3, streams.recs.size());
        for (Rec stream : streams.recs) {
            SecurityInfo info = stream.endpoint.getSecurityInfo();
            assertEquals(p.clientId.getChain(), asList(info.getPeerCertificates()));
        }
    }

    @Test
    public void theServerNameTheClientAskedForIsReported() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, true, true);
        assertEquals("localhost", p.server.conn.getSecurityInfo().getServerName());
    }

    @Test
    public void theClientStillSeesTheServersChain() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, true, true);
        Certificate[] seen = p.client.conn.getSecurityInfo().getPeerCertificates();
        assertNotNull(seen);
        assertEquals(1, seen.length);
    }

    @Test
    public void withoutClientAuthRequiredThereIsNoClientChain() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, false, false);
        assertTrue(p.server.conn.isEstablished());
        assertNull(p.server.conn.getSecurityInfo().getPeerCertificates());
        assertNull(p.server.conn.getSecurityInfo().getPeerCertificateChain());
    }

    @Test
    public void aClientWithoutACertificateIsRefused() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, false, true);
        // (the client has finished its own handshake by the time the
        // server decides, as in any TLS 1.3 client authentication)
        assertFalse("not established on the server", p.server.conn != null && p.server.conn.isEstablished());
    }

    @Test
    public void aCertificateOnlyForServerAuthenticationIsRefused() throws Exception {
        Pair p = new Pair(TestCertificates.ExtendedKeyUsage.SERVER_ONLY, true, true);
        assertFalse("not established on the server", p.server.conn != null && p.server.conn.isEstablished());
    }
}
