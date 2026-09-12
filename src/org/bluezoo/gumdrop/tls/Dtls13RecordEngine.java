/*
 * Dtls13RecordEngine.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.io.ByteArrayOutputStream;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.tls.HandshakeAsyncOffload;
import org.bluezoo.gumdrop.tls.HandshakeAsyncScheduler;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionException;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;

/**
 * DTLS 1.3 record layer (RFC 9147) wrapping {@link HandshakeEngine}.
 */
public final class Dtls13RecordEngine {

    static final int DTLS_VERSION_MAJOR = 0xfe;
    static final int DTLS_VERSION_MINOR = 0xfd;
    static final int RECORD_HEADER_LEN = 13;
    static final int FRAGMENT_HEADER_LEN = 12;
    static final int UNIFIED_HEADER_LEN = 5;
    static final int UNIFIED_HEADER_BASE = 0x2c;
    static final int EPOCH_PLAINTEXT = 0;
    static final int EPOCH_HANDSHAKE = 2;
    static final int EPOCH_APPLICATION = 3;
    static final int TRUNCATED_PN_BITS = 16;
    static final int TRUNCATED_PN_LENGTH = 2;

    private static final int CONTENT_CHANGE_CIPHER_SPEC = 20;
    private static final int CONTENT_ALERT = 21;
    private static final int CONTENT_HANDSHAKE = 22;
    private static final int CONTENT_APPLICATION_DATA = 23;
    private static final int CONTENT_ACK = 26;

    private static final int ALERT_LEVEL_WARNING = 1;
    private static final int ALERT_LEVEL_FATAL = 2;
    private static final int ALERT_CLOSE_NOTIFY = 0;

    private static final int MAX_FRAGMENT = 16384;
    private static final int AEAD_TAG_LENGTH = 16;

    private final HandshakeEngine engine;
    private final HandshakeRole role;
    private final DtlsReassembler reassembler = new DtlsReassembler();
    private final int maxFragmentSize;
    private final InnerSink innerSink = new InnerSink();
    private final HandshakeRunner handshakeRunner = new HandshakeRunner();
    private final HandshakeFailureHandler handshakeFailure = new HandshakeFailureHandler();
    private final Tls13DeferredDispatch deferredDispatch;
    private final HandshakeAsyncScheduler handshakeAsync;

    Dtls13DirectionalKeys write;
    Dtls13DirectionalKeys read;
    DtlsReplayWindow readReplay;

    Dtls13DirectionalKeys previousRead;
    DtlsReplayWindow previousReadReplay;

    private int readEpoch;
    private int writeEpoch;
    private long plaintextWriteSeq;
    private long lastPeerCombinedSeq = -1L;
    private boolean ackSent;
    private boolean pendingAck;
    private boolean alertSent;
    private boolean failed;

    public Dtls13RecordEngine(Dtls13HandshakeConfig config, int maxFragmentSize) {
        this(config, maxFragmentSize, null);
    }

    public Dtls13RecordEngine(Dtls13HandshakeConfig config, int maxFragmentSize, HandshakeAsyncOffload offload) {
        this.role = config.getBase().getRole();
        HandshakeConfig base = config.getBase();
        base.setMode(HandshakeMode.DTLS);
        this.engine = new HandshakeEngine(base);
        this.maxFragmentSize = Math.max(1, Math.min(maxFragmentSize, MAX_FRAGMENT));
        this.handshakeAsync = new HandshakeAsyncScheduler(offload, handshakeRunner, handshakeFailure);
        this.deferredDispatch = new Tls13DeferredDispatch(handshakeAsync, innerSink);
    }

    public void start(TlsRecordSink sink) {
        innerSink.outer = sink;
        handshakeAsync.scheduleStart();
    }

    public boolean isComplete() {
        return engine.isComplete();
    }

    public boolean isFailed() {
        return failed || engine.isFailed();
    }

    public void feedDatagram(byte[] datagram, TlsRecordSink sink) {
        feedDatagram(datagram, 0, datagram.length, sink);
    }

