/*
 * QuicStreamEndToEndTest.java
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import tech.kwik.agent15.engine.TlsServerEngineFactory;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * After completing a handshake (see {@link QuicHandshakeEndToEndTest}),
 * opens a client-initiated bidirectional stream and exchanges a
 * request/response pair over it -- the same shape DNS-over-QUIC uses
 * (RFC 9250 section 4.2: one query-response pair per bidirectional
 * stream, FIN after each side's message) -- proving that STREAM frames,
 * the transport-parameters-derived flow control limits, and 1-RTT
 * packet protection all work together.
 *
 * <p>See {@link QuicTestPeer}'s documentation for what this test
 * harness deliberately does not exercise: dynamic flow control window
 * growth (MAX_DATA/MAX_STREAM_DATA are parsed but not acted on), and
 * anything requiring more than one packet per direction per level.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250#section-4.2">RFC 9250 section 4.2</a>
 */
public class QuicStreamEndToEndTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static TlsServerEngineFactory serverCertificateFactory;

    @BeforeClass
    public static void generateServerCertificate() throws Exception {
        certsDirectory = Files.createTempDirectory("quic-stream-test");
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
                    .forEach(QuicStreamEndToEndTest::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    @Test
    public void testClientStreamRequestServerStreamResponse() throws Exception {
        byte[] clientInitialDcid = QuicHandshakeEndToEndTest.randomConnectionId();
        byte[] clientScid = QuicHandshakeEndToEndTest.randomConnectionId();
        byte[] serverScid = QuicHandshakeEndToEndTest.randomConnectionId();

        QuicTestPeer client = QuicTestPeer.newClient(
                clientInitialDcid, QuicHandshakeEndToEndTest.defaultTransportParameters(clientScid));
        QuicTestPeer server = QuicTestPeer.newServer(clientInitialDcid,
                QuicHandshakeEndToEndTest.defaultTransportParameters(serverScid), serverCertificateFactory);

        QuicTestPeer.completeHandshake(client, server, clientInitialDcid, clientScid, serverScid, SERVER_NAME);
        assertTrue("Handshake should have been confirmed before opening a stream", client.handshakeConfirmed);

        byte[] query = "example.com A?".getBytes(StandardCharsets.US_ASCII);
        long streamId = client.openBidiStream();
        client.queueStreamData(streamId, query, true);

        byte[] clientStreamDatagram = client.buildPacket(EncryptionLevel.ONE_RTT,
                serverScid, null, false, false, 0);
        server.receiveDatagram(clientStreamDatagram);

        assertArrayEquals("Server should have received the client's full query", query,
                server.getReceivedStreamData(streamId));
        assertTrue("Server should have seen FIN on the query", server.isStreamFinReceived(streamId));

        byte[] response = "93.184.216.34".getBytes(StandardCharsets.US_ASCII);
        server.queueStreamData(streamId, response, true);

        byte[] serverStreamDatagram = server.buildPacket(EncryptionLevel.ONE_RTT,
                clientScid, null, false, false, 0);
        client.receiveDatagram(serverStreamDatagram);

        assertArrayEquals("Client should have received the server's full response", response,
                client.getReceivedStreamData(streamId));
        assertTrue("Client should have seen FIN on the response", client.isStreamFinReceived(streamId));
    }

    @Test
    public void testMultipleStreamsAreIndependent() throws Exception {
        byte[] clientInitialDcid = QuicHandshakeEndToEndTest.randomConnectionId();
        byte[] clientScid = QuicHandshakeEndToEndTest.randomConnectionId();
        byte[] serverScid = QuicHandshakeEndToEndTest.randomConnectionId();

        QuicTestPeer client = QuicTestPeer.newClient(
                clientInitialDcid, QuicHandshakeEndToEndTest.defaultTransportParameters(clientScid));
        QuicTestPeer server = QuicTestPeer.newServer(clientInitialDcid,
                QuicHandshakeEndToEndTest.defaultTransportParameters(serverScid), serverCertificateFactory);

        QuicTestPeer.completeHandshake(client, server, clientInitialDcid, clientScid, serverScid, SERVER_NAME);

        long streamA = client.openBidiStream();
        long streamB = client.openBidiStream();
        byte[] dataA = "query A".getBytes(StandardCharsets.US_ASCII);
        byte[] dataB = "query B".getBytes(StandardCharsets.US_ASCII);
        client.queueStreamData(streamA, dataA, true);
        client.queueStreamData(streamB, dataB, true);

        byte[] datagram = client.buildPacket(EncryptionLevel.ONE_RTT, serverScid, null, false, false, 0);
        server.receiveDatagram(datagram);

        assertArrayEquals(dataA, server.getReceivedStreamData(streamA));
        assertArrayEquals(dataB, server.getReceivedStreamData(streamB));
        assertTrue(server.isStreamFinReceived(streamA));
        assertTrue(server.isStreamFinReceived(streamB));
    }
}
