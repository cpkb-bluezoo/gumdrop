/*
 * Tls12RecordEngine.java
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
 * The TLS 1.2 record layer (RFC 5246 section 6, RFC 5288 GCM, RFC 7905
 * ChaCha20-Poly1305) for TCP: wraps {@link Tls12HandshakeEngine} with
 * record framing and AEAD. Reactive like {@link TlsRecordEngine} (TLS
 * 1.3) and implementing the exact same {@link TlsRecordSink} interface,
 * unchanged -- its shape (ciphertext out; application data, handshake
 * completion, protocol error, and peer-close events in) isn't actually
 * TLS-1.3-specific, so no separate 1.2-flavored sink is needed, and
 * {@code TlsRecordState}'s existing sink adapter pattern in
 * {@code org.bluezoo.gumdrop} carries over unchanged (a sibling
 * {@code Tls12RecordState} class drives this engine instead).
 *
 * <p>Unlike {@link TlsRecordEngine}, {@code ChangeCipherSpec} here is a
 * real wire signal (content type 20, not middlebox-compat theater) that
 * actually switches an epoch -- key material staged by {@link Tls12EventSink#keysReady}
 * is only <em>activated</em> when this side sends its own CCS (write
 * epoch) or the peer's CCS arrives on the wire (read epoch). And unlike
 * TLS 1.3's opaque {@code application_data} outer type hiding the real
 * content, TLS 1.2 records carry their true content type in the record
 * header even once encrypted, so there is no inner-type unwrapping to do
 * here either.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5246#section-6">RFC 5246 section 6</a>
 */
public final class Tls12RecordEngine {

    private static final int CONTENT_CHANGE_CIPHER_SPEC = 20;
    private static final int CONTENT_ALERT = 21;
    private static final int CONTENT_HANDSHAKE = 22;
    private static final int CONTENT_APPLICATION_DATA = 23;

    private static final int ALERT_LEVEL_WARNING = 1;
    private static final int ALERT_LEVEL_FATAL = 2;
    private static final int ALERT_CLOSE_NOTIFY = 0;

    /** RFC 5246 section 6.2.1: plaintext fragments are capped at 2^14 bytes. */
    private static final int MAX_FRAGMENT = 16384;

    /**
     * Upper bound over every supported cipher's per-record overhead --
     * GCM's 8-byte explicit nonce + 16-byte tag (RFC 5288 section 3;
     * ChaCha20-Poly1305's actual overhead is smaller, well within this
     * bound). Used only as an early sanity check on the record length
     * before the negotiated cipher's exact overhead applies in
     * {@link #takeOneRecord}.
     */
    private static final int MAX_CIPHERTEXT_RECORD = MAX_FRAGMENT + 8 + 16;

    private static final class GrowableBuffer extends ByteArrayOutputStream {
        byte[] array() {
            return buf;
        }

        int length() {
            return count;
        }

        void discard(int n) {
            if (n <= 0) {
                return;
            }
            System.arraycopy(buf, n, buf, 0, count - n);
            count -= n;
        }
    }

    private static final class Record {
        final int contentType;
        final byte[] payload;

        Record(int contentType, byte[] payload) {
            this.contentType = contentType;
            this.payload = payload;
        }
    }

    private final Tls12HandshakeEngine engine;
    private final HandshakeRole role;
    private final InnerSink innerSink = new InnerSink();
    private final GrowableBuffer inbound = new GrowableBuffer();

    // Package-private, not private: Tls12RecordEngineTest fast-forwards
    // Tls12DirectionalKeys.seq directly on these to exercise the
    // confidentiality-limit close without actually protecting 23 million
    // records.
    Tls12DirectionalKeys write;
    Tls12DirectionalKeys read;

    private DirectionalKeyMaterial pendingWrite;
    private DirectionalKeyMaterial pendingRead;
    private Tls12CipherSuite stagedSuite;

    // At most one fatal alert per connection, and no reply to a peer's own alert.
    private boolean alertSent;

