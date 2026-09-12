/*
 * TlsRecordEngine.java
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
 * The TLS 1.3 record layer (RFC 8446 section 5) for TCP: wraps
 * {@link HandshakeEngine} (forced into {@link HandshakeMode#TCP_RECORD_LAYER})
 * with record framing and AEAD. Reactive like every other engine in this
 * package: {@link #feedCiphertext}/{@link #sendApplicationData} go in,
 * {@link TlsRecordSink} events come out. No record layer exists for QUIC
 * (RFC 9001 section 4 -- packet protection replaces it); this class is
 * TCP-only.
 *
 * <p>Application traffic keys install immediately when
 * {@link TlsEventSink#applicationSecretsReady} fires, with no staging
 * step: tracing {@link HandshakeEngine}'s own event order shows every
 * handshake-epoch message a role needs to send (including, for the
 * client, its own {@code Finished}, and -- when mutual TLS is configured
 * -- either role's own {@code Certificate}/{@code CertificateVerify}) is
 * always pushed via {@link TlsEventSink#handshakeDataReady} before the
 * corresponding {@code *SecretsReady} event fires, so nothing that needs
 * the old key is still pending once the epoch flips.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-5">RFC 8446 section 5</a>
 */
public final class TlsRecordEngine {

    private static final int CONTENT_CHANGE_CIPHER_SPEC = 20;
    private static final int CONTENT_ALERT = 21;
    private static final int CONTENT_HANDSHAKE = 22;
    private static final int CONTENT_APPLICATION_DATA = 23;

    private static final int ALERT_LEVEL_WARNING = 1;
    private static final int ALERT_LEVEL_FATAL = 2;
    private static final int ALERT_CLOSE_NOTIFY = 0;

    /** RFC 8446 section 5.1: plaintext fragments are capped at 2^14 bytes. */
    private static final int MAX_FRAGMENT = 16384;

    /** RFC 8446 section 5.2: ciphertext records may be up to 2^14 + 256 bytes. */
    private static final int MAX_CIPHERTEXT_RECORD = MAX_FRAGMENT + 256;

    private static final int AEAD_TAG_LENGTH = 16;

    private enum Epoch {
        PLAINTEXT,
        HANDSHAKE,
        APPLICATION
    }

    /**
     * A {@link ByteArrayOutputStream} exposing its backing array and
     * length directly, so records can be sliced off the front without
     * paying for {@link ByteArrayOutputStream#toByteArray()}'s
     * full-buffer copy on every access -- same pattern
     * {@code org.bluezoo.gumdrop.quic.tls.CryptoStreamBuffer} uses for
     * QUIC's CRYPTO stream, minus the out-of-order reassembly that
     * doesn't apply here: TCP bytes already arrive in order.
     */
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

    private final HandshakeEngine engine;
    private final HandshakeRole role;
    private final InnerSink innerSink = new InnerSink();
    private final GrowableBuffer inbound = new GrowableBuffer();

    private Epoch epoch = Epoch.PLAINTEXT;
    // Package-private, not private: TlsRecordEngineTest fast-forwards
    // DirectionalKeys.seq directly on these to exercise the
    // confidentiality-limit rotation without actually protecting 23
    // million records.
    DirectionalKeys write;
    DirectionalKeys read;

    // Set once we've asked the peer to rotate their write key because our
    // read-side confidentiality limit was reached; cleared when their
    // KeyUpdate actually arrives and installs a fresh read key. Guards
    // against re-requesting on every subsequent record while waiting.
    private boolean readUpdateRequested;

    // Set once a fatal alert has gone out (or a peer's own fatal/close
    // alert has been relayed without a reply) -- guards against sending a
    // second one, and against replying to a peer's alert with one of our
    // own (RFC 8446 section 6 has no alert-acknowledgment concept).
    private boolean alertSent;

    private boolean failed;

