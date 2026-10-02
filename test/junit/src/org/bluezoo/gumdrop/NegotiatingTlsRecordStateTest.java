/*
 * NegotiatingTlsRecordStateTest.java
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

import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordEngine;
import org.bluezoo.gumdrop.tls.TlsRecordSink;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.junit.Test;

/**
 * Regression tests for {@link NegotiatingTlsRecordState}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NegotiatingTlsRecordStateTest {

    private static final class ClientHelloSink implements TlsRecordSink {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();

        @Override
        public void ciphertextReady(byte[] data) {
            wire.write(data, 0, data.length);
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

    private static final class RecordingCallback implements TlsRecordState.Callback {
        TlsProtocolError error;

        @Override
        public void onApplicationData(ByteBuffer data) {
        }

        @Override
        public void onHandshakeComplete(String protocol) {
        }

        @Override
        public void onClosed() {
        }

        @Override
        public void onProtocolError(TlsProtocolError error) {
            this.error = error;
        }

        @Override
        public Object getRemoteAddress() {
            return "test";
        }
    }

    /**
     * After the first flight selects TLS 1.3, only the bytes the peer
     * actually sent may reach the engine. The activating read used to
     * unwrap the endpoint's already-compacted (write mode) netIn a second
     * time, feeding its whole spare capacity as garbage ciphertext and
     * failing every handshake with BAD_RECORD_MAC.
     */
    @Test
    public void activatingReadDoesNotFeedSpareNetInCapacity() throws Exception {
        HandshakeConfig clientConfig = new HandshakeConfig(HandshakeRole.CLIENT);
        clientConfig.setServerName(TestCertificates.SERVER_NAME);
        TlsRecordEngine client = new TlsRecordEngine(clientConfig);
        ClientHelloSink clientSink = new ClientHelloSink();
        client.start(clientSink);
        byte[] clientHello = clientSink.wire.toByteArray();

        HandshakeConfig serverConfig = new HandshakeConfig(HandshakeRole.SERVER);
        serverConfig.setServerCredentials(TestCertificates.ec256().credentials());
        TcpEndpoint endpoint = new TcpEndpoint(new NullHandler(), serverConfig, null,
                TlsVersion.NEGOTIATE, false);
        RecordingCallback callback = new RecordingCallback();
        NegotiatingTlsRecordState state = new NegotiatingTlsRecordState(serverConfig, null,
                TlsVersion.NEGOTIATE, endpoint, callback, false);
        ByteBuffer netIn = ByteBuffer.allocate(32768);
        netIn.put(clientHello);
        netIn.flip();
        endpoint.netIn = netIn;

        state.unwrap();

        assertNull(callback.error == null ? null : callback.error.getMessage(), callback.error);
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
}
