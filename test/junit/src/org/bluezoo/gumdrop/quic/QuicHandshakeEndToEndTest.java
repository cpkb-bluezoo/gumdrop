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
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509TrustManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import tech.kwik.agent15.TlsConstants;
import tech.kwik.agent15.engine.TlsServerEngineFactory;

import org.bluezoo.gumdrop.quic.frame.QuicFrameHandler;
import org.bluezoo.gumdrop.quic.frame.QuicFrameParser;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderPrefix;
import org.bluezoo.gumdrop.quic.packet.PacketNumberCodec;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionException;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;
import org.bluezoo.gumdrop.quic.packet.ShortHeaderCodec;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.bluezoo.gumdrop.quic.tls.Hkdf;
import org.bluezoo.gumdrop.quic.tls.InitialSecrets;
import org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngineListener;
import org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives a complete QUIC handshake to {@code HANDSHAKE_DONE} between two
 * in-process peers -- a client and a server, talking only through Java
 * byte arrays, no sockets -- exercising every piece built for Stage 1 of
 * the quiche/BoringSSL replacement together: {@code quic.tls} (Agent15
 * wiring), {@code quic.packet} (packet protection, header layout), and
 * {@code quic.frame} (CRYPTO/ACK/HANDSHAKE_DONE).
 *
 * <p>This is a connectivity proof, not the production QUIC connection
 * implementation: connection IDs are supplied by the test rather than
 * learned dynamically, there is no coalescing of packets into shared
 * datagrams, no loss detection or retransmission, and no QUIC
 * transport-parameters extension. See {@link QuicTlsClientEngine}'s
 * documentation for what else is deliberately deferred. The exact
 * message sequence below (which packet carries which message, and at
 * which encryption level) follows RFC 9001 section 4.1 directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.1">RFC 9001 section 4.1</a>
 */
public class QuicHandshakeEndToEndTest {

    private static final int CONNECTION_ID_LENGTH = 8;
    private static final int MIN_CLIENT_INITIAL_DATAGRAM_SIZE = 1200;
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
                    .sorted(java.util.Comparator.reverseOrder())
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

    /** Test-only: accepts any server certificate, since there is no CA chain here. */
    private static class PermissiveTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static byte[] randomConnectionId() {
        byte[] id = new byte[CONNECTION_ID_LENGTH];
        new SecureRandom().nextBytes(id);
        return id;
    }

    private static Hkdf hkdfFor(TlsConstants.CipherSuite cipher) {
        if (cipher == TlsConstants.CipherSuite.TLS_AES_256_GCM_SHA384) {
            return Hkdf.sha384();
        }
        return Hkdf.sha256();
    }

    private static QuicAeadAlgorithm aeadFor(TlsConstants.CipherSuite cipher) {
        if (cipher == TlsConstants.CipherSuite.TLS_AES_256_GCM_SHA384) {
            return QuicAeadAlgorithm.AES_256_GCM;
        }
        return QuicAeadAlgorithm.AES_128_GCM;
    }

    /** One handshake message queued to be sent as a CRYPTO frame. */
    private static class PendingCrypto {
        final long offset;
        final byte[] data;

        PendingCrypto(long offset, byte[] data) {
            this.offset = offset;
            this.data = data;
        }
    }

    /**
     * One side of the in-process connection: owns its TLS engine, its
     * packet-protection keys per level (as they become available), its
     * per-level packet number counters, and enough packet/frame
     * assembly logic to build and consume datagrams.
     */
    private static class Peer implements QuicTlsEngineListener {

        final boolean isClient;
        final QuicTlsEngine tlsEngine;

        final EnumMap<EncryptionLevel, List<PendingCrypto>> pendingCrypto =
                new EnumMap<EncryptionLevel, List<PendingCrypto>>(EncryptionLevel.class);
        final EnumMap<EncryptionLevel, PacketProtectionKeys> sendKeys =
                new EnumMap<EncryptionLevel, PacketProtectionKeys>(EncryptionLevel.class);
        final EnumMap<EncryptionLevel, PacketProtectionKeys> recvKeys =
                new EnumMap<EncryptionLevel, PacketProtectionKeys>(EncryptionLevel.class);

        final long[] sendPacketNumber = new long[EncryptionLevel.values().length];
        final long[] largestReceived = { -1, -1, -1 };

        volatile boolean handshakeDoneReadyToSend;
        volatile boolean handshakeConfirmed;
        Exception deliveryError;

