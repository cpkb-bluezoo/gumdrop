/*
 * DTLSSendNoCopyTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #328: secure {@link UDPEndpoint#sendTo} must
 * hand {@link DTLSSession#wrap}'s freshly pooled buffer directly to the
 * wire queue instead of copying it again inside
 * {@link UDPEndpoint#sendRawDatagram}.
 */
public class DTLSSendNoCopyTest {

    private static final String PASSWORD = "testpass";
    private static final InetSocketAddress CLIENT_ADDR =
            new InetSocketAddress("127.0.0.1", 1);
    private static final InetSocketAddress SERVER_ADDR =
            new InetSocketAddress("127.0.0.1", 2);

    private static Path keystorePath;
    private static Path truststorePath;

    @BeforeClass
    public static void generateKeystore() throws Exception {
        keystorePath = Files.createTempFile("dtls-send-nocopy-keystore", ".p12");
        Files.delete(keystorePath);
        runKeytool("-genkeypair",
                "-alias", "dtlstest",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "30",
                "-dname", "CN=localhost",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-keypass", PASSWORD);

        Path certPath = Files.createTempFile("dtls-send-nocopy-cert", ".pem");
        runKeytool("-exportcert",
                "-alias", "dtlstest",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-rfc",
                "-file", certPath.toString());

        truststorePath = Files.createTempFile("dtls-send-nocopy-truststore", ".p12");
        Files.delete(truststorePath);
        runKeytool("-importcert",
                "-alias", "dtlstest",
                "-file", certPath.toString(),
                "-keystore", truststorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-noprompt");
        Files.delete(certPath);
    }

    private static void runKeytool(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "keytool";
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append('\n');
        }
        assertEquals("keytool failed: " + output, 0, process.waitFor());
    }

    @AfterClass
    public static void deleteKeystore() throws Exception {
        if (keystorePath != null) {
            Files.deleteIfExists(keystorePath);
        }
        if (truststorePath != null) {
            Files.deleteIfExists(truststorePath);
        }
    }

    private static SSLContext buildServerContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("DTLSv1.2");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext buildClientContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(truststorePath)) {
            trustStore.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext context = SSLContext.getInstance("DTLSv1.2");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    private static final class TrackingEndpoint extends UDPEndpoint {
        final Deque<ByteBuffer> sent = new ArrayDeque<ByteBuffer>();
        final List<byte[]> received = new ArrayList<byte[]>();
        int rawDatagramSends;
        int ownedDatagramSends;
        ByteBuffer lastOwnedBuffer;

        TrackingEndpoint() {
            super(new ProtocolHandler() {
                @Override public void receive(ByteBuffer data) { }
                @Override public void connected(Endpoint endpoint) { }
                @Override public void disconnected() { }
                @Override public void securityEstablished(SecurityInfo info) { }
                @Override public void error(Exception cause) { }
            });
        }

        @Override
        void sendRawDatagram(ByteBuffer data, InetSocketAddress dest) {
            rawDatagramSends++;
            sent.addLast(copyDatagram(data));
        }

        @Override
        void sendOwnedRawDatagram(ByteBuffer data, InetSocketAddress dest) {
            ownedDatagramSends++;
            lastOwnedBuffer = data;
            sent.addLast(copyDatagram(data));
            ByteBufferPool.release(data);
        }

        private static ByteBuffer copyDatagram(ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            return copy;
        }
    }

    private static void pumpUntilQuiescent(TrackingEndpoint clientEp,
            DTLSSession clientSession, TrackingEndpoint serverEp,
            DTLSSession serverSession) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            while (!clientEp.sent.isEmpty()) {
                ByteBuffer datagram = clientEp.sent.pollFirst();
                ByteBuffer plaintext = serverSession.unwrap(datagram);
                if (plaintext != null) {
                    byte[] bytes = new byte[plaintext.remaining()];
                    plaintext.get(bytes);
                    serverEp.received.add(bytes);
                }
                progressed = true;
            }
            while (!serverEp.sent.isEmpty()) {
                ByteBuffer datagram = serverEp.sent.pollFirst();
                ByteBuffer plaintext = clientSession.unwrap(datagram);
                if (plaintext != null) {
                    byte[] bytes = new byte[plaintext.remaining()];
                    plaintext.get(bytes);
                    clientEp.received.add(bytes);
                }
                progressed = true;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerSession(UDPEndpoint endpoint,
            InetSocketAddress peer, DTLSSession session) throws Exception {
        Field sessions = UDPEndpoint.class.getDeclaredField("dtlsSessions");
        sessions.setAccessible(true);
        Map<InetSocketAddress, DTLSSession> map =
                (Map<InetSocketAddress, DTLSSession>) sessions.get(endpoint);
        map.put(peer, session);
    }

    private static void setSecure(UDPEndpoint endpoint) throws Exception {
        Field secure = UDPEndpoint.class.getDeclaredField("secure");
        secure.setAccessible(true);
        secure.setBoolean(endpoint, true);
    }

    @Test
    public void testSecureSendToUsesOwnedBufferWithoutExtraRawCopy() throws Exception {
        SSLEngine serverEngine = buildServerContext().createSSLEngine();
        serverEngine.setUseClientMode(false);
        SSLEngine clientEngine = buildClientContext().createSSLEngine(
                "localhost", SERVER_ADDR.getPort());
        clientEngine.setUseClientMode(true);

        TrackingEndpoint clientEp = new TrackingEndpoint();
        TrackingEndpoint serverEp = new TrackingEndpoint();
        DTLSSession clientSession = new DTLSSession(clientEngine, clientEp, SERVER_ADDR);
        DTLSSession serverSession = new DTLSSession(serverEngine, serverEp, CLIENT_ADDR);

        serverSession.beginHandshake();
        clientSession.beginHandshake();
        pumpUntilQuiescent(clientEp, clientSession, serverEp, serverSession);
        assertTrue(clientSession.isHandshakeComplete());
        assertTrue(serverSession.isHandshakeComplete());

        registerSession(clientEp, SERVER_ADDR, clientSession);
        setSecure(clientEp);

        int rawBefore = clientEp.rawDatagramSends;
        byte[] payload = "dtls sendTo payload".getBytes(StandardCharsets.UTF_8);
        clientEp.sendTo(ByteBuffer.wrap(payload), SERVER_ADDR);

        assertEquals("application send must use the owned-buffer path",
                1, clientEp.ownedDatagramSends);
        assertEquals("application send must not copy again through sendRawDatagram",
                rawBefore, clientEp.rawDatagramSends);
        assertNotNull(clientEp.lastOwnedBuffer);
    }

    @Test
    public void testSecureSendToDeliversApplicationDataUnchanged() throws Exception {
        SSLEngine serverEngine = buildServerContext().createSSLEngine();
        serverEngine.setUseClientMode(false);
        SSLEngine clientEngine = buildClientContext().createSSLEngine(
                "localhost", SERVER_ADDR.getPort());
        clientEngine.setUseClientMode(true);

        TrackingEndpoint clientEp = new TrackingEndpoint();
        TrackingEndpoint serverEp = new TrackingEndpoint();
        DTLSSession clientSession = new DTLSSession(clientEngine, clientEp, SERVER_ADDR);
        DTLSSession serverSession = new DTLSSession(serverEngine, serverEp, CLIENT_ADDR);

        serverSession.beginHandshake();
        clientSession.beginHandshake();
        pumpUntilQuiescent(clientEp, clientSession, serverEp, serverSession);

        registerSession(clientEp, SERVER_ADDR, clientSession);
        setSecure(clientEp);

        byte[] payload = "hello over sendTo".getBytes(StandardCharsets.UTF_8);
        clientEp.sendTo(ByteBuffer.wrap(payload), SERVER_ADDR);
        pumpUntilQuiescent(clientEp, clientSession, serverEp, serverSession);

        assertEquals(1, serverEp.received.size());
        assertArrayEquals(payload, serverEp.received.get(0));
    }

    @Test
    public void testSendOwnedRawDatagramQueuesSameBufferInstance() throws Exception {
        UDPEndpoint endpoint = new UDPEndpoint(new ProtocolHandler() {
            @Override public void receive(ByteBuffer data) { }
            @Override public void connected(Endpoint ep) { }
            @Override public void disconnected() { }
            @Override public void securityEstablished(SecurityInfo info) { }
            @Override public void error(Exception cause) { }
        });
        ByteBuffer owned = ByteBufferPool.acquire(64);
        owned.put(new byte[] {1, 2, 3, 4});
        owned.flip();

        endpoint.sendOwnedRawDatagram(owned, SERVER_ADDR);

        Field pendingField = UDPEndpoint.class.getDeclaredField("pendingDatagrams");
        pendingField.setAccessible(true);
        Deque<?> pending = (Deque<?>) pendingField.get(endpoint);
        assertEquals(1, pending.size());
        Object pendingDatagram = pending.peekFirst();
        Field dataField = pendingDatagram.getClass().getDeclaredField("data");
        dataField.setAccessible(true);
        assertSame("owned send must queue the buffer without copying it",
                owned, dataField.get(pendingDatagram));

        ByteBufferPool.release(owned);
    }
}