    public void feedDatagram(byte[] datagram, int baseOffset, int length, TlsRecordSink sink) {
        if (failed) {
            return;
        }
        innerSink.outer = sink;
        int end = baseOffset + length;
        int offset = baseOffset;
        while (offset < end) {
            if ((datagram[offset] & 0xe0) == 0x20) {
                if (end - offset < UNIFIED_HEADER_LEN) {
                    fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS unified header");
                    return;
                }
                int epoch = datagram[offset] & 0x03;
                int bodyLength = ((datagram[offset + 3] & 0xff) << 8) | (datagram[offset + 4] & 0xff);
                if (offset + UNIFIED_HEADER_LEN + bodyLength > end) {
                    fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS unified record body");
                    return;
                }
                byte[] record = Arrays.copyOfRange(datagram, offset, offset + UNIFIED_HEADER_LEN + bodyLength);
                offset += UNIFIED_HEADER_LEN + bodyLength;
                if (!processUnifiedRecord(epoch, record, sink)) {
                    return;
                }
            } else {
                if (end - offset < RECORD_HEADER_LEN) {
                    fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS record header");
                    return;
                }
                int contentType = datagram[offset] & 0xff;
                if ((datagram[offset + 1] & 0xff) != DTLS_VERSION_MAJOR
                        || (datagram[offset + 2] & 0xff) != DTLS_VERSION_MINOR) {
                    fail(sink, AlertDescription.PROTOCOL_VERSION, "unexpected DTLS version");
                    return;
                }
                int epoch = ((datagram[offset + 3] & 0xff) << 8) | (datagram[offset + 4] & 0xff);
                long seq = 0L;
                for (int i = 0; i < 6; i++) {
                    seq = (seq << 8) | (datagram[offset + 5 + i] & 0xff);
                }
                int recordLength = ((datagram[offset + 11] & 0xff) << 8) | (datagram[offset + 12] & 0xff);
                if (offset + RECORD_HEADER_LEN + recordLength > end) {
                    fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS record body");
                    return;
                }
                byte[] body = Arrays.copyOfRange(datagram, offset + RECORD_HEADER_LEN,
                        offset + RECORD_HEADER_LEN + recordLength);
                offset += RECORD_HEADER_LEN + recordLength;
                if (!processCleartextRecord(contentType, epoch, seq, body, sink)) {
                    return;
                }
            }
        }
    }

    public void sendApplicationData(byte[] plaintext, TlsRecordSink sink) {
        sendApplicationData(plaintext, 0, plaintext.length, sink);
    }