    private boolean failed;

    /**
     * Creates a TCP record-layer engine, wrapping a {@link Tls12HandshakeEngine}.
     *
     * @param config this side's configuration
     */
    public Tls12RecordEngine(Tls12HandshakeConfig config) {
        this.role = config.getRole();
        this.engine = new Tls12HandshakeEngine(config);
    }

    /**
     * Begins the handshake -- client emits {@code ClientHello}; server
     * waits for input.
     *
     * @param sink where to push resulting events
     */
    public void start(TlsRecordSink sink) {
        engine.start(innerSink(sink));
    }

    /**
     * Returns whether the handshake has completed.
     *
     * @return true once the handshake has finished
     */
    public boolean isComplete() {
        return engine.isComplete();
    }

    /**
     * Consumes raw bytes off the TCP stream -- any number of complete or
     * partial records. Buffers a trailing partial record for the next call.
     *
     * @param input the received bytes
     * @param sink where to push resulting events
     */
    public void feedCiphertext(byte[] input, TlsRecordSink sink) {
        if (failed) {
            return;
        }
        inbound.write(input, 0, input.length);
        while (true) {
            Record record;
            try {
                record = takeOneRecord();
            } catch (HandshakeFormatException e) {
                fail(sink, AlertDescription.BAD_RECORD_MAC, "malformed or unauthenticated TLS record");
                return;
            } catch (GeneralSecurityException e) {
                fail(sink, AlertDescription.BAD_RECORD_MAC, "malformed or unauthenticated TLS record");
                return;
            }
            if (record == null) {
                return;
            }
            // RFC 8446 section 5.5 / RFC 9325 section 4.4: TLS 1.2 has no
            // KeyUpdate to fall back on, so a read key that protected too
            // many records under one key must close the connection
            // rather than keep decrypting past the safety margin.
            if (read != null && read.overConfidentialityLimit()) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "AES-GCM read key exceeded its confidentiality limit");
                return;
            }
            if (!dispatchRecord(record.contentType, record.payload, sink)) {
                return;
            }
        }
    }

    /**
     * Encrypts and frames application data. Only valid once
     * {@link #isComplete()}.
     *
     * @param plaintext the application data to send
     * @param sink where to push resulting events
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
        writeFragmented(CONTENT_APPLICATION_DATA, plaintext, sink);
        // Same reasoning as the read-side check above: no rekey mechanism
        // exists in TLS 1.2, so close rather than keep encrypting past
        // the AES-GCM safety margin.
        if (write.overConfidentialityLimit()) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "AES-GCM write key exceeded its confidentiality limit");
        }
    }

    /**
     * Sends a {@code close_notify} alert under the current epoch.
     *
     * @param sink where to push resulting events
     */
    public void sendCloseNotify(TlsRecordSink sink) {
        if (failed) {
            return;
        }
        writeFragmented(CONTENT_ALERT, new byte[] { (byte) ALERT_LEVEL_WARNING, (byte) ALERT_CLOSE_NOTIFY }, sink);
    }

    /**
     * Returns the negotiated cipher suite, once known.
     *
     * @return the negotiated cipher suite, or null if not yet negotiated
     */
    public Tls12CipherSuite getNegotiatedCipherSuite() {
        return engine.getNegotiatedCipherSuite();
    }

    /**
     * Returns the negotiated ALPN application protocol, once known.
     *
     * @return the negotiated protocol name, or null if none was negotiated
     */
    public String getNegotiatedApplicationProtocol() {
        return engine.getNegotiatedApplicationProtocol();
    }

    /**
     * Returns the peer's certificate chain.
     *
     * @return the peer's certificate chain, leaf first, or null
     */
    public List<X509Certificate> getPeerCertificateChain() {
        return engine.getPeerCertificateChain();
    }

    /**
     * Returns whether this handshake resumed a previous session via an
     * RFC 5077 ticket.
     *
     * @return true if this is a resumed handshake
     */
    public boolean isResumed() {
        return engine.isResumed();
    }

    private Tls12EventSink innerSink(TlsRecordSink outer) {
        innerSink.outer = outer;
        return innerSink;
    }

    /**
     * Dispatches one already-decrypted {@code (content type, payload)}
     * pair. Returns false if the caller should stop processing further
     * buffered records this call.
     */
    private boolean dispatchRecord(int contentType, byte[] payload, TlsRecordSink sink) {
        switch (contentType) {
            case CONTENT_CHANGE_CIPHER_SPEC:
                activateRead();
                return true;
            case CONTENT_ALERT: {
                if (payload.length != 2) {
                    fail(sink, AlertDescription.DECODE_ERROR, "malformed alert record");
                    return false;
                }
                int level = payload[0] & 0xff;
                int code = payload[1] & 0xff;
                if (code == ALERT_CLOSE_NOTIFY) {
                    failed = true;
                    alertSent = true; // no close_notify echo needed
                    sink.peerClosed();
                } else {
                    // Relay the peer's own alert; don't send one back (no
                    // alert-acknowledgment concept in RFC 5246 section 7.2
                    // either, and the peer is already tearing down).
                    failed = true;
                    alertSent = true;
                    AlertDescription desc = AlertDescription.fromCode(code);
                    String label = (level == ALERT_LEVEL_FATAL) ? "fatal" : "warning";
                    String descLabel = (desc != null) ? desc.name() : ("code " + code);
                    sink.protocolError(new TlsProtocolError(desc, "peer sent " + label + " alert " + descLabel));
                }
                return false;
            }
            case CONTENT_HANDSHAKE:
                engine.processMessage(payload, innerSink(sink));
                return true;
            case CONTENT_APPLICATION_DATA:
                if (!engine.isComplete()) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "application data before handshake completed");
                    return false;
                }
                sink.applicationDataReady(payload);
                return true;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "unknown record content type");
                return false;
        }
    }

    /**
     * Pops one record off {@link #inbound}, decrypting it if a read
     * epoch is active. Returns null if no complete record is available yet.
     */
    private Record takeOneRecord() throws HandshakeFormatException, GeneralSecurityException {
        int available = inbound.length();
        if (available < 5) {
            return null;
        }
        byte[] buffered = inbound.array();
        int hdrType = buffered[0] & 0xff;
        int len = ((buffered[3] & 0xff) << 8) | (buffered[4] & 0xff);
        if (len > MAX_CIPHERTEXT_RECORD) {
            throw new HandshakeFormatException("record length exceeds maximum");
        }
        if (available < 5 + len) {
            return null;
        }
        byte[] body = Arrays.copyOfRange(buffered, 5, 5 + len);
        inbound.discard(5 + len);

        if (hdrType == CONTENT_CHANGE_CIPHER_SPEC) {
            return new Record(CONTENT_CHANGE_CIPHER_SPEC, new byte[0]);
        }
        if (read == null) {
            return new Record(hdrType, body);
        }

        boolean hasExplicit = read.hasExplicitNonce();
        int overhead = (hasExplicit ? 8 : 0) + 16;
        if (body.length < overhead) {
            throw new HandshakeFormatException("ciphertext shorter than AEAD overhead");
        }
        int ciphertextStart = hasExplicit ? 8 : 0;
        int plainLen = body.length - overhead;
        byte[] aad = additionalData(read.seq, hdrType, plainLen);
        byte[] nonce = hasExplicit ? read.nonceFromWire(Arrays.copyOfRange(body, 0, 8)) : read.localNonce();
        byte[] ciphertext = Arrays.copyOfRange(body, ciphertextStart, body.length);
        byte[] plain = read.openInPlace(nonce, aad, ciphertext);
        read.advance();
        if (plain == null) {
            throw new HandshakeFormatException("AEAD tag verification failed");
        }
        return new Record(hdrType, plain);
    }

    /** RFC 5288 section 3 AEAD {@code additional_data} (adapted from RFC 5246 section 6.2.3.3's MAC input). */
    private static byte[] additionalData(long seq, int contentType, int plaintextLen) {
        byte[] aad = new byte[13];
        for (int i = 0; i < 8; i++) {
            aad[i] = (byte) (seq >>> (56 - 8 * i));
        }
        aad[8] = (byte) contentType;
        aad[9] = 0x03;
        aad[10] = 0x03;
        aad[11] = (byte) ((plaintextLen >> 8) & 0xff);
        aad[12] = (byte) (plaintextLen & 0xff);
        return aad;
    }

    private void writeFragmented(int contentType, byte[] data, TlsRecordSink sink) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        do {
            int chunkLen = Math.min(MAX_FRAGMENT, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + chunkLen);
            if (write == null) {
                writePlaintextRecord(contentType, chunk, out);
            } else {
                writeEncryptedRecord(contentType, chunk, out);
            }
            offset += chunkLen;
        } while (offset < data.length);
        byte[] wire = out.toByteArray();
        if (wire.length > 0) {
            sink.ciphertextReady(wire);
        }
    }

    private static void writePlaintextRecord(int contentType, byte[] payload, ByteArrayOutputStream out) {
        out.write(contentType);
        out.write(0x03);
        out.write(0x03);
        out.write((payload.length >> 8) & 0xff);
        out.write(payload.length & 0xff);
        out.write(payload, 0, payload.length);
    }

    private void writeEncryptedRecord(int contentType, byte[] payload, ByteArrayOutputStream out) {
        byte[] aad = additionalData(write.seq, contentType, payload.length);
        byte[] nonce = write.localNonce();
        byte[] sealed;
        try {
            sealed = write.sealAppendTag(nonce, aad, payload);
        } catch (GeneralSecurityException e) {
            // A freshly derived key sealing a well-formed plaintext never
            // fails; a real JCE provider never reaches this.
            throw new IllegalStateException("Record seal failed", e);
        }
        boolean hasExplicit = write.hasExplicitNonce();
        int recordLen = (hasExplicit ? 8 : 0) + sealed.length;
        out.write(contentType);
        out.write(0x03);
        out.write(0x03);
        out.write((recordLen >> 8) & 0xff);
        out.write(recordLen & 0xff);
        if (hasExplicit) {
            byte[] explicitNonce = write.seqBytes();
            out.write(explicitNonce, 0, explicitNonce.length);
        }
        out.write(sealed, 0, sealed.length);
        write.advance();
    }

    private void activateRead() {
        if (pendingRead != null) {
            read = Tls12DirectionalKeys.fromMaterial(stagedSuite, pendingRead);
            pendingRead = null;
        }
    }

    private void sendFatalAlert(AlertDescription alert, TlsRecordSink sink) {
        if (alertSent) {
            return;
        }
        alertSent = true;
        int code = (alert != null) ? alert.getCode() : AlertDescription.INTERNAL_ERROR.getCode();
        writeFragmented(CONTENT_ALERT, new byte[] { (byte) ALERT_LEVEL_FATAL, (byte) code }, sink);
    }

    private void fail(TlsRecordSink sink, AlertDescription alert, String message) {
        if (failed) {
            return;
        }
        failed = true;
        inbound.reset();
        sendFatalAlert(alert, sink);
        sink.protocolError(new TlsProtocolError(alert, message));
    }

    /** Bridges {@link Tls12HandshakeEngine}'s handshake-message events onto record framing. */
    private final class InnerSink implements Tls12EventSink {

        TlsRecordSink outer;

        @Override
        public void handshakeDataReady(byte[] data) {
            writeFragmented(CONTENT_HANDSHAKE, data, outer);
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
            writeFragmented(CONTENT_CHANGE_CIPHER_SPEC, new byte[] { 0x01 }, outer);
            if (pendingWrite != null) {
                write = Tls12DirectionalKeys.fromMaterial(stagedSuite, pendingWrite);
                pendingWrite = null;
            }
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
