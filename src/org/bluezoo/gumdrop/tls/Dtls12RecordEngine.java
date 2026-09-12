/*
 * Dtls12RecordEngine.java
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
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * DTLS 1.2 record layer (RFC 6347) wrapping {@link Tls12HandshakeEngine}.
 * Structural sibling of {@link Tls12RecordEngine} with datagram framing,
 * fragmentation/reassembly, epoch-aware keys, and anti-replay.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Dtls12RecordEngine {

    static final int DTLS_VERSION_MAJOR = 0xfe;
    static final int DTLS_VERSION_MINOR = 0xfd;
    static final int RECORD_HEADER_LEN = 13;
    static final int FRAGMENT_HEADER_LEN = 12;
    static final int HANDSHAKE_TYPE_HELLO_VERIFY_REQUEST = 3;

    private static final int CONTENT_CHANGE_CIPHER_SPEC = 20;
    private static final int CONTENT_ALERT = 21;
    private static final int CONTENT_HANDSHAKE = 22;
    private static final int CONTENT_APPLICATION_DATA = 23;

    private static final int ALERT_LEVEL_WARNING = 1;
    private static final int ALERT_LEVEL_FATAL = 2;
    private static final int ALERT_CLOSE_NOTIFY = 0;

    private static final int MAX_FRAGMENT = 16384;

    private final Tls12HandshakeEngine engine;
    private final HandshakeRole role;
    private final DtlsReassembler reassembler = new DtlsReassembler();
    private final int maxFragmentSize;
    private final InnerSink innerSink = new InnerSink();

    Dtls12DirectionalKeys write;
    Dtls12DirectionalKeys read;
    DtlsReplayWindow readReplay;

    Dtls12DirectionalKeys previousRead;
    DtlsReplayWindow previousReadReplay;

    private DirectionalKeyMaterial pendingWrite;
    private DirectionalKeyMaterial pendingRead;
    private Tls12CipherSuite stagedSuite;
    private int readEpoch;
    private int writeEpoch;
    private long plaintextWriteSeq;

    private HelloVerifyCallback helloVerifyCallback;
    private boolean alertSent;
    private boolean failed;

    /**
     * Optional callback for RFC 6347 HelloVerifyRequest (message type 3).
     */
    public interface HelloVerifyCallback {
        void onHelloVerifyRequest(byte[] cookie);
    }

    public void setHelloVerifyCallback(HelloVerifyCallback callback) {
        this.helloVerifyCallback = callback;
    }

    /**
     * Creates a DTLS 1.2 record engine.
     *
     * @param config handshake configuration (must have {@link Tls12HandshakeConfig#setDtlsTransport(boolean)} true)
     * @param maxFragmentSize maximum handshake fragment payload size
     */
    public Dtls12RecordEngine(Tls12HandshakeConfig config, int maxFragmentSize) {
        this.role = config.getRole();
        this.engine = new Tls12HandshakeEngine(config);
        this.maxFragmentSize = Math.max(1, Math.min(maxFragmentSize, MAX_FRAGMENT));
    }

    /**
     * Begins the handshake.
     *
     * @param sink where to push resulting events
     */
    public void start(TlsRecordSink sink) {
        engine.start(innerSink(sink));
    }

    /**
     * Returns whether the handshake has completed.
     *
     * @return true once finished
     */
    public boolean isComplete() {
        return engine.isComplete();
    }

    /**
     * Returns whether the engine has failed.
     *
     * @return true after a fatal error
     */
    public boolean isFailed() {
        return failed || engine.isFailed();
    }

    /**
     * Parses one complete datagram (possibly containing several coalesced records).
     *
     * @param datagram the received datagram bytes
     * @param sink where to push resulting events
     */
    public void feedDatagram(byte[] datagram, TlsRecordSink sink) {
        if (failed) {
            return;
        }
        int offset = 0;
        while (offset < datagram.length) {
            if (datagram.length - offset < RECORD_HEADER_LEN) {
                fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS record header");
                return;
            }
            int contentType = datagram[offset] & 0xff;
            int versionMajor = datagram[offset + 1] & 0xff;
            int versionMinor = datagram[offset + 2] & 0xff;
            int epoch = ((datagram[offset + 3] & 0xff) << 8) | (datagram[offset + 4] & 0xff);
            long seq = 0L;
            for (int i = 0; i < 6; i++) {
                seq = (seq << 8) | (datagram[offset + 5 + i] & 0xff);
            }
            int length = ((datagram[offset + 11] & 0xff) << 8) | (datagram[offset + 12] & 0xff);
            if (versionMajor != DTLS_VERSION_MAJOR || versionMinor != DTLS_VERSION_MINOR) {
                fail(sink, AlertDescription.PROTOCOL_VERSION, "unexpected DTLS version");
                return;
            }
            if (offset + RECORD_HEADER_LEN + length > datagram.length) {
                fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS record body");
                return;
            }
            byte[] body = Arrays.copyOfRange(datagram, offset + RECORD_HEADER_LEN,
                    offset + RECORD_HEADER_LEN + length);
            offset += RECORD_HEADER_LEN + length;

            if (!processRecord(contentType, epoch, seq, body, sink)) {
                return;
            }
        }
    }

    /**
     * Encrypts and frames application data.
     *
     * @param plaintext application bytes
     * @param sink where to push ciphertext datagrams
     */
    public void sendApplicationData(byte[] plaintext, TlsRecordSink sink) {
        if (failed) {
            return;
        }
        if (write == null) {
            sink.protocolError(new TlsProtocolError(AlertDescription.INTERNAL_ERROR,
                    "application data sent before handshake completed"));
            return;
        }
        writeOneRecord(CONTENT_APPLICATION_DATA, plaintext, sink);
        if (write.overConfidentialityLimit()) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "AES-GCM write key exceeded its confidentiality limit");
        }
    }

    /**
     * Sends {@code close_notify}.
     *
     * @param sink where to push ciphertext datagrams
     */
    public void sendCloseNotify(TlsRecordSink sink) {
        if (failed) {
            return;
        }
        writeOneRecord(CONTENT_ALERT,
                new byte[] { (byte) ALERT_LEVEL_WARNING, (byte) ALERT_CLOSE_NOTIFY }, sink);
    }

    public Tls12CipherSuite getNegotiatedCipherSuite() {
        return engine.getNegotiatedCipherSuite();
    }

    public String getNegotiatedApplicationProtocol() {
        return engine.getNegotiatedApplicationProtocol();
    }

    public List<X509Certificate> getPeerCertificateChain() {
        return engine.getPeerCertificateChain();
    }

    public boolean isResumed() {
        return engine.isResumed();
    }

    /** Returns the ClientHello random from an in-progress client handshake. */
    public byte[] getClientRandom() {
        return engine.getClientRandom();
    }

    Tls12HandshakeEngine getHandshakeEngine() {
        return engine;
    }

    private Tls12EventSink innerSink(TlsRecordSink outer) {
        innerSink.outer = outer;
        return innerSink;
    }

    private boolean processRecord(int contentType, int epoch, long seq, byte[] body, TlsRecordSink sink) {
        if (contentType == CONTENT_CHANGE_CIPHER_SPEC) {
            if (epoch != readEpoch) {
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "ChangeCipherSpec for wrong epoch");
                return false;
            }
            activateRead();
            return true;
        }

        byte[] plaintext;
        try {
            plaintext = decryptRecord(contentType, epoch, seq, body);
        } catch (HandshakeFormatException e) {
            fail(sink, AlertDescription.BAD_RECORD_MAC, "malformed or unauthenticated DTLS record");
            return false;
        } catch (GeneralSecurityException e) {
            fail(sink, AlertDescription.BAD_RECORD_MAC, "malformed or unauthenticated DTLS record");
            return false;
        }
        if (plaintext == null) {
            if (contentType == CONTENT_HANDSHAKE) {
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "discarded handshake record");
                return false;
            }
            return true;
        }

        if (read != null && read.overConfidentialityLimit()) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "AES-GCM read key exceeded its confidentiality limit");
            return false;
        }

        switch (contentType) {
            case CONTENT_ALERT:
                return handleAlert(plaintext, sink);
            case CONTENT_HANDSHAKE:
                return handleHandshake(plaintext, sink);
            case CONTENT_APPLICATION_DATA:
                if (!engine.isComplete()) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "application data before handshake completed");
                    return false;
                }
                sink.applicationDataReady(plaintext);
                return true;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "unknown record content type");
                return false;
        }
    }

    private byte[] decryptRecord(int contentType, int epoch, long seq, byte[] body)
            throws HandshakeFormatException, GeneralSecurityException {
        if (epoch == 0) {
            return body;
        }

        long combinedSeq = ((long) epoch << 48) | seq;
        Dtls12DirectionalKeys keys = selectReadKeys(epoch);
        DtlsReplayWindow replay = selectReadReplay(epoch);
        if (keys == null || replay == null) {
            throw new HandshakeFormatException("no read keys for epoch " + epoch);
        }
        if (!replay.mayAccept(combinedSeq)) {
            return null;
        }

        boolean hasExplicit = keys.hasExplicitNonce();
        int overhead = (hasExplicit ? 8 : 0) + 16;
        if (body.length < overhead) {
            throw new HandshakeFormatException("ciphertext shorter than AEAD overhead");
        }
        int ciphertextStart = hasExplicit ? 8 : 0;
        int plainLen = body.length - overhead;
        byte[] aad = keys.additionalData(contentType, plainLen);
        byte[] nonce = hasExplicit ? keys.nonceFromWire(Arrays.copyOfRange(body, 0, 8)) : keys.localNonce();
        byte[] ciphertext = Arrays.copyOfRange(body, ciphertextStart, body.length);
        byte[] plain = keys.openInPlace(nonce, aad, ciphertext);
        if (plain == null) {
            if (previousRead != null && epoch == readEpoch) {
                DtlsReplayWindow prevReplay = previousReadReplay;
                if (prevReplay != null && prevReplay.mayAccept(combinedSeq)) {
                    plain = tryDecryptWithKeys(previousRead, prevReplay, contentType, combinedSeq, body);
                }
            }
            if (plain == null) {
                throw new HandshakeFormatException("AEAD tag verification failed");
            }
            return plain;
        }
        replay.recordAccepted(combinedSeq);
        if (epoch == readEpoch && previousRead != null) {
            previousRead = null;
            previousReadReplay = null;
        }
        keys.advance();
        return plain;
    }

    private byte[] tryDecryptWithKeys(Dtls12DirectionalKeys keys, DtlsReplayWindow replay, int contentType,
            long combinedSeq, byte[] body) throws GeneralSecurityException, HandshakeFormatException {
        boolean hasExplicit = keys.hasExplicitNonce();
        int overhead = (hasExplicit ? 8 : 0) + 16;
        int ciphertextStart = hasExplicit ? 8 : 0;
        int plainLen = body.length - overhead;
        byte[] aad = keys.additionalData(contentType, plainLen);
        byte[] nonce = hasExplicit ? keys.nonceFromWire(Arrays.copyOfRange(body, 0, 8)) : keys.localNonce();
        byte[] ciphertext = Arrays.copyOfRange(body, ciphertextStart, body.length);
        byte[] plain = keys.openInPlace(nonce, aad, ciphertext);
        if (plain != null) {
            replay.recordAccepted(combinedSeq);
            keys.advance();
        }
        return plain;
    }

    private Dtls12DirectionalKeys selectReadKeys(int epoch) {
        if (read != null && read.epoch == epoch) {
            return read;
        }
        if (previousRead != null && previousRead.epoch == epoch) {
            return previousRead;
        }
        return null;
    }

    private DtlsReplayWindow selectReadReplay(int epoch) {
        if (read != null && read.epoch == epoch) {
            return readReplay;
        }
        if (previousReadReplay != null && previousRead != null && previousRead.epoch == epoch) {
            return previousReadReplay;
        }
        return null;
    }

    private boolean handleAlert(byte[] payload, TlsRecordSink sink) {
        if (payload.length != 2) {
            fail(sink, AlertDescription.DECODE_ERROR, "malformed alert record");
            return false;
        }
        int level = payload[0] & 0xff;
        int code = payload[1] & 0xff;
        if (code == ALERT_CLOSE_NOTIFY) {
            failed = true;
            alertSent = true;
            sink.peerClosed();
        } else {
            failed = true;
            alertSent = true;
            AlertDescription desc = AlertDescription.fromCode(code);
            String label = (level == ALERT_LEVEL_FATAL) ? "fatal" : "warning";
            String descLabel = (desc != null) ? desc.name() : ("code " + code);
            sink.protocolError(new TlsProtocolError(desc, "peer sent " + label + " alert " + descLabel));
        }
        return false;
    }

    private boolean handleHandshake(byte[] payload, TlsRecordSink sink) {
        if (payload.length < FRAGMENT_HEADER_LEN) {
            fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS handshake fragment");
            return false;
        }
        int msgType = payload[0] & 0xff;
        if (msgType == HANDSHAKE_TYPE_HELLO_VERIFY_REQUEST) {
            byte[] cookie = parseHelloVerifyRequest(payload);
            if (cookie != null && helloVerifyCallback != null) {
                helloVerifyCallback.onHelloVerifyRequest(cookie);
            }
            return true;
        }
        try {
            List<byte[]> messages = reassembler.addFragment(payload);
            for (int i = 0; i < messages.size(); i++) {
                engine.processMessage(messages.get(i), innerSink(sink));
                if (failed || engine.isFailed()) {
                    return false;
                }
            }
        } catch (HandshakeFormatException e) {
            fail(sink, AlertDescription.DECODE_ERROR, "malformed DTLS handshake fragment: " + e.getMessage());
            return false;
        }
        return true;
    }

    private void writeHandshakeMessage(byte[] handshakeMessage, TlsRecordSink sink) {
        int messageSeq = reassembler.nextWriteMessageSeq();
        int msgType = handshakeMessage[0] & 0xff;
        int bodyLen = ((handshakeMessage[1] & 0xff) << 16)
                | ((handshakeMessage[2] & 0xff) << 8)
                | (handshakeMessage[3] & 0xff);
        byte[] body = Arrays.copyOfRange(handshakeMessage, 4, 4 + bodyLen);
        int offset = 0;
        do {
            int chunkLen = Math.min(maxFragmentSize, body.length - offset);
            byte[] fragmentBody = buildFragment(msgType, body.length, messageSeq, offset, chunkLen, body, offset);
            writeOneRecord(CONTENT_HANDSHAKE, fragmentBody, sink);
            offset += chunkLen;
        } while (offset < body.length);
    }

    private static byte[] buildFragment(int msgType, int totalLength, int messageSeq, int fragmentOffset,
            int fragmentLength, byte[] body, int bodyOffset) {
        byte[] fragment = new byte[FRAGMENT_HEADER_LEN + fragmentLength];
        fragment[0] = (byte) msgType;
        fragment[1] = (byte) ((totalLength >> 16) & 0xff);
        fragment[2] = (byte) ((totalLength >> 8) & 0xff);
        fragment[3] = (byte) (totalLength & 0xff);
        fragment[4] = (byte) ((messageSeq >> 8) & 0xff);
        fragment[5] = (byte) (messageSeq & 0xff);
        fragment[6] = (byte) ((fragmentOffset >> 16) & 0xff);
        fragment[7] = (byte) ((fragmentOffset >> 8) & 0xff);
        fragment[8] = (byte) (fragmentOffset & 0xff);
        fragment[9] = (byte) ((fragmentLength >> 16) & 0xff);
        fragment[10] = (byte) ((fragmentLength >> 8) & 0xff);
        fragment[11] = (byte) (fragmentLength & 0xff);
        System.arraycopy(body, bodyOffset, fragment, FRAGMENT_HEADER_LEN, fragmentLength);
        return fragment;
    }

    private static byte[] parseHelloVerifyRequest(byte[] fragmentPayload) {
        if (fragmentPayload.length < FRAGMENT_HEADER_LEN + 2) {
            return null;
        }
        int totalLength = ((fragmentPayload[1] & 0xff) << 16)
                | ((fragmentPayload[2] & 0xff) << 8)
                | (fragmentPayload[3] & 0xff);
        int fragmentOffset = ((fragmentPayload[6] & 0xff) << 16)
                | ((fragmentPayload[7] & 0xff) << 8)
                | (fragmentPayload[8] & 0xff);
        int fragmentLength = ((fragmentPayload[9] & 0xff) << 16)
                | ((fragmentPayload[10] & 0xff) << 8)
                | (fragmentPayload[11] & 0xff);
        if (fragmentOffset != 0 || fragmentLength != totalLength) {
            return null;
        }
        if (FRAGMENT_HEADER_LEN + fragmentLength > fragmentPayload.length) {
            return null;
        }
        WireReader body = new WireReader(Arrays.copyOfRange(fragmentPayload, FRAGMENT_HEADER_LEN,
                FRAGMENT_HEADER_LEN + fragmentLength));
        try {
            body.u16();
            return body.opaque8();
        } catch (HandshakeFormatException e) {
            return null;
        }
    }

    private void writeOneRecord(int contentType, byte[] payload, TlsRecordSink sink) {
        byte[] datagram;
        if (write == null) {
            datagram = framePlaintextRecord(contentType, writeEpoch, plaintextWriteSeq, payload);
            plaintextWriteSeq++;
        } else {
            datagram = frameEncryptedRecord(contentType, write, payload);
        }
        sink.ciphertextReady(datagram);
    }

    private byte[] framePlaintextRecord(int contentType, int epoch, long seq, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRecordHeader(out, contentType, epoch, seq, payload.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private byte[] frameEncryptedRecord(int contentType, Dtls12DirectionalKeys keys, byte[] payload) {
        byte[] aad = keys.additionalData(contentType, payload.length);
        byte[] nonce = keys.localNonce();
        byte[] sealed;
        try {
            sealed = keys.sealAppendTag(nonce, aad, payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Record seal failed", e);
        }
        boolean hasExplicit = keys.hasExplicitNonce();
        int recordLen = (hasExplicit ? 8 : 0) + sealed.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRecordHeader(out, contentType, keys.epoch, keys.seq, recordLen);
        if (hasExplicit) {
            byte[] explicitNonce = keys.combinedSeqBytes();
            out.write(explicitNonce, 0, explicitNonce.length);
        }
        out.write(sealed, 0, sealed.length);
        keys.advance();
        return out.toByteArray();
    }

    private static void writeRecordHeader(ByteArrayOutputStream out, int contentType, int epoch, long seq, int length) {
        out.write(contentType);
        out.write(DTLS_VERSION_MAJOR);
        out.write(DTLS_VERSION_MINOR);
        out.write((epoch >> 8) & 0xff);
        out.write(epoch & 0xff);
        for (int i = 0; i < 6; i++) {
            out.write((byte) (seq >>> (40 - 8 * i)));
        }
        out.write((length >> 8) & 0xff);
        out.write(length & 0xff);
    }

    private void activateRead() {
        if (pendingRead == null) {
            return;
        }
        if (read != null) {
            previousRead = read;
            previousReadReplay = readReplay;
        }
        readEpoch++;
        read = Dtls12DirectionalKeys.fromMaterial(stagedSuite, pendingRead, readEpoch);
        readReplay = new DtlsReplayWindow();
        pendingRead = null;
    }

    private void activateWrite() {
        if (pendingWrite == null) {
            return;
        }
        writeEpoch++;
        write = Dtls12DirectionalKeys.fromMaterial(stagedSuite, pendingWrite, writeEpoch);
        pendingWrite = null;
    }

    private void sendFatalAlert(AlertDescription alert, TlsRecordSink sink) {
        if (alertSent) {
            return;
        }
        alertSent = true;
        int code = (alert != null) ? alert.getCode() : AlertDescription.INTERNAL_ERROR.getCode();
        writeOneRecord(CONTENT_ALERT, new byte[] { (byte) ALERT_LEVEL_FATAL, (byte) code }, sink);
    }

    private void fail(TlsRecordSink sink, AlertDescription alert, String message) {
        if (failed) {
            return;
        }
        failed = true;
        sendFatalAlert(alert, sink);
        sink.protocolError(new TlsProtocolError(alert, message));
    }

    private final class InnerSink implements Tls12EventSink {

        TlsRecordSink outer;

        @Override
        public void handshakeDataReady(byte[] data) {
            writeHandshakeMessage(data, outer);
        }

        @Override
        public void keysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client, DirectionalKeyMaterial server) {
            stagedSuite = cipher;
            if (role == HandshakeRole.CLIENT) {
                pendingWrite = client;
                pendingRead = server;
            } else {
                pendingWrite = server;
                pendingRead = client;
            }
        }

        @Override
        public void sendChangeCipherSpec() {
            writeOneRecord(CONTENT_CHANGE_CIPHER_SPEC, new byte[] { 0x01 }, outer);
            activateWrite();
        }

        @Override
        public void handshakeComplete() {
            outer.handshakeComplete();
        }

        @Override
        public void protocolError(TlsProtocolError error) {
            sendFatalAlert(error.getAlert(), outer);
            outer.protocolError(error);
        }
    }

}