    public void sendApplicationData(byte[] plaintext, int offset, int length, TlsRecordSink sink) {
        if (failed) {
            return;
        }
        if (write == null || writeEpoch != EPOCH_APPLICATION) {
            sink.protocolError(new TlsProtocolError(AlertDescription.INTERNAL_ERROR,
                    "application data sent before handshake completed"));
            return;
        }
        writeUnifiedRecord(CONTENT_APPLICATION_DATA, plaintext, offset, length, sink);
        if (write.overConfidentialityLimit()) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "AES-GCM write key exceeded its confidentiality limit");
        }
    }

    public void sendCloseNotify(TlsRecordSink sink) {
        if (failed) {
            return;
        }
        if (write == null) {
            writeCleartextRecord(CONTENT_ALERT,
                    new byte[] { (byte) ALERT_LEVEL_WARNING, (byte) ALERT_CLOSE_NOTIFY }, sink);
        } else {
            writeUnifiedRecord(CONTENT_ALERT,
                    new byte[] { (byte) ALERT_LEVEL_WARNING, (byte) ALERT_CLOSE_NOTIFY }, sink);
        }
    }

    public CipherSuite getNegotiatedCipherSuite() {
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

    private TlsEventSink innerSink(TlsRecordSink outer) {
        innerSink.outer = outer;
        return innerSink;
    }

    private final class HandshakeRunner implements HandshakeAsyncScheduler.Runner {
        @Override
        public void runStart() {
            deferredDispatch.resetSlots();
            engine.start(innerSink);
        }

        @Override
        public void runMessages(List<byte[]> messages) {
            for (int i = 0; i < messages.size(); i++) {
                deferredDispatch.resetSlots();
                engine.processMessage(messages.get(i), innerSink);
                if (failed || engine.isFailed()) {
                    break;
                }
            }
        }
    }

    private final class HandshakeFailureHandler implements HandshakeAsyncOffload.FailureHandler {
        @Override
        public void failed(Throwable error) {
            fail(innerSink.outer, AlertDescription.INTERNAL_ERROR,
                    "handshake processing failed: " + error);
        }
    }

    private boolean processCleartextRecord(int contentType, int epoch, long seq, byte[] body, TlsRecordSink sink) {
        if (epoch != EPOCH_PLAINTEXT) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "unexpected cleartext epoch " + epoch);
            return false;
        }
        switch (contentType) {
            case CONTENT_HANDSHAKE:
                return handleHandshake(body, sink);
            case CONTENT_ALERT:
                return handleAlert(body, sink);
            case CONTENT_CHANGE_CIPHER_SPEC:
                return true;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "unknown cleartext content type");
                return false;
        }
    }

    private boolean processUnifiedRecord(int epoch, byte[] record, TlsRecordSink sink) {
        if (record.length < UNIFIED_HEADER_LEN + AEAD_TAG_LENGTH) {
            fail(sink, AlertDescription.BAD_RECORD_MAC, "DTLS unified record too short");
            return false;
        }
        byte[] header = Arrays.copyOfRange(record, 0, UNIFIED_HEADER_LEN);
        byte[] ciphertext = Arrays.copyOfRange(record, UNIFIED_HEADER_LEN, record.length);
        byte[] sample = Arrays.copyOfRange(ciphertext, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
        Dtls13DirectionalKeys keys = selectReadKeys(epoch);
        DtlsReplayWindow replay = selectReadReplay(epoch);
        if (keys == null || replay == null) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "no read keys for epoch " + epoch);
            return false;
        }
        try {
            byte[] mask = keys.headerProtectionMask(sample);
            PacketProtection.xorPacketNumberBytes(header, 1, TRUNCATED_PN_LENGTH, mask);
        } catch (PacketProtectionException e) {
            fail(sink, AlertDescription.BAD_RECORD_MAC, "header protection failed");
            return false;
        }
        int truncatedPn = ((header[1] & 0xff) << 8) | (header[2] & 0xff);
        long highest = replay.highestAccepted();
        long seq = reconstructSequenceNumber(highest, truncatedPn, TRUNCATED_PN_BITS);
        long combinedSeq = combinedSeq(epoch, seq);
        if (!replay.mayAccept(combinedSeq)) {
            return true;
        }
        byte[] plain;
        try {
            plain = keys.open(seq, header, ciphertext);
        } catch (PacketProtectionException e) {
            plain = null;
        }
        if (plain == null && previousRead != null && epoch == readEpoch) {
            DtlsReplayWindow prevReplay = previousReadReplay;
            if (prevReplay != null && prevReplay.mayAccept(combinedSeq)) {
                try {
                    plain = previousRead.open(seq, header, ciphertext);
                    if (plain != null) {
                        prevReplay.recordAccepted(combinedSeq);
                        previousRead.advance();
                    }
                } catch (PacketProtectionException ignored) {
                    plain = null;
                }
            }
        }
        if (plain == null) {
            fail(sink, AlertDescription.BAD_RECORD_MAC, "malformed or unauthenticated DTLS record");
            return false;
        }
        replay.recordAccepted(combinedSeq);
        keys.advance();
        lastPeerCombinedSeq = combinedSeq;
        if (epoch == readEpoch && previousRead != null) {
            previousRead = null;
            previousReadReplay = null;
        }
        if (keys.overConfidentialityLimit()) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "AES-GCM read key exceeded its confidentiality limit");
            return false;
        }
        return dispatchInnerRecord(plain, sink);
    }

    private boolean dispatchInnerRecord(byte[] plain, TlsRecordSink sink) {
        int end = plain.length;
        while (end > 0 && plain[end - 1] == 0) {
            end--;
        }
        if (end == 0) {
            fail(sink, AlertDescription.DECODE_ERROR, "empty inner plaintext");
            return false;
        }
        int innerType = plain[end - 1] & 0xff;
        byte[] payload = Arrays.copyOfRange(plain, 0, end - 1);
        if (innerType == CONTENT_ACK) {
            return true;
        }
        switch (innerType) {
            case CONTENT_ALERT:
                return handleAlert(payload, sink);
            case CONTENT_HANDSHAKE:
                return handleHandshake(payload, sink);
            case CONTENT_APPLICATION_DATA:
                if (!engine.isComplete()) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "application data before handshake completed");
                    return false;
                }
                sink.applicationDataReady(payload);
                return true;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "unknown inner content type");
                return false;
        }
    }

    private boolean handleHandshake(byte[] payload, TlsRecordSink sink) {
        if (payload.length < FRAGMENT_HEADER_LEN) {
            fail(sink, AlertDescription.DECODE_ERROR, "truncated DTLS handshake fragment");
            return false;
        }
        try {
            List<byte[]> messages = reassembler.addFragment(payload);
            if (!messages.isEmpty()) {
                handshakeAsync.scheduleMessages(messages);
            }
        } catch (HandshakeFormatException e) {
            fail(sink, AlertDescription.DECODE_ERROR, "malformed DTLS handshake fragment: " + e.getMessage());
            return false;
        }
        return true;
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
            if (write == null) {
                writeCleartextRecord(CONTENT_HANDSHAKE, fragmentBody, sink);
            } else {
                writeUnifiedRecord(CONTENT_HANDSHAKE, fragmentBody, sink);
            }
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

    private void writeCleartextRecord(int contentType, byte[] payload, TlsRecordSink sink) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRecordHeader(out, contentType, EPOCH_PLAINTEXT, plaintextWriteSeq, payload.length);
        out.write(payload, 0, payload.length);
        plaintextWriteSeq++;
        sink.ciphertextReady(out.toByteArray());
    }

    private void writeUnifiedRecord(int innerContentType, byte[] payload, TlsRecordSink sink) {
        writeUnifiedRecord(innerContentType, payload, 0, payload.length, sink);
    }

    private void writeUnifiedRecord(int innerContentType, byte[] payload, int offset, int length,
            TlsRecordSink sink) {
        if (write == null) {
            sink.protocolError(new TlsProtocolError(AlertDescription.INTERNAL_ERROR, "no write keys"));
            return;
        }
        byte[] innerPlain = new byte[length + 1];
        System.arraycopy(payload, offset, innerPlain, 0, length);
        innerPlain[length] = (byte) innerContentType;
        long seq = write.seq;
        byte[] header = new byte[UNIFIED_HEADER_LEN];
        header[0] = (byte) (UNIFIED_HEADER_BASE | write.epoch);
        header[1] = (byte) ((seq >> 8) & 0xff);
        header[2] = (byte) (seq & 0xff);
        int ciphertextLength = innerPlain.length + AEAD_TAG_LENGTH;
        header[3] = (byte) ((ciphertextLength >> 8) & 0xff);
        header[4] = (byte) (ciphertextLength & 0xff);
        try {
            byte[] ciphertext = write.seal(seq, header, innerPlain);
            if (ciphertext.length != ciphertextLength) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "unexpected DTLS ciphertext length");
                return;
            }
            byte[] sample = Arrays.copyOfRange(ciphertext, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
            byte[] mask = write.headerProtectionMask(sample);
            PacketProtection.xorPacketNumberBytes(header, 1, TRUNCATED_PN_LENGTH, mask);
            byte[] record = new byte[UNIFIED_HEADER_LEN + ciphertext.length];
            System.arraycopy(header, 0, record, 0, UNIFIED_HEADER_LEN);
            System.arraycopy(ciphertext, 0, record, UNIFIED_HEADER_LEN, ciphertext.length);
            write.advance();
            sink.ciphertextReady(record);
        } catch (PacketProtectionException e) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "record seal failed");
        }
    }

    private void maybeSendAck(TlsRecordSink sink) {
        if (ackSent || !pendingAck || lastPeerCombinedSeq < 0L) {
            return;
        }
        if (write == null || writeEpoch != EPOCH_APPLICATION) {
            return;
        }
        byte[] ackBody = new byte[8];
        for (int i = 0; i < 8; i++) {
            ackBody[i] = (byte) (lastPeerCombinedSeq >>> (56 - 8 * i));
        }
        writeUnifiedRecord(CONTENT_ACK, ackBody, sink);
        ackSent = true;
        pendingAck = false;
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

    private static long combinedSeq(int epoch, long seq) {
        return ((long) epoch << 48) | (seq & 0x0000ffffffffffffL);
    }

    static long reconstructSequenceNumber(long highestAccepted, int truncatedPn, int pnBits) {
        if (highestAccepted < 0L) {
            return truncatedPn & 0xffffL;
        }
        long highestSeq = highestAccepted & 0x0000ffffffffffffL;
        long expected = highestSeq + 1;
        long pnWin = 1L << pnBits;
        long pnHwin = pnWin / 2;
        long pnMask = pnWin - 1;
        long candidate = (expected & ~pnMask) | (truncatedPn & pnMask);
        if (candidate <= expected - pnHwin && candidate < (1L << 62) - pnWin) {
            candidate += pnWin;
        } else if (candidate > expected + pnHwin && candidate >= pnWin) {
            candidate -= pnWin;
        }
        return candidate;
    }

    private Dtls13DirectionalKeys selectReadKeys(int epoch) {
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

    private void installDirectionalKeys(int newEpoch) {
        CipherSuite suite = engine.getNegotiatedCipherSuite();
        byte[] ownSecret;
        byte[] peerSecret;
        if (newEpoch == EPOCH_HANDSHAKE) {
            ownSecret = (role == HandshakeRole.CLIENT)
                    ? engine.getClientHandshakeTrafficSecret() : engine.getServerHandshakeTrafficSecret();
            peerSecret = (role == HandshakeRole.CLIENT)
                    ? engine.getServerHandshakeTrafficSecret() : engine.getClientHandshakeTrafficSecret();
        } else {
            ownSecret = (role == HandshakeRole.CLIENT)
                    ? engine.getClientApplicationTrafficSecret() : engine.getServerApplicationTrafficSecret();
            peerSecret = (role == HandshakeRole.CLIENT)
                    ? engine.getServerApplicationTrafficSecret() : engine.getClientApplicationTrafficSecret();
        }
        if (read != null) {
            previousRead = read;
            previousReadReplay = readReplay;
        }
        readEpoch = newEpoch;
        writeEpoch = newEpoch;
        write = Dtls13DirectionalKeys.fromSecret(suite, ownSecret, newEpoch);
        read = Dtls13DirectionalKeys.fromSecret(suite, peerSecret, newEpoch);
        readReplay = new DtlsReplayWindow();
    }

    private void sendFatalAlert(AlertDescription alert, TlsRecordSink sink) {
        if (alertSent) {
            return;
        }
        alertSent = true;
        int code = (alert != null) ? alert.getCode() : AlertDescription.INTERNAL_ERROR.getCode();
        byte[] alertBytes = new byte[] { (byte) ALERT_LEVEL_FATAL, (byte) code };
        if (write == null) {
            writeCleartextRecord(CONTENT_ALERT, alertBytes, sink);
        } else {
            writeUnifiedRecord(CONTENT_ALERT, alertBytes, sink);
        }
    }

    private void fail(TlsRecordSink sink, AlertDescription alert, String message) {
        if (failed) {
            return;
        }
        failed = true;
        sendFatalAlert(alert, sink);
        sink.protocolError(new TlsProtocolError(alert, message));
    }

    private final class InnerSink implements TlsEventSink, Tls13DeferredDispatch.Target {

        TlsRecordSink outer;

        @Override
        public void handshakeDataReady(byte[] data) {
            deferredDispatch.handshakeDataReady(data);
        }

        @Override
        public void handshakeSecretsReady() {
            deferredDispatch.handshakeSecretsReady();
        }

        @Override
        public void applicationSecretsReady() {
            deferredDispatch.applicationSecretsReady();
        }

        @Override
        public void applicationTrafficSecretUpdated(KeyUpdateDirection direction, byte[] newSecret) {
            deferredDispatch.applicationTrafficSecretUpdated(direction, newSecret);
        }

        @Override
        public void protocolError(TlsProtocolError error) {
            deferredDispatch.protocolError(error);
        }

        @Override
        public void peerClosed() {
            deferredDispatch.peerClosed();
        }

        @Override
        public void deliverHandshakeData(byte[] data) {
            writeHandshakeMessage(data, outer);
        }

        @Override
        public void deliverHandshakeSecretsReady() {
            installDirectionalKeys(EPOCH_HANDSHAKE);
        }

        @Override
        public void deliverApplicationSecretsReady() {
            installDirectionalKeys(EPOCH_APPLICATION);
            pendingAck = true;
            outer.handshakeComplete();
            maybeSendAck(outer);
        }

        @Override
        public void deliverKeyUpdate(KeyUpdateDirection direction, byte[] newSecret) {
            fail(outer, AlertDescription.UNEXPECTED_MESSAGE, "KeyUpdate is not supported over DTLS 1.3");
        }

        @Override
        public void deliverProtocolError(TlsProtocolError error) {
            sendFatalAlert(error.getAlert(), outer);
            outer.protocolError(error);
        }

        @Override
        public void deliverPeerClosed() {
            outer.peerClosed();
        }
    }
}
