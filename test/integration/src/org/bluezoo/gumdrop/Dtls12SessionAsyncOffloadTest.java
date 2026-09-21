/*
 * Dtls12SessionAsyncOffloadTest.java
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

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.tls.Dtls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link Dtls12Session} with {@link TlsHandshakeAsyncOffload} (issue #274):
 * the session must flush outbound flights after async handshake batches finish,
 * not only from the synchronous tail of {@code beginHandshake()} /
 * {@code receive()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Dtls12SessionAsyncOffloadTest {

    private static final String PASSWORD = "testpass";
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 1);
    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 2);
    private static final String SERVER_NAME = "localhost";

    private static List<X509Certificate> chain;
    private static PrivateKey privateKey;

    private Gumdrop gumdrop;

    @BeforeClass
    public static void generateKeystore() throws Exception {
        Path keystorePath = Files.createTempFile("dtls12-async-offload-keystore", ".p12");
        Files.delete(keystorePath);
        Process process = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "dtlstest",
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "30",
                "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:" + SERVER_NAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-keypass", PASSWORD)
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        chain = Collections.singletonList((X509Certificate) keyStore.getCertificate("dtlstest"));
        privateKey = (PrivateKey) keyStore.getKey("dtlstest", PASSWORD.toCharArray());
        Files.delete(keystorePath);
    }

    @Before
    public void startGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
    }

    @After
    public void stopGumdrop() {
        CryptoExecutor.loopCallbackObserver = null;
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
        gumdrop = null;
    }

    private static Dtls12HandshakeConfig clientConfig() throws Exception {
        Tls12HandshakeConfig base = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        base.setServerName(SERVER_NAME);
        base.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        return new Dtls12HandshakeConfig(base);
    }

    private static Dtls12HandshakeConfig serverConfig() {
        Tls12HandshakeConfig base = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        base.setServerCredentials(new ServerCredentials(chain, privateKey));
        return new Dtls12HandshakeConfig(base);
    }

    private static final class RecordingEndpoint extends UdpEndpoint {
        final Deque<ByteBuffer> sent = new ArrayDeque<ByteBuffer>();
        SecurityInfo securityInfo;

        RecordingEndpoint(SelectorLoop loop) {
            super(new ProtocolHandler() {
                @Override public void receive(ByteBuffer data) { }
                @Override public void connected(Endpoint endpoint) { }
                @Override public void disconnected() { }
                @Override public void securityEstablished(SecurityInfo info) { }
                @Override public void error(Exception cause) { }
            });
            setSelectorLoop(loop);
        }

        @Override
        void sendOwnedRawDatagram(ByteBuffer data, InetSocketAddress dest) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            sent.add(copy);
        }

        @Override
        void notifyDtlsHandshakeComplete(InetSocketAddress peer, SecurityInfo info) {
            this.securityInfo = info;
        }
    }

    private static boolean relayQueuedDatagrams(RecordingEndpoint clientEp, Dtls12Session server,
            RecordingEndpoint serverEp, Dtls12Session client) {
        boolean moved = false;
        ByteBuffer buf;
        while ((buf = clientEp.sent.poll()) != null) {
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            server.receive(data);
            moved = true;
        }
        while ((buf = serverEp.sent.poll()) != null) {
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            client.receive(data);
            moved = true;
        }
        return moved;
    }

    private static void pumpWithAsyncOffload(RecordingEndpoint clientEp, Dtls12Session client,
            RecordingEndpoint serverEp, Dtls12Session server) throws InterruptedException {
        final AtomicReference<CountDownLatch> step =
                new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        CryptoExecutor.loopCallbackObserver = new Runnable() {
            @Override
            public void run() {
                CountDownLatch latch = step.get();
                if (latch != null) {
                    latch.countDown();
                }
            }
        };
        try {
            for (int round = 0; round < 96; round++) {
                if (relayQueuedDatagrams(clientEp, server, serverEp, client)) {
                    if (client.isHandshakeComplete() && server.isHandshakeComplete()) {
                        return;
                    }
                    continue;
                }
                if (client.isHandshakeComplete() && server.isHandshakeComplete()) {
                    return;
                }
                CountDownLatch await = step.get();
                if (!await.await(5, TimeUnit.SECONDS)) {
                    return;
                }
                step.set(new CountDownLatch(1));
            }
        } finally {
            CryptoExecutor.loopCallbackObserver = null;
        }
    }

    @Test(timeout = 20000)
    public void handshakeCompletesWithCryptoExecutorOffload() throws Exception {
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        RecordingEndpoint clientEp = new RecordingEndpoint(loop);
        RecordingEndpoint serverEp = new RecordingEndpoint(loop);
        Dtls12Session client = new Dtls12Session(clientConfig(), clientEp, SERVER_ADDR);
        Dtls12Session server = new Dtls12Session(serverConfig(), serverEp, CLIENT_ADDR);
        client.beginHandshake();
        pumpWithAsyncOffload(clientEp, client, serverEp, server);
        assertTrue(client.isHandshakeComplete());
        assertTrue(server.isHandshakeComplete());
        assertNotNull(clientEp.securityInfo);
        assertEquals("DTLSv1.2", clientEp.securityInfo.getProtocol());
    }
}
