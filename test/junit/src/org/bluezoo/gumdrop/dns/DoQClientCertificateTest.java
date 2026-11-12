/*
 * DnsQueryContextTest.java
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

package org.bluezoo.gumdrop.dns;

import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicLoopback;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

import static org.junit.Assert.*;

/**
 * Over DoQ, as over DoT, a handler can see the certificate chain a client
 * presented: it is on the {@link DnsQueryContext}'s security parameters of
 * every query, on every stream of the connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQClientCertificateTest {

    private static final class Seen implements DnsQueryHandler {
        final List<List<Certificate>> chains = new ArrayList<List<Certificate>>();
        final List<String> serverNames = new ArrayList<String>();

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback callback) {
            callback.onResponse(query.createResponse(Collections.<DnsResourceRecord>emptyList()));
        }

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryContext context,
                                DnsQueryCallback callback) {
            Certificate[] chain = context.getSecurityInfo().getPeerCertificates();
            chains.add(chain != null ? Arrays.asList(chain) : null);
            serverNames.add(context.getSecurityInfo().getServerName());
            callback.onResponse(query.createResponse(Collections.<DnsResourceRecord>emptyList()));
        }
    }

    /** A client stream that does nothing with the answer. */
    private static final class Quiet implements ProtocolHandler {
        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
        }

        @Override
        public void securityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
        }
    }

    private static final class Connection implements QuicEngine.ConnectionAcceptedHandler {
        QuicConnection conn;

        @Override
        public void connectionAccepted(QuicConnection connection) {
            conn = connection;
        }
    }

    private static ByteBuffer framedQuery(String name) {
        // RFC 9250 section 4.2.1: the message ID is 0 on the wire
        ByteBuffer wire = DnsMessage.createQuery(0, name, DnsType.A).serialize();
        ByteBuffer out = ByteBuffer.allocate(2 + wire.remaining());
        out.put((byte) (wire.remaining() >> 8));
        out.put((byte) wire.remaining());
        out.put(wire);
        out.flip();
        return out;
    }

    private Seen run(TestCertificates.ExtendedKeyUsage usage, boolean present) throws Exception {
        QuicLoopback lb = new QuicLoopback();
        TestCertificates.Identity ca =
                TestCertificates.newCa(TestCertificates.KeyKind.EC_P256, "doq-ca");
        TestCertificates.Identity client = TestCertificates.newIssued(ca,
                TestCertificates.KeyKind.EC_P256, "doq-client", usage);
        expected = client.getChain();
        lb.serverFactory.setApplicationProtocols("doq");
        lb.clientFactory.setApplicationProtocols("doq");
        lb.serverFactory.setNeedClientAuth(true);
        lb.serverFactory.setTrustManager(ca.trustManager());
        if (present) {
            lb.clientFactory.setServerCredentials(client.credentials());
        }
        lb.startFactories();
        final Seen seen = new Seen();
        final DnsServer dns = new DnsServer();
        dns.handler(seen);
        lb.startServer(new StreamAcceptHandler() {
            @Override
            public ProtocolHandler acceptStream(Endpoint stream) {
                return new DoQStreamHandler(dns);
            }
        });
        Connection connection = new Connection();
        lb.startClient(null, connection);
        lb.pump();
        lb.pump();
        if (connection.conn != null && connection.conn.isEstablished()) {
            for (int i = 0; i < 3; i++) {
                Endpoint stream = connection.conn.openStream(new Quiet());
                stream.send(framedQuery("q" + i + ".example.com"));
                stream.close();
                lb.pump();
            }
            lb.pump();
        }
        return seen;
    }

    private List<java.security.cert.X509Certificate> expected;

    @Test
    public void everyQueryOnTheConnectionSeesTheClientsChain() throws Exception {
        Seen seen = run(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, true);
        assertEquals("three queries, on three streams", 3, seen.chains.size());
        for (List<Certificate> chain : seen.chains) {
            assertNotNull(chain);
            assertEquals(expected, chain);
        }
        for (String name : seen.serverNames) {
            assertEquals("localhost", name);
        }
    }

    @Test
    public void aClientWithoutACertificateGetsNoAnswers() throws Exception {
        Seen seen = run(TestCertificates.ExtendedKeyUsage.CLIENT_ONLY, false);
        assertTrue(seen.chains.isEmpty());
    }

    @Test
    public void aCertificateOnlyForServerAuthenticationGetsNoAnswers() throws Exception {
        Seen seen = run(TestCertificates.ExtendedKeyUsage.SERVER_ONLY, true);
        assertTrue(seen.chains.isEmpty());
    }
}
