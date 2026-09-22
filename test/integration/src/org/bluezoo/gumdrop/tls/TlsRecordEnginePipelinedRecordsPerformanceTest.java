/*
 * TlsRecordEnginePipelinedRecordsPerformanceTest.java
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

package org.bluezoo.gumdrop.tls;

import java.io.ByteArrayOutputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.IntegrationTestHosts;
import org.bluezoo.gumdrop.TestTlsFiles;

/**
 * Wall-clock regression test for {@link TlsRecordEngine}'s inbound
 * {@code GrowableBuffer}: before the read-cursor fix, every decrypted
 * record shifted the *entire* remaining unread tail of the buffer down
 * to offset 0 ({@code discard(n)} did {@code System.arraycopy(buf, n,
 * buf, 0, count - n)}), so parsing {@code k} records pipelined into one
 * {@link TlsRecordEngine#feedCiphertext} call cost O(k^2) rather than
 * O(k). A real TCP read commonly contains many small pipelined records
 * (e.g. a burst of small application-data writes coalesced by the
 * kernel, or -- as profiling of the HTTP integration suite showed --
 * simply nothing generates this pattern at integration-test volumes, so
 * a dedicated microbenchmark is needed to see it at all).
 *
 * <p>This drives a real TLS 1.3 loopback handshake (not a fake buffer),
 * then has the server seal many small application-data records and
 * hands the client every ciphertext byte in a <em>single</em> {@code
 * feedCiphertext} call, the way a saturated TCP read would.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsRecordEnginePipelinedRecordsPerformanceTest {

    /** Must match a DNS SAN on {@code etc/tls/cert.pem} (see {@code integration.tls.names}). */
    private static final String SERVER_NAME = IntegrationTestHosts.TLS_SERVER_NAME;

    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;
    private static X509TrustManager trustManager;

    @BeforeClass
    public static void loadCertificates() throws Exception {
        TestTlsFiles.assumeAvailable();
        ecChain = TestTlsFiles.certificateChain();
        ecKey = TestTlsFiles.privateKey();
        trustManager = TestTlsFiles.trustManager();
    }

    /** Records every event a {@link TlsRecordEngine} pushes, for assertions. */
    private static class RecordingSink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        boolean handshakeComplete;
        TlsProtocolError error;

        byte[] drainOutbound() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < outbound.size(); i++) {
                byte[] chunk = outbound.get(i);
                out.write(chunk, 0, chunk.length);
            }
            outbound.clear();
            return out.toByteArray();
        }

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
            appData.add(plaintext);
        }

        @Override
        public void handshakeComplete() {
            handshakeComplete = true;
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
        }

        @Override
        public void peerClosed() {
        }
    }

    private HandshakeConfig serverConfig() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(new ServerCredentials(ecChain, ecKey));
        config.setApplicationProtocols(Collections.singletonList("test"));
        return config;
    }

    private HandshakeConfig clientConfig() throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(SERVER_NAME);
        config.setTrustManager(trustManager);
        config.setApplicationProtocols(Collections.singletonList("test"));
        return config;
    }

    private static void relay(RecordingSink from, TlsRecordEngine toEngine, RecordingSink toSink) {
        byte[] wire = from.drainOutbound();
        if (wire.length > 0) {
            toEngine.feedCiphertext(wire, toSink);
        }
    }

    /**
     * Feeds {@code recordCount} small application-data records, all
     * sealed up front and concatenated into one buffer, to a client in a
     * single {@link TlsRecordEngine#feedCiphertext} call -- reproducing
     * a saturated TCP read carrying many pipelined records -- and
     * asserts this completes in time roughly linear in
     * {@code recordCount}, not quadratic.
     */
    @Test(timeout = 60000)
    public void manyPipelinedRecordsInOneReadStayLinear() throws Exception {
        TlsRecordEngine client = new TlsRecordEngine(clientConfig());
        TlsRecordEngine server = new TlsRecordEngine(serverConfig());
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        client.start(clientSink);
        relay(clientSink, server, serverSink); // ClientHello
        relay(serverSink, client, clientSink); // ServerHello..Finished
        relay(clientSink, server, serverSink); // client Finished
        relay(serverSink, client, clientSink); // post-handshake NewSessionTicket
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());

        int recordCount = 100000;
        byte[] payload = new byte[64];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        // Seal every record up front: sealing itself is O(1) per record
        // (writeFragmented resets its outbound buffer on every call), so
        // this loop is not what the fix touches -- only the single big
        // feedCiphertext call below exercises the inbound GrowableBuffer.
        ByteArrayOutputStream allCiphertext = new ByteArrayOutputStream();
        for (int i = 0; i < recordCount; i++) {
            server.sendApplicationData(payload, serverSink);
            byte[] chunk = serverSink.drainOutbound();
            allCiphertext.write(chunk, 0, chunk.length);
        }
        byte[] wire = allCiphertext.toByteArray();

        long start = System.nanoTime();
        client.feedCiphertext(wire, clientSink);
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertEquals(recordCount, clientSink.appData.size());
        assertTrue(recordCount + " pipelined " + payload.length + "-byte records ("
                + wire.length + " total bytes) in one feedCiphertext call took " + elapsedMs
                + "ms -- expected the inbound buffer to track a read cursor instead of "
                + "shifting the whole remaining buffer down on every record",
                elapsedMs < 5000);
    }

}