        Peer(boolean isClient, byte[] clientInitialDcid) {
            this.isClient = isClient;
            for (EncryptionLevel level : EncryptionLevel.values()) {
                pendingCrypto.put(level, new ArrayList<PendingCrypto>());
            }

            byte[] clientInitialSecret = InitialSecrets.clientSecretV1(clientInitialDcid);
            byte[] serverInitialSecret = InitialSecrets.serverSecretV1(clientInitialDcid);
            Hkdf sha256 = Hkdf.sha256();
            PacketProtectionKeys clientInitialKeys =
                    PacketProtectionKeys.derive(sha256, clientInitialSecret, QuicAeadAlgorithm.AES_128_GCM);
            PacketProtectionKeys serverInitialKeys =
                    PacketProtectionKeys.derive(sha256, serverInitialSecret, QuicAeadAlgorithm.AES_128_GCM);

            if (isClient) {
                sendKeys.put(EncryptionLevel.INITIAL, clientInitialKeys);
                recvKeys.put(EncryptionLevel.INITIAL, serverInitialKeys);
                QuicTlsClientEngine clientEngine = new QuicTlsClientEngine(this);
                clientEngine.setTrustManager(new PermissiveTrustManager());
                this.tlsEngine = clientEngine;
            } else {
                sendKeys.put(EncryptionLevel.INITIAL, serverInitialKeys);
                recvKeys.put(EncryptionLevel.INITIAL, clientInitialKeys);
                this.tlsEngine = new QuicTlsServerEngine(serverCertificateFactory, this);
            }
        }

        // ── QuicTlsEngineListener ──

        @Override
        public void cryptoDataReady(EncryptionLevel level, long offset, byte[] data) {
            pendingCrypto.get(level).add(new PendingCrypto(offset, data));
        }

        @Override
        public void handshakeSecretsAvailable() {
            TlsConstants.CipherSuite cipher = negotiatedCipher();
            Hkdf hkdf = hkdfFor(cipher);
            QuicAeadAlgorithm aead = aeadFor(cipher);
            byte[] clientSecret = tlsEngine.getClientHandshakeTrafficSecret();
            byte[] serverSecret = tlsEngine.getServerHandshakeTrafficSecret();
            deriveDirectionalKeys(EncryptionLevel.HANDSHAKE, hkdf, aead, clientSecret, serverSecret);
        }

        @Override
        public void handshakeFinished() {
            TlsConstants.CipherSuite cipher = negotiatedCipher();
            Hkdf hkdf = hkdfFor(cipher);
            QuicAeadAlgorithm aead = aeadFor(cipher);
            byte[] clientSecret = tlsEngine.getClientApplicationTrafficSecret();
            byte[] serverSecret = tlsEngine.getServerApplicationTrafficSecret();
            deriveDirectionalKeys(EncryptionLevel.ONE_RTT, hkdf, aead, clientSecret, serverSecret);
            if (!isClient) {
                // RFC 9001 section 4.1.2: once the server has verified
                // the client's Finished message, it MUST send
                // HANDSHAKE_DONE.
                handshakeDoneReadyToSend = true;
            }
        }

        private TlsConstants.CipherSuite negotiatedCipher() {
            if (isClient) {
                return ((QuicTlsClientEngine) tlsEngine).getSelectedCipher();
            }
            return ((QuicTlsServerEngine) tlsEngine).getSelectedCipher();
        }

        private void deriveDirectionalKeys(EncryptionLevel level, Hkdf hkdf, QuicAeadAlgorithm aead,
                byte[] clientSecret, byte[] serverSecret) {
            PacketProtectionKeys clientDirectionKeys = PacketProtectionKeys.derive(hkdf, clientSecret, aead);
            PacketProtectionKeys serverDirectionKeys = PacketProtectionKeys.derive(hkdf, serverSecret, aead);
            if (isClient) {
                sendKeys.put(level, clientDirectionKeys);
                recvKeys.put(level, serverDirectionKeys);
            } else {
                sendKeys.put(level, serverDirectionKeys);
                recvKeys.put(level, clientDirectionKeys);
            }
        }

        // ── Packet building ──

