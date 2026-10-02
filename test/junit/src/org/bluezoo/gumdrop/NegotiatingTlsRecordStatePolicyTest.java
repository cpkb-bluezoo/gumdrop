/*
 * NegotiatingTlsRecordStatePolicyTest.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Tls12RecordEngine;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordEngine;
import org.bluezoo.gumdrop.tls.TlsRecordSink;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Test;

/**
 * The version policy of {@link NegotiatingTlsRecordState}: a first flight
 * whose version the local policy forbids is a protocol error, a malformed
 * first flight is reported rather than thrown, and the active-engine
 * accessors follow the pick. The first flights are produced by real record
 * engines, so no handshake bytes are forged.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NegotiatingTlsRecordStatePolicyTest {

    private static final class Wire implements TlsRecordSink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public void ciphertextReady(byte[] data) {
            out.write(data, 0, data.length);
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
        }

        @Override
        public void handshakeComplete() {
        }

        @Override
        public void protocolError(TlsProtocolError error) {
        }

        @Override
        public void peerClosed() {
        }
    }

    private static final class Callback implements TlsRecordState.Callback {
        final List<TlsProtocolError> errors = new ArrayList<TlsProtocolError>();
        int closed;
        String protocol;

        @Override
        public void onApplicationData(ByteBuffer data) {
        }

        @Override
        public void onHandshakeComplete(String protocol) {
            this.protocol = protocol;
        }

        @Override
        public void onClosed() {
            closed++;
        }

        @Override
        public void onProtocolError(TlsProtocolError error) {
            errors.add(error);
        }

        @Override
        public Object getRemoteAddress() {
            return "test";
        }
    }

    private static final class NullHandler implements ProtocolHandler {
        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
        }
    }

    private static HandshakeConfig client13() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.ec256().trustManager());
        return c;
    }

    private static HandshakeConfig server13() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TestCertificates.ec256().credentials());
        return c;
    }

    private static Tls12HandshakeConfig client12() throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TestCertificates.SERVER_NAME);
        c.setTrustManager(TestCertificates.ec256().trustManager());
        return c;
    }

    private static Tls12HandshakeConfig server12() throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TestCertificates.ec256().credentials());
        return c;
    }

    private static byte[] tls13ClientHello() throws Exception {
        TlsRecordEngine engine = new TlsRecordEngine(client13());
        Wire wire = new Wire();
        engine.start(wire);
        return wire.out.toByteArray();
    }

    private static byte[] tls12ClientHello() throws Exception {
        Tls12RecordEngine engine = new Tls12RecordEngine(client12());
        Wire wire = new Wire();
        engine.start(wire);
        return wire.out.toByteArray();
    }

    /** What a TLS 1.2 server says in reply to a TLS 1.2 ClientHello, starting with its ServerHello. */
    private static byte[] tls12ServerFlight() throws Exception {
        Tls12RecordEngine server = new Tls12RecordEngine(server12());
        Wire wire = new Wire();
        server.feedCiphertext(tls12ClientHello(), wire);
        return wire.out.toByteArray();
    }

    private static TcpEndpoint endpointHolding(byte[] bytes) throws Exception {
        TcpEndpoint ep = new TcpEndpoint(new NullHandler());
        ep.netIn = ByteBuffer.allocate(Math.max(32768, bytes.length));
        ep.netIn.put(bytes);
        ep.netIn.flip();
        return ep;
    }

    private static NegotiatingTlsRecordState state(TlsVersion policy, TcpEndpoint ep, Callback cb,
            boolean client) throws Exception {
        return new NegotiatingTlsRecordState(server13(), server12(), policy, ep, cb, client);
    }

    @Test
    public void serverRefusesATls13FirstFlightWhenOnlyTls12IsAllowed() throws Exception {
        TcpEndpoint ep = endpointHolding(tls13ClientHello());
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = state(TlsVersion.TLS_1_2, ep, cb, false);
        s.unwrap();
        assertEquals(1, cb.errors.size());
        assertFalse(s.isTls13Active());
        assertNull(s.getActiveTls12Engine());
        assertNull(s.getActiveTls13Engine());
    }

    @Test
    public void serverRefusesATls12FirstFlightWhenOnlyTls13IsAllowed() throws Exception {
        TcpEndpoint ep = endpointHolding(tls12ClientHello());
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = state(TlsVersion.TLS_1_3, ep, cb, false);
        s.unwrap();
        assertEquals(1, cb.errors.size());
    }

    @Test
    public void serverActivatesTls12WhenAllowedAndExposesTheEngine() throws Exception {
        TcpEndpoint ep = endpointHolding(tls12ClientHello());
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = state(TlsVersion.NEGOTIATE, ep, cb, false);
        s.unwrap();
        assertTrue(cb.errors.isEmpty());
        assertNotNull(s.getActiveTls12Engine());
        assertFalse(s.isTls13Active());
        s.closeOutbound();
        s.wrap(ByteBuffer.wrap(new byte[] {1}));
    }

    @Test
    public void serverActivatesTls13WhenAllowedAndExposesTheEngine() throws Exception {
        TcpEndpoint ep = endpointHolding(tls13ClientHello());
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = state(TlsVersion.NEGOTIATE, ep, cb, false);
        s.unwrap();
        assertTrue(cb.errors.isEmpty());
        assertTrue(s.isTls13Active());
        assertNotNull(s.getActiveTls13Engine());
        assertNull(s.getActiveTls12Engine());
        s.closeOutbound();
    }

    @Test
    public void serverReportsAMalformedFirstFlight() throws Exception {
        byte[] junk = new byte[] {0x16, 0x03, 0x03, 0x00, 0x30, 0x01, 0x00, 0x00, 0x2c, 0x03, 0x03,
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            25, 26, 27, 28, 29, 30, 31, 32, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0};
        TcpEndpoint ep = endpointHolding(junk);
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = state(TlsVersion.NEGOTIATE, ep, cb, false);
        s.unwrap();
        assertEquals(1, cb.errors.size());
    }

    @Test
    public void clientRefusesATls12ServerHelloWhenOnlyTls13IsAllowed() throws Exception {
        TcpEndpoint ep = endpointHolding(tls12ServerFlight());
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = new NegotiatingTlsRecordState(client13(), client12(),
                TlsVersion.TLS_1_3, ep, cb, true);
        s.startClientHandshake();
        s.startClientHandshake();
        assertNotNull(s.getActiveTls13Engine());
        s.unwrap();
        assertEquals(1, cb.errors.size());
    }

    @Test
    public void clientFollowsATls12ServerHelloWhenAllowed() throws Exception {
        TcpEndpoint ep = endpointHolding(tls12ServerFlight());
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = new NegotiatingTlsRecordState(client13(), client12(),
                TlsVersion.NEGOTIATE, ep, cb, true);
        s.startClientHandshake();
        s.unwrap();
        assertNotNull(s.getActiveTls12Engine());
        assertFalse(s.isTls13Active());
    }

    @Test
    public void serverSideStateIgnoresClientHandshakeRequests() throws Exception {
        TcpEndpoint ep = endpointHolding(new byte[0]);
        Callback cb = new Callback();
        NegotiatingTlsRecordState s = state(TlsVersion.NEGOTIATE, ep, cb, false);
        s.startClientHandshake();
        assertNull(s.getActiveTls13Engine());
        s.unwrap();
        assertTrue(cb.errors.isEmpty());
    }
}
