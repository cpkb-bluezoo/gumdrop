/*
 * QuicTestPeer.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.X509TrustManager;

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
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.bluezoo.gumdrop.quic.tls.Hkdf;
import org.bluezoo.gumdrop.quic.tls.InitialSecrets;
import org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngineListener;
import org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine;

/**
 * One side of an in-process QUIC connection used by the quic package's
 * end-to-end tests: owns a TLS engine, packet-protection keys per level
 * (as they become available), per-level packet number counters, and
 * enough packet/frame/stream assembly logic to build and consume
 * datagrams without any real socket.
 *
 * <p>This is a connectivity proof, not the production QUIC connection
 * implementation: connection IDs are supplied by the caller rather than
 * learned dynamically, there is no coalescing of packets into shared
 * datagrams, no loss detection or retransmission (ACK frames sent are
 * inert), and flow control limits are the static values from the
 * initial transport parameters exchange -- there is no MAX_DATA/
 * MAX_STREAM_DATA window-update logic, so this cannot carry more data
 * on a stream or connection than the initial limits allow. See
 * {@link QuicTlsClientEngine}'s documentation for what else is
 * deliberately deferred.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicTestPeer implements QuicTlsEngineListener {

    public static final int CONNECTION_ID_LENGTH = 8;

    public final boolean isClient;
    public final QuicTlsEngine tlsEngine;

    private final EnumMap<EncryptionLevel, List<PendingChunk>> pendingCrypto =
            new EnumMap<EncryptionLevel, List<PendingChunk>>(EncryptionLevel.class);
    private final Map<Long, List<PendingChunk>> pendingStream = new HashMap<Long, List<PendingChunk>>();
    private final Map<Long, Long> streamSendOffset = new HashMap<Long, Long>();
    private final Map<Long, ByteArrayOutputStream> streamReceiveBuffers =
            new HashMap<Long, ByteArrayOutputStream>();
    private final Map<Long, Boolean> streamFinReceived = new HashMap<Long, Boolean>();

    private final EnumMap<EncryptionLevel, PacketProtectionKeys> sendKeys =
            new EnumMap<EncryptionLevel, PacketProtectionKeys>(EncryptionLevel.class);
    private final EnumMap<EncryptionLevel, PacketProtectionKeys> recvKeys =
            new EnumMap<EncryptionLevel, PacketProtectionKeys>(EncryptionLevel.class);

    private final long[] sendPacketNumber = new long[EncryptionLevel.values().length];
    private final long[] largestReceived = { -1, -1, -1 };

    private long nextLocalBidiStreamId;

    public volatile boolean handshakeDoneReadyToSend;
    public volatile boolean handshakeConfirmed;
    public volatile TransportParameters peerTransportParameters;
    private Exception deliveryError;

    /**
     * Creates a client-side peer.
     *
     * @param clientInitialDcid the Destination Connection ID for the
     *                          client's first Initial packet
     * @param localTransportParameters this peer's own transport parameters
     * @return the new client peer
     */
    public static QuicTestPeer newClient(byte[] clientInitialDcid, TransportParameters localTransportParameters) {
        return new QuicTestPeer(true, clientInitialDcid, localTransportParameters, null);
    }

    /**
     * Creates a server-side peer.
     *
     * @param clientInitialDcid the Destination Connection ID from the
     *                          client's first Initial packet
     * @param localTransportParameters this peer's own transport parameters
     * @param certificateFactory factory holding the server's certificate
     *                           chain and private key
     * @return the new server peer
     */
    public static QuicTestPeer newServer(byte[] clientInitialDcid, TransportParameters localTransportParameters,
            TlsServerEngineFactory certificateFactory) {
        return new QuicTestPeer(false, clientInitialDcid, localTransportParameters, certificateFactory);
    }

    private QuicTestPeer(boolean isClient, byte[] clientInitialDcid,
            TransportParameters localTransportParameters, TlsServerEngineFactory certificateFactory) {
        this.isClient = isClient;
        this.nextLocalBidiStreamId = isClient ? 0 : 1;
        for (EncryptionLevel level : EncryptionLevel.values()) {
            pendingCrypto.put(level, new ArrayList<PendingChunk>());
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
            QuicTlsClientEngine clientEngine = new QuicTlsClientEngine(localTransportParameters, this);
            clientEngine.setTrustManager(new PermissiveTrustManager());
            this.tlsEngine = clientEngine;
        } else {
            sendKeys.put(EncryptionLevel.INITIAL, serverInitialKeys);
            recvKeys.put(EncryptionLevel.INITIAL, clientInitialKeys);
            this.tlsEngine = new QuicTlsServerEngine(certificateFactory, localTransportParameters, this);
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

    public void startHandshake(String serverName) throws IOException {
        ((QuicTlsClientEngine) tlsEngine).startHandshake(serverName);
    }

    public TlsConstants.CipherSuite getSelectedCipher() {
        if (isClient) {
            return ((QuicTlsClientEngine) tlsEngine).getSelectedCipher();
        }
        return ((QuicTlsServerEngine) tlsEngine).getSelectedCipher();
    }

    public List<X509Certificate> getServerCertificateChain() {
        return ((QuicTlsClientEngine) tlsEngine).getServerCertificateChain();
    }

    // ── QuicTlsEngineListener ──

    @Override
    public void cryptoDataReady(EncryptionLevel level, long offset, byte[] data) {
        pendingCrypto.get(level).add(new PendingChunk(offset, data));
    }

    @Override
    public void handshakeSecretsAvailable() {
        TlsConstants.CipherSuite cipher = getSelectedCipher();
        Hkdf hkdf = hkdfFor(cipher);
        QuicAeadAlgorithm aead = aeadFor(cipher);
        byte[] clientSecret = tlsEngine.getClientHandshakeTrafficSecret();
        byte[] serverSecret = tlsEngine.getServerHandshakeTrafficSecret();
        deriveDirectionalKeys(EncryptionLevel.HANDSHAKE, hkdf, aead, clientSecret, serverSecret);
    }

    @Override
    public void handshakeFinished() {
        TlsConstants.CipherSuite cipher = getSelectedCipher();
        Hkdf hkdf = hkdfFor(cipher);
        QuicAeadAlgorithm aead = aeadFor(cipher);
        byte[] clientSecret = tlsEngine.getClientApplicationTrafficSecret();
        byte[] serverSecret = tlsEngine.getServerApplicationTrafficSecret();
        deriveDirectionalKeys(EncryptionLevel.ONE_RTT, hkdf, aead, clientSecret, serverSecret);
        if (!isClient) {
            // RFC 9001 section 4.1.2: once the server has verified the
            // client's Finished message, it MUST send HANDSHAKE_DONE.
            handshakeDoneReadyToSend = true;
        }
    }

    @Override
    public void transportParametersReceived(TransportParameters transportParameters) {
        this.peerTransportParameters = transportParameters;
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

    // ── Streams ──

    /**
     * Allocates the next locally initiated bidirectional stream ID
     * (RFC 9000 section 2.1: 0, 4, 8, ... for a client; 1, 5, 9, ...
     * for a server).
     *
     * @return the new stream ID
     */
    public long openBidiStream() {
        long streamId = nextLocalBidiStreamId;
        nextLocalBidiStreamId += 4;
        return streamId;
    }

    /**
     * Queues data to be sent on a stream the next time a 1-RTT packet
     * is built. Does not check this against the peer's advertised flow
     * control limits -- see the class documentation.
     *
     * @param streamId the stream to send on
     * @param data the data to send
     * @param fin true if this is the last data on the stream
     */
    public void queueStreamData(long streamId, byte[] data, boolean fin) {
        long offset = streamSendOffset.containsKey(streamId) ? streamSendOffset.get(streamId) : 0L;
        List<PendingChunk> chunks = pendingStream.get(streamId);
        if (chunks == null) {
            chunks = new ArrayList<PendingChunk>();
            pendingStream.put(streamId, chunks);
        }
        chunks.add(new PendingChunk(offset, data, fin));
        streamSendOffset.put(streamId, offset + data.length);
    }

    /**
     * Returns the stream data received so far on a stream.
     *
     * @param streamId the stream
     * @return the received bytes, or an empty array if none received yet
     */
    public byte[] getReceivedStreamData(long streamId) {
        ByteArrayOutputStream buffer = streamReceiveBuffers.get(streamId);
        return buffer == null ? new byte[0] : buffer.toByteArray();
    }

    /**
     * Returns whether a FIN has been received on a stream.
     *
     * @param streamId the stream
     * @return true if the peer has finished sending on this stream
     */
    public boolean isStreamFinReceived(long streamId) {
        Boolean fin = streamFinReceived.get(streamId);
        return fin != null && fin;
    }

    /**
     * Drives a complete QUIC handshake between an already-constructed
     * client and server peer to {@code HANDSHAKE_DONE}, following the
     * exact message sequence of RFC 9001 section 4.1: client Initial
     * (padded to the minimum datagram size), server Initial + Handshake,
     * client Handshake (with the client's Finished message), server
     * 1-RTT carrying HANDSHAKE_DONE.
     *
     * @param client a peer created with {@link #newClient}
     * @param server a peer created with {@link #newServer}, for the
     *               same {@code clientInitialDcid}
     * @param clientInitialDcid the Destination Connection ID used to
     *                          construct both peers
     * @param clientScid the client's own connection ID
     * @param serverScid the server's own connection ID
     * @param serverName the SNI server name
     * @throws Exception rethrows anything either peer's
     *                   {@link #receiveDatagram} reported
     */
    public static void completeHandshake(QuicTestPeer client, QuicTestPeer server,
            byte[] clientInitialDcid, byte[] clientScid, byte[] serverScid, String serverName)
            throws Exception {
        client.startHandshake(serverName);
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

        byte[] serverOneRttDatagram = server.buildPacket(EncryptionLevel.ONE_RTT,
                clientScid, null, false, true, 0);

        client.receiveDatagram(serverOneRttDatagram);
    }

    /** RFC 9000 section 14.1: a client MUST pad datagrams carrying Initial packets to at least this size. */
    private static final int MIN_CLIENT_INITIAL_DATAGRAM_SIZE = 1200;

    // ── Packet building ──

    /**
     * Builds and protects one packet at the given level, containing
     * every pending CRYPTO chunk for that level (and, at
     * {@link EncryptionLevel#ONE_RTT}, every pending STREAM chunk for
     * every stream), optionally an ACK for the highest packet number
     * received so far at that level, optionally a HANDSHAKE_DONE frame,
     * padded to at least {@code minDatagramSize} bytes total.
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
    public byte[] buildPacket(EncryptionLevel level, byte[] dcid, byte[] scid,
            boolean includeAck, boolean includeHandshakeDone, int minDatagramSize)
            throws PacketProtectionException {
        return buildPacket(level, dcid, scid, EMPTY_TOKEN, includeAck, includeHandshakeDone, minDatagramSize);
    }

    private static final byte[] EMPTY_TOKEN = new byte[0];

    /**
     * Same as {@link #buildPacket(EncryptionLevel, byte[], byte[], boolean, boolean, int)},
     * but with an explicit Initial-packet Token field (RFC 9000 section
     * 17.2.2) -- e.g. to test how a server reacts to a forged or garbage
     * Retry Token, which this harness has no legitimate way to produce
     * itself.
     *
     * @param token the Token field value (Initial packets only; ignored otherwise)
     */
    public byte[] buildPacket(EncryptionLevel level, byte[] dcid, byte[] scid, byte[] token,
            boolean includeAck, boolean includeHandshakeDone, int minDatagramSize)
            throws PacketProtectionException {
        List<PendingChunk> cryptoChunks = pendingCrypto.get(level);
        boolean oneRtt = (level == EncryptionLevel.ONE_RTT);

        int frameBytes = 0;
        for (PendingChunk chunk : cryptoChunks) {
            frameBytes += QuicFrameWriter.cryptoLength(chunk.offset, chunk.data.length);
        }
        if (oneRtt) {
            for (Map.Entry<Long, List<PendingChunk>> entry : pendingStream.entrySet()) {
                for (PendingChunk chunk : entry.getValue()) {
                    frameBytes += QuicFrameWriter.streamLength(entry.getKey(), chunk.offset, chunk.data.length);
                }
            }
        }
        if (includeAck) {
            frameBytes += QuicFrameWriter.ackLength(
                    new long[][] { { largestReceived[level.ordinal()], largestReceived[level.ordinal()] } }, 0);
        }
        if (includeHandshakeDone) {
            frameBytes += QuicFrameWriter.handshakeDoneLength();
        }

        boolean longHeader = !oneRtt;
        long packetNumber = sendPacketNumber[level.ordinal()]++;
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
        int packetType = (level == EncryptionLevel.INITIAL)
                ? LongHeaderCodec.TYPE_INITIAL : LongHeaderCodec.TYPE_HANDSHAKE;

        // Padding pushes up the protected payload length, which can in
        // turn push the header's Length field into a longer varint
        // encoding (RFC 9000 section 16), which changes the header
        // length the padding target has to account for. Header length
        // is non-decreasing in padding size and the varint length class
        // has only 4 possible values, so this converges in at most a
        // few iterations.
        int paddingBytes = 0;
        byte[] header;
        while (true) {
            header = longHeader
                    ? LongHeaderCodec.build(packetType, 1, dcid, scid, token,
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
        for (PendingChunk chunk : cryptoChunks) {
            QuicFrameWriter.writeCrypto(payload, chunk.offset, chunk.data);
        }
        if (oneRtt) {
            for (Map.Entry<Long, List<PendingChunk>> entry : pendingStream.entrySet()) {
                for (PendingChunk chunk : entry.getValue()) {
                    QuicFrameWriter.writeStream(payload, entry.getKey(), chunk.offset, chunk.data, chunk.fin);
                }
            }
        }
        if (includeAck) {
            QuicFrameWriter.writeAck(payload,
                    new long[][] { { largestReceived[level.ordinal()], largestReceived[level.ordinal()] } }, 0);
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
        cryptoChunks.clear();
        if (oneRtt) {
            pendingStream.clear();
        }

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
     * Removes protection from a received datagram and dispatches its frames.
     *
     * @param datagram the received datagram bytes
     * @throws Exception rethrows whatever the underlying TLS engine or
     *                   packet protection layer reported
     */
    public void receiveDatagram(byte[] datagram) throws Exception {
        boolean longHeader = (datagram[0] & 0x80) != 0;
        EncryptionLevel level;
        int pnOffset;
        long remainingLength;

        if (longHeader) {
            LongHeaderPrefix prefix = LongHeaderCodec.parsePrefix(datagram);
            level = (prefix.getPacketType() == LongHeaderCodec.TYPE_INITIAL)
                    ? EncryptionLevel.INITIAL : EncryptionLevel.HANDSHAKE;
            pnOffset = prefix.getPacketNumberOffset();
            remainingLength = prefix.getRemainingLength();
        } else {
            level = EncryptionLevel.ONE_RTT;
            pnOffset = ShortHeaderCodec.packetNumberOffset(CONNECTION_ID_LENGTH);
            remainingLength = -1; // runs to end of datagram
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

    private static class PendingChunk {
        final long offset;
        final byte[] data;
        final boolean fin;

        PendingChunk(long offset, byte[] data) {
            this(offset, data, false);
        }

        PendingChunk(long offset, byte[] data, boolean fin) {
            this.offset = offset;
            this.data = data;
            this.fin = fin;
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
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) {
            // No retransmission logic in this test harness.
        }

        @Override
        public void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize) {
        }

        @Override
        public void stopSendingFrameReceived(long streamId, long applicationErrorCode) {
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
        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) {
            ByteArrayOutputStream buffer = streamReceiveBuffers.get(streamId);
            if (buffer == null) {
                buffer = new ByteArrayOutputStream();
                streamReceiveBuffers.put(streamId, buffer);
            }
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            buffer.write(copy, 0, copy.length);
            if (fin) {
                streamFinReceived.put(streamId, Boolean.TRUE);
            }
        }

        @Override
        public void newTokenFrameReceived(ByteBuffer token) {
        }

        @Override
        public void maxDataFrameReceived(long maximumData) {
            // No dynamic window growth in this test harness.
        }

        @Override
        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) {
            // No dynamic window growth in this test harness.
        }

        @Override
        public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) {
            // No dynamic stream-limit growth in this test harness.
        }

        @Override
        public void dataBlockedFrameReceived(long maximumData) {
        }

        @Override
        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) {
        }

        @Override
        public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) {
        }

        @Override
        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) {
        }

        @Override
        public void retireConnectionIdFrameReceived(long sequenceNumber) {
        }

        @Override
        public void pathChallengeFrameReceived(ByteBuffer data) {
        }

        @Override
        public void pathResponseFrameReceived(ByteBuffer data) {
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
