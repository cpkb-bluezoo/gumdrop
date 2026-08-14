/*
 * QuicHandshakeEndToEndTest.java
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import tech.kwik.agent15.engine.TlsServerEngineFactory;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives a complete QUIC handshake to {@code HANDSHAKE_DONE} between two
 * in-process peers -- a client and a server, talking only through Java
 * byte arrays, no sockets -- exercising every piece built for Stage 1 of
 * the quiche/BoringSSL replacement together: {@code quic.tls} (Agent15
 * wiring), {@code quic.packet} (packet protection, header layout,
 * transport parameters), and {@code quic.frame} (CRYPTO/ACK/HANDSHAKE_DONE).
 * See {@link QuicTestPeer}'s documentation for what this driver
 * deliberately does not do.
 *
 * <p>The exact message sequence below (which packet carries which
 * message, and at which encryption level) follows RFC 9001 section 4.1
 * directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.1">RFC 9001 section 4.1</a>
 */
public class QuicHandshakeEndToEndTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static TlsServerEngineFactory serverCertificateFactory;

    @BeforeClass
    public static void generateServerCertificate() throws Exception {
        certsDirectory = Files.createTempDirectory("quic-handshake-test");
        Path keystorePath = certsDirectory.resolve("server.p12");

        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=" + SERVER_NAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            fail("keytool failed to generate a test certificate");
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        serverCertificateFactory = new TlsServerEngineFactory(keyStore, "server", "changeit".toCharArray());
    }

    @AfterClass
    public static void deleteServerCertificate() throws IOException {
        if (certsDirectory != null) {
            Files.walk(certsDirectory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(QuicHandshakeEndToEndTest::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    static byte[] randomConnectionId() {
        byte[] id = new byte[QuicTestPeer.CONNECTION_ID_LENGTH];
        new SecureRandom().nextBytes(id);
        return id;
    }

    static TransportParameters defaultTransportParameters(byte[] initialSourceConnectionId) {
        TransportParameters params = new TransportParameters();
        params.setMaxIdleTimeout(30000);
        params.setInitialMaxData(10_000_000);
        params.setInitialMaxStreamDataBidiLocal(1_000_000);
        params.setInitialMaxStreamDataBidiRemote(1_000_000);
        params.setInitialMaxStreamDataUni(1_000_000);
        params.setInitialMaxStreamsBidi(10);
        params.setInitialMaxStreamsUni(10);
        params.setInitialSourceConnectionId(initialSourceConnectionId);
        return params;
    }

    @Test
    public void testHandshakeReachesHandshakeDone() throws Exception {
        byte[] clientInitialDcid = randomConnectionId();
        byte[] clientScid = randomConnectionId();
        byte[] serverScid = randomConnectionId();

        QuicTestPeer client = QuicTestPeer.newClient(clientInitialDcid, defaultTransportParameters(clientScid));
        QuicTestPeer server = QuicTestPeer.newServer(
                clientInitialDcid, defaultTransportParameters(serverScid), serverCertificateFactory);

        QuicTestPeer.completeHandshake(client, server, clientInitialDcid, clientScid, serverScid, SERVER_NAME);

        assertTrue("Server should have been ready to send HANDSHAKE_DONE", server.handshakeDoneReadyToSend);
        assertTrue("Client should have received HANDSHAKE_DONE", client.handshakeConfirmed);
        assertEquals("Client and server must have negotiated the same cipher suite",
                client.getSelectedCipher(), server.getSelectedCipher());
        assertTrue("Client should have received the server's certificate chain",
                !client.getServerCertificateChain().isEmpty());
        assertEquals("Server should have learned the client's transport parameters",
                1_000_000, server.peerTransportParameters.getInitialMaxStreamDataBidiLocal());
        assertEquals("Client should have learned the server's transport parameters",
                1_000_000, client.peerTransportParameters.getInitialMaxStreamDataBidiLocal());
    }
}