    /**
     * Creates a TCP record-layer engine, wrapping a {@link HandshakeEngine}.
     * {@code config}'s mode is forced to {@link HandshakeMode#TCP_RECORD_LAYER}.
     *
     * @param config this side's configuration
     */
    public TlsRecordEngine(HandshakeConfig config) {
        config.setMode(HandshakeMode.TCP_RECORD_LAYER);
        this.role = config.getRole();
        this.engine = new HandshakeEngine(config);
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
     * partial records. Buffers a trailing partial record for the next
     * call.
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
            // RFC 8446 section 5.5 / RFC 9325 section 4.4: our read key
            // has protected close to its AES-GCM confidentiality limit's
            // worth of records -- ask the peer to rotate theirs (which
            // also rotates our own write key as a side effect).
            if (!readUpdateRequested && read != null && read.overConfidentialityLimit()) {
                readUpdateRequested = true;
                requestKeyUpdate(sink, true);
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
        if (epoch != Epoch.APPLICATION) {
            sink.protocolError(new TlsProtocolError(AlertDescription.INTERNAL_ERROR,
                    "application data sent before handshake completed"));
            return;
        }
        writeFragmented(CONTENT_APPLICATION_DATA, plaintext, sink);
        // RFC 8446 section 5.5 / RFC 9325 section 4.4: retire our own
        // write key before it exceeds the AES-GCM confidentiality limit.
        // No peer round trip needed -- ratcheting our write secret is
        // immediate.
        if (write.overConfidentialityLimit()) {
            requestKeyUpdate(sink, false);
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
     * Rotates this connection's own application traffic key forward (RFC
     * 8446 section 4.6.3/7.2), optionally asking the peer to reciprocate.
     * Delegates straight to {@link HandshakeEngine#requestKeyUpdate}.
     *
     * @param sink where to push resulting events
     * @param requestPeerUpdate whether to also request the peer update
     *                          its own sending keys
     * @return true if a KeyUpdate was actually sent
     */
    public boolean requestKeyUpdate(TlsRecordSink sink, boolean requestPeerUpdate) {
        if (failed) {
            return false;
        }
        return engine.requestKeyUpdate(innerSink(sink), requestPeerUpdate);
    }

    /**
     * Returns the negotiated cipher suite, once known.
     *
     * @return the negotiated cipher suite, or null if not yet negotiated
     */
    public CipherSuite getNegotiatedCipherSuite() {
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
     * Returns the peer's certificate chain -- see
     * {@link HandshakeEngine#getPeerCertificateChain} for the full
     * per-role contract.
     *
     * @return the peer's certificate chain, leaf first, or null
     */
    public List<X509Certificate> getPeerCertificateChain() {
        return engine.getPeerCertificateChain();
    }

    /**
     * Returns whether this handshake resumed a previous session via PSK.
     *
     * @return true if this is a resumed handshake
     */
    public boolean isResumed() {
        return engine.isResumed();
    }

    private TlsEventSink innerSink(TlsRecordSink outer) {
        innerSink.outer = outer;
        return innerSink;
    }

    /**
     * Dispatches one already-decrypted {@code (inner content type,
     * payload)} pair. Returns false if the caller should stop processing
     * further buffered records this call (a fatal alert, or a downstream
     * failure, was already reported).
     */
    private boolean dispatchRecord(int contentType, byte[] payload, TlsRecordSink sink) {
        switch (contentType) {
            case CONTENT_CHANGE_CIPHER_SPEC:
                // Middlebox-compatibility passthrough (RFC 8446 appendix
                // D.4) -- tolerated on receipt; never sent, since this
                // engine (like hopf's own TCP record layer) doesn't
                // implement middlebox-compatibility mode.
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
                    // Relay the peer's own alert; don't send one back
                    // (RFC 8446 section 6 has no alert-acknowledgment
                    // concept, and the peer is already tearing down).
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
     * Pops one record off {@link #inbound}, decrypting it if the current
     * read epoch requires it. Returns null if no complete record is
     * available yet. The returned record's content type is the inner
     * content type (the last non-zero-padding byte of the decrypted
     * plaintext, RFC 8446 section 5.2) once past the plaintext epoch, not
     * the on-wire opaque type.
     *
     * @throws HandshakeFormatException if the record is malformed, in the
     *         wrong shape for the current epoch, or its AEAD tag does not verify
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
        byte[] header = Arrays.copyOfRange(buffered, 0, 5);
        byte[] body = Arrays.copyOfRange(buffered, 5, 5 + len);
        inbound.discard(5 + len);

        if (hdrType == CONTENT_CHANGE_CIPHER_SPEC) {
            return new Record(CONTENT_CHANGE_CIPHER_SPEC, new byte[0]);
        }

        if (epoch == Epoch.PLAINTEXT) {
            return new Record(hdrType, body);
        }
        if (hdrType != CONTENT_APPLICATION_DATA || read == null) {
            throw new HandshakeFormatException("unexpected outer record type for this epoch");
        }
        byte[] plain = read.openInPlace(read.nonce(), header, body);
        read.advance();
        if (plain == null) {
            throw new HandshakeFormatException("AEAD tag verification failed");
        }
        int end = plain.length;
        while (end > 0 && plain[end - 1] == 0) {
            end--;
        }
        if (end == 0) {
            throw new HandshakeFormatException("empty inner plaintext");
        }
        int innerType = plain[end - 1] & 0xff;
        byte[] payload = Arrays.copyOfRange(plain, 0, end - 1);
        return new Record(innerType, payload);
    }

    private void writeFragmented(int contentType, byte[] data, TlsRecordSink sink) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        do {
            int chunkLen = Math.min(MAX_FRAGMENT, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + chunkLen);
            if (epoch == Epoch.PLAINTEXT) {
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

    private void writeEncryptedRecord(int innerContentType, byte[] payload, ByteArrayOutputStream out) {
        byte[] plain = new byte[payload.length + 1];
        System.arraycopy(payload, 0, plain, 0, payload.length);
        plain[payload.length] = (byte) innerContentType;
        int cipherLength = plain.length + AEAD_TAG_LENGTH;
        byte[] header = {
            (byte) CONTENT_APPLICATION_DATA, 0x03, 0x03,
            (byte) ((cipherLength >> 8) & 0xff), (byte) (cipherLength & 0xff)
        };
        byte[] sealed;
        try {
            sealed = write.sealAppendTag(write.nonce(), header, plain);
        } catch (GeneralSecurityException e) {
            // A freshly derived key sealing a well-formed plaintext never
            // fails; a real JCE provider never reaches this.
            throw new IllegalStateException("Record seal failed", e);
        }
        write.advance();
        out.write(header, 0, header.length);
        out.write(sealed, 0, sealed.length);
    }

    /**
     * Sends a fatal alert for a violation this side detected (RFC 8446
     * section 6.2) -- at most once per connection (see {@link #alertSent}),
     * since after the first fatal alert the connection is already going down.
     */
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

    /** Bridges {@link HandshakeEngine}'s handshake-message events onto record framing. */
    private final class InnerSink implements TlsEventSink {

        TlsRecordSink outer;

        @Override
        public void handshakeDataReady(byte[] data) {
            writeFragmented(CONTENT_HANDSHAKE, data, outer);
        }

        @Override
        public void handshakeSecretsReady() {
            installDirectionalKeys(Epoch.HANDSHAKE);
        }

        @Override
        public void applicationSecretsReady() {
            installDirectionalKeys(Epoch.APPLICATION);
            outer.handshakeComplete();
        }

        @Override
        public void applicationTrafficSecretUpdated(KeyUpdateDirection direction, byte[] newSecret) {
            DirectionalKeys updated = DirectionalKeys.fromSecret(engine.getNegotiatedCipherSuite(), newSecret);
            if (direction == KeyUpdateDirection.WRITE) {
                write = updated;
            } else {
                read = updated;
                readUpdateRequested = false;
            }
        }

        @Override
        public void protocolError(TlsProtocolError error) {
            sendFatalAlert(error.getAlert(), outer);
            outer.protocolError(error);
        }

        @Override
        public void peerClosed() {
            outer.peerClosed();
        }

        private void installDirectionalKeys(Epoch newEpoch) {
            CipherSuite suite = engine.getNegotiatedCipherSuite();
            byte[] ownSecret;
            byte[] peerSecret;
            if (newEpoch == Epoch.HANDSHAKE) {
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
            write = DirectionalKeys.fromSecret(suite, ownSecret);
            read = DirectionalKeys.fromSecret(suite, peerSecret);
            epoch = newEpoch;
        }
    }

}