        /**
         * Builds and protects one packet at the given level, containing
         * every pending CRYPTO chunk for that level, optionally an ACK
         * for the highest packet number received so far at that level,
         * optionally a HANDSHAKE_DONE frame, padded to at least
         * {@code minDatagramSize} bytes total.
         *
         * @param level the encryption level to send at
         * @param dcid the Destination Connection ID to address this packet to
         * @param scid this peer's own connection ID (long-header packets only)
         * @param includeAck whether to include an ACK frame for the
         *                   highest packet number received at this level
         * @param includeHandshakeDone whether to include a HANDSHAKE_DONE frame
         * @param minDatagramSize the minimum total datagram size (padded if needed)
         * @return the protected datagram bytes
         * @throws PacketProtectionException if sealing fails
         */
        byte[] buildPacket(EncryptionLevel level, byte[] dcid, byte[] scid,
                boolean includeAck, boolean includeHandshakeDone, int minDatagramSize)
                throws PacketProtectionException {
            List<PendingCrypto> chunks = pendingCrypto.get(level);

            int frameBytes = 0;
            for (PendingCrypto chunk : chunks) {
                frameBytes += QuicFrameWriter.cryptoLength(chunk.offset, chunk.data.length);
            }
            if (includeAck) {
                frameBytes += QuicFrameWriter.ackLength(largestReceived[level.ordinal()], 0, 0);
            }
            if (includeHandshakeDone) {
                frameBytes += QuicFrameWriter.handshakeDoneLength();
            }

            boolean longHeader = (level != EncryptionLevel.ONE_RTT);
            long packetNumber = sendPacketNumber[level.ordinal()]++;
            int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
            int packetType = (level == EncryptionLevel.INITIAL)
                    ? LongHeaderCodec.TYPE_INITIAL : LongHeaderCodec.TYPE_HANDSHAKE;

            // Padding pushes up the protected payload length, which can
            // in turn push the header's Length field into a longer
            // varint encoding (RFC 9000 section 16), which changes the
            // header length the padding target has to account for.
            // Header length is non-decreasing in padding size and the
            // varint length class has only 4 possible values, so this
            // converges in at most a few iterations.
            int paddingBytes = 0;
            byte[] header;
            while (true) {
                header = longHeader
                        ? LongHeaderCodec.build(packetType, 1, dcid, scid, new byte[0],
                                packetNumber, pnLength, frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH)
                        : ShortHeaderCodec.build(dcid, false, packetNumber, pnLength);
                int required = minDatagramSize - (header.length + frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH);
                int nextPadding = Math.max(0, paddingBytes + required);
                if (nextPadding == paddingBytes) {
                    break;
                }
                paddingBytes = nextPadding;
            }
            frameBytes += paddingBytes;

            ByteBuffer payload = ByteBuffer.allocate(frameBytes);
            for (PendingCrypto chunk : chunks) {
                QuicFrameWriter.writeCrypto(payload, chunk.offset, chunk.data);
            }
            if (includeAck) {
                QuicFrameWriter.writeAck(payload, largestReceived[level.ordinal()], 0, 0);
            }
            if (includeHandshakeDone) {
                QuicFrameWriter.writeHandshakeDone(payload);
            }
            if (paddingBytes > 0) {
                QuicFrameWriter.writePadding(payload, paddingBytes);
            }
            payload.flip();
            byte[] plaintext = new byte[payload.remaining()];
            payload.get(plaintext);
            chunks.clear();

            PacketProtectionKeys keys = sendKeys.get(level);
            byte[] ciphertext = PacketProtection.seal(keys, packetNumber, header, plaintext);

            byte[] packet = new byte[header.length + ciphertext.length];
            System.arraycopy(header, 0, packet, 0, header.length);
            System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);

            int pnOffset = longHeader
                    ? header.length - pnLength
                    : ShortHeaderCodec.packetNumberOffset(dcid.length);
            byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
            System.arraycopy(packet, pnOffset + 4, sample, 0, sample.length);
            byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
            PacketProtection.xorFirstByte(packet, mask, longHeader);
            PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

            return packet;
        }

        // ── Packet receiving ──

        /**
         * Removes protection from a received datagram and dispatches
         * its frames.
         *
         * @param datagram the received datagram bytes
         * @throws Exception rethrows whatever the underlying TLS engine
         *                   or packet protection layer reported
         */
        void receiveDatagram(byte[] datagram) throws Exception {
            boolean longHeader = (datagram[0] & 0x80) != 0;
            EncryptionLevel level;
            int pnOffset;
            long remainingLength;
            byte[] aadPrefix;

            if (longHeader) {
                LongHeaderPrefix prefix = LongHeaderCodec.parsePrefix(datagram);
                level = (prefix.getPacketType() == LongHeaderCodec.TYPE_INITIAL)
                        ? EncryptionLevel.INITIAL : EncryptionLevel.HANDSHAKE;
                pnOffset = prefix.getPacketNumberOffset();
                remainingLength = prefix.getRemainingLength();
                aadPrefix = null;
            } else {
                level = EncryptionLevel.ONE_RTT;
                pnOffset = ShortHeaderCodec.packetNumberOffset(CONNECTION_ID_LENGTH);
                remainingLength = -1; // runs to end of datagram
                aadPrefix = null;
            }

            PacketProtectionKeys keys = recvKeys.get(level);
            byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
            System.arraycopy(datagram, pnOffset + 4, sample, 0, sample.length);
            byte[] mask = PacketProtection.headerProtectionMask(keys, sample);

            PacketProtection.xorFirstByte(datagram, mask, longHeader);
            int pnLength = (datagram[0] & 0x03) + 1;
            PacketProtection.xorPacketNumberBytes(datagram, pnOffset, pnLength, mask);

            long truncatedPn = 0;
            for (int i = 0; i < pnLength; i++) {
                truncatedPn = (truncatedPn << 8) | (datagram[pnOffset + i] & 0xff);
            }
            long fullPacketNumber = PacketNumberCodec.decode(largestReceived[level.ordinal()], truncatedPn, pnLength);

            int headerLength = pnOffset + pnLength;
            int ciphertextEnd = longHeader ? (int) (pnOffset + remainingLength) : datagram.length;
            byte[] aad = new byte[headerLength];
            System.arraycopy(datagram, 0, aad, 0, headerLength);
            byte[] ciphertext = new byte[ciphertextEnd - headerLength];
            System.arraycopy(datagram, headerLength, ciphertext, 0, ciphertext.length);

            byte[] plaintext = PacketProtection.open(keys, fullPacketNumber, aad, ciphertext);
            largestReceived[level.ordinal()] = Math.max(largestReceived[level.ordinal()], fullPacketNumber);

            deliveryError = null;
            QuicFrameParser parser = new QuicFrameParser(new DeliveringFrameHandler(level));
            parser.receive(ByteBuffer.wrap(plaintext));
            if (deliveryError != null) {
                throw deliveryError;
            }
        }

