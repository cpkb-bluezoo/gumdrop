/*
 * QuicHandshakeAsyncOffloadTest.java
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

import org.bluezoo.gumdrop.CryptoExecutor;
import org.bluezoo.gumdrop.Gumdrop;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import tech.kwik.agent15.engine.TlsServerEngineFactory;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #300: {@code QuicTlsServerEngine}/
 * {@code QuicTlsClientEngine} ran Agent15's handshake processing --
 * {@code TlsMessageParser.parseAndProcessHandshakeMessage}, which is where
 * the actual ECDHE key-exchange math and certificate-chain
 * validation/signing happen -- entirely inline on whatever thread called
 * {@code receiveCryptoData}, with no {@link CryptoExecutor} offload at
 * all, unlike the equivalent fixes already made for TCP/TLS ({@code
 * SSLState}, issue #262) and DTLS ({@code DTLSSession}, issue #274).
 *
 * <p>Reuses {@link QuicTestPeer} and {@link QuicHandshakeEndToEndTest}'s
 * own certificate-generation and transport-parameters helpers, so a real
 * handshake between two real Agent15 engines is what's actually being
 * driven here, not a mock.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicHandshakeAsyncOffloadTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static TlsServerEngineFactory serverCertificateFactory;

    private Gumdrop gumdrop;

    @BeforeClass
    public static void generateServerCertificate() throws Exception {
        certsDirectory = Files.createTempDirectory("quic-async-offload-test");
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
            Files.walkFileTree(certsDirectory, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuietly(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
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

    @Before
    public void setUp() {
        CryptoExecutor.workThreadObserver = null;
        System.setProperty("gumdrop.workers", "1");
        gumdrop = Gumdrop.getInstance();
        gumdrop.setDrainTimeoutMs(0);
        if (!gumdrop.isStarted()) {
            gumdrop.start();
        }
        assertNotNull("CryptoExecutor must be available once Gumdrop has started",
                gumdrop.getCryptoExecutor());
    }

    @After
    public void tearDown() {
        CryptoExecutor.workThreadObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    @Test(timeout = 20000)
    public void testHandshakeDelegatesToCryptoExecutor() throws Exception {
        final List<String> observedThreads = new CopyOnWriteArrayList<String>();
        CryptoExecutor.workThreadObserver = new CryptoExecutor.WorkThreadObserver() {
            @Override
            public void observed(Thread worker) {
                observedThreads.add(worker.getName());
            }
        };

        byte[] clientInitialDcid = randomConnectionId();
        byte[] clientScid = randomConnectionId();
        byte[] serverScid = randomConnectionId();

        QuicTestPeer client = QuicTestPeer.newClient(clientInitialDcid, defaultTransportParameters(clientScid));
        QuicTestPeer server = QuicTestPeer.newServer(
                clientInitialDcid, defaultTransportParameters(serverScid), serverCertificateFactory);

        QuicTestPeer.completeHandshake(client, server, clientInitialDcid, clientScid, serverScid, SERVER_NAME);

        assertTrue("Server should have been ready to send HANDSHAKE_DONE", server.handshakeDoneReadyToSend);
        assertTrue("Client should have received HANDSHAKE_DONE", client.handshakeConfirmed);

        assertFalse("handshake processing must run through CryptoExecutor -- "
                + "the work-thread observer was never invoked, meaning Agent15's "
                + "handshake processing ran inline instead of being offloaded",
                observedThreads.isEmpty());
        for (String name : observedThreads) {
            assertTrue("delegated handshake processing ran on unexpected thread: " + name,
                    name.startsWith("gumdrop-crypto-"));
        }
    }
}