        private class DeliveringFrameHandler implements QuicFrameHandler {

            private final EncryptionLevel level;

            DeliveringFrameHandler(EncryptionLevel level) {
                this.level = level;
            }

            @Override
            public void paddingFrameReceived(int length) {
            }

            @Override
            public void pingFrameReceived() {
            }

            @Override
            public void ackFrameReceived(long largestAcknowledged, long ackDelay, long firstAckRange) {
                // No retransmission logic in this test harness.
            }

            @Override
            public void cryptoFrameReceived(long offset, ByteBuffer data) {
                byte[] copy = new byte[data.remaining()];
                data.get(copy);
                try {
                    tlsEngine.receiveCryptoData(level, offset, ByteBuffer.wrap(copy));
                } catch (Exception e) {
                    deliveryError = e;
                }
            }

            @Override
            public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                    long frameType, String reason) {
                deliveryError = new IOException("Peer closed the connection: " + reason);
            }

            @Override
            public void handshakeDoneFrameReceived() {
                handshakeConfirmed = true;
            }

            @Override
            public void frameError(String message) {
                deliveryError = new IOException("Frame parse error: " + message);
            }
        }
    }

    @Test
    public void testHandshakeReachesHandshakeDone() throws Exception {
        byte[] clientInitialDcid = randomConnectionId();
        byte[] clientScid = randomConnectionId();
        byte[] serverScid = randomConnectionId();

        Peer client = new Peer(true, clientInitialDcid);
        Peer server = new Peer(false, clientInitialDcid);

        ((QuicTlsClientEngine) client.tlsEngine).startHandshake(SERVER_NAME);
        byte[] clientInitialDatagram = client.buildPacket(EncryptionLevel.INITIAL,
                clientInitialDcid, clientScid, false, false, MIN_CLIENT_INITIAL_DATAGRAM_SIZE);

        server.receiveDatagram(clientInitialDatagram);

        byte[] serverInitialDatagram = server.buildPacket(EncryptionLevel.INITIAL,
                clientScid, serverScid, true, false, 0);
        byte[] serverHandshakeDatagram = server.buildPacket(EncryptionLevel.HANDSHAKE,
                clientScid, serverScid, false, false, 0);

        client.receiveDatagram(serverInitialDatagram);
        client.receiveDatagram(serverHandshakeDatagram);

        byte[] clientHandshakeDatagram = client.buildPacket(EncryptionLevel.HANDSHAKE,
                serverScid, clientScid, true, false, 0);

        server.receiveDatagram(clientHandshakeDatagram);

        assertTrue("Server TLS handshake should have finished",
                server.sendKeys.containsKey(EncryptionLevel.ONE_RTT));
        assertTrue("Server should be ready to send HANDSHAKE_DONE", server.handshakeDoneReadyToSend);

        byte[] serverOneRttDatagram = server.buildPacket(EncryptionLevel.ONE_RTT,
                clientScid, null, false, true, 0);

        client.receiveDatagram(serverOneRttDatagram);

        assertTrue("Client should have received HANDSHAKE_DONE", client.handshakeConfirmed);
        assertEquals("Client and server must have negotiated the same cipher suite",
                ((QuicTlsClientEngine) client.tlsEngine).getSelectedCipher(),
                ((QuicTlsServerEngine) server.tlsEngine).getSelectedCipher());
        assertTrue("Client should have received the server's certificate chain",
                !((QuicTlsClientEngine) client.tlsEngine).getServerCertificateChain().isEmpty());
    }
}
