/*
 * Tls12HandshakeEngine.java
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
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.crypto.KeyExchange;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.Prf;
import org.bluezoo.gumdrop.crypto.SignatureScheme;

/**
 * A TLS 1.2 handshake state machine (RFC 5246 section 7.3, RFC 4492/8422
 * ECDHE), driven reactively like {@link HandshakeEngine}: {@link #start}
 * and {@link #processMessage} take complete handshake messages and push
 * every outcome to a {@link Tls12EventSink}.
 *
 * <p>Scope: ECDHE key exchange only (secp256r1 only -- no static-RSA, no
 * other curves), AEAD cipher suites only (RFC 5289 GCM, RFC 7905
 * ChaCha20-Poly1305 -- CBC suites are permanently out of scope, not
 * deferred: MAC-then-encrypt CBC has a real, recurring timing-side-
 * channel history). Extended Master Secret (RFC 7627) and RFC 5746
 * secure renegotiation indication are mandatory on every handshake this
 * engine does; renegotiation itself is never offered or accepted. Client
 * certificate authentication (mTLS, {@link Tls12HandshakeConfig#getClientAuthPolicy})
 * and SNI-based server credential dispatch
 * ({@link Tls12HandshakeConfig#getServerCredentialsResolver}) are
 * supported, reusing the exact same types {@link HandshakeEngine} (TLS
 * 1.3) uses for both. ALPN (RFC 7301) is supported. Session resumption is
 * RFC 5077 stateless tickets (see {@link Tls12TicketPayload}), not RFC
 * 5246 section 7.3's session-ID server-side caching.
 *
 * <p>Deliberately independent of {@link HandshakeEngine} beyond the
 * shared crypto floor ({@link KeyExchange}, {@link SignatureScheme},
 * {@link CertificateVerifier}) -- see {@link Tls12HandshakeMessages}'s
 * module doc for why the wire layer itself isn't shared.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5246#section-7.3">RFC 5246 section 7.3</a>
 */
final class Tls12HandshakeEngine {

    private enum State {
        INITIAL,
        // client
        EXPECT_SERVER_HELLO,
        EXPECT_CERTIFICATE,
        EXPECT_SERVER_KEY_EXCHANGE,
        /** After ServerKeyExchange -- either CertificateRequest or ServerHelloDone may come next (RFC 5246 section 7.4.4/7.4.5). */
        EXPECT_SERVER_HELLO_DONE_OR_CERT_REQUEST,
        EXPECT_SERVER_HELLO_DONE,
        EXPECT_SERVER_FINISHED,
        EXPECT_SERVER_FINISHED_RESUMED,
        // server
        /** Server sent CertificateRequest -- waiting for the client's Certificate. */
        EXPECT_CLIENT_CERTIFICATE,
        EXPECT_CLIENT_KEY_EXCHANGE,
        /** Client's Certificate had entries -- waiting for CertificateVerify before Finished. */
        EXPECT_CLIENT_CERTIFICATE_VERIFY,
        EXPECT_CLIENT_FINISHED,
        EXPECT_CLIENT_FINISHED_RESUMED,
        COMPLETE,
        FAILED
    }

    private final Tls12HandshakeConfig config;
    private final SecureRandom secureRandom = new SecureRandom();

    private State state = State.INITIAL;
    private Transcript transcript;
    /**
     * Raw concatenated handshake message bytes seen so far, alongside
     * {@link #transcript}'s running digest -- RFC 5246 section 7.4.8's
     * {@code CertificateVerify} signs {@code Hash(handshake_messages)}
     * directly (the JCA {@link java.security.Signature} hashes this
     * internally), not a further-hashed wrapper the way TLS 1.3's
     * {@code CertificateVerify} does, so the exact raw bytes are needed
     * here rather than just the running digest.
     */
    private ByteArrayOutputStream rawTranscript = new ByteArrayOutputStream();

    private Tls12CipherSuite negotiatedSuite;
    private byte[] clientRandom;
    private byte[] serverRandom;
    private KeyExchange localEcdhe;
    private byte[] peerEcPoint;
    private byte[] masterSecret;
    /**
     * Whether Extended Master Secret (RFC 7627) was negotiated. Both
     * roles refuse the handshake before this would ever be read as false
     * at derivation time -- see {@link #onClientHello}/{@link #onServerHello}
     * -- so this is effectively always true by the time
     * {@link #deriveMasterSecret} reads it, but it stays the negotiated
     * value, not a hardcoded assumption.
     */
    private boolean useEms;
    private List<X509Certificate> peerCertificateChain;
    private String peerServerName;
    private String negotiatedAlpn;

    /** Client role: the session ID offered in our own ClientHello, so a matching echo in ServerHello signals resumption. */
    private byte[] sentSessionId = new byte[0];
    /** Client role: the cached ticket offered, pending confirmation via the session-ID echo above. */
    private Tls12SessionTicket pendingResumeTicket;
    /** Server role: whether to mint and send a NewSessionTicket at the end of the full handshake in progress. */
    private boolean shouldIssueTicket;
    /** Client role: whether the server echoed SessionTicket in its ServerHello (RFC 5077 section 3.2). */
    private boolean expectNewSessionTicket;
    /** Client role: the server sent CertificateRequest this handshake. */
    private boolean clientCertRequested;
    /** Server role: the client's Certificate (already processed) had at least one entry. */
    private boolean expectClientCertificateVerify;

    private boolean resumed;

    Tls12HandshakeEngine(Tls12HandshakeConfig config) {
        this.config = config;
    }

    /**
     * Starts the handshake. Only meaningful for the client role, which
     * builds and sends ClientHello; a no-op for the server role, which
     * simply waits for {@link #processMessage} to be called with one.
     *
     * @param sink where to push resulting events
     */
    void start(Tls12EventSink sink) {
        if (config.getRole() != HandshakeRole.CLIENT || state != State.INITIAL) {
            return;
        }
        byte[] random;
        if (config.getDtlsClientRandom() != null) {
            random = config.getDtlsClientRandom().clone();
        } else {
            random = new byte[32];
            secureRandom.nextBytes(random);
        }
        clientRandom = random;

        byte[] sessionId = new byte[0];
        byte[] ticketOffer = null;
        Tls12ClientTicketStore store = config.getClientTicketStore();
        if (store != null) {
            Tls12SessionTicket stored = (config.getServerName() != null) ? store.get(config.getServerName()) : null;
            if (stored != null) {
                byte[] sid = new byte[32];
                secureRandom.nextBytes(sid);
                sessionId = sid;
                ticketOffer = stored.getTicket();
                pendingResumeTicket = stored;
            } else {
                ticketOffer = new byte[0];
            }
        }
        sentSessionId = sessionId;

        List<SignatureScheme> signatureAlgorithms = offeredSignatureAlgorithms();

        Tls12HandshakeMessages.ClientHelloParams params = new Tls12HandshakeMessages.ClientHelloParams();
        params.random = clientRandom;
        params.sessionId = sessionId;
        params.cipherSuites = config.getCipherSuites();
        params.serverName = config.getServerName();
        params.signatureAlgorithms = signatureAlgorithms;
        params.applicationProtocols = config.getApplicationProtocols();
        params.sessionTicket = ticketOffer;
        params.dtlsTransport = config.isDtlsTransport();
        params.dtlsCookie = config.getDtlsCookie();

        byte[] clientHello = Tls12HandshakeMessages.buildClientHello(params);
        // No transcript exists yet (the negotiated PRF hash isn't known
        // until ServerHello) -- stash the raw bytes and add them in once
        // onServerHello creates it, mirroring HandshakeEngine's own
        // savedClientHelloBytes pattern.
        savedClientHelloBytes = clientHello;
        state = State.EXPECT_SERVER_HELLO;
        sink.handshakeDataReady(clientHello);
    }

    private byte[] savedClientHelloBytes;

    /**
     * Feeds one complete handshake message to the state machine.
     *
     * @param message the complete framed message
     * @param sink where to push resulting events
     */
    void processMessage(byte[] message, Tls12EventSink sink) {
        if (state == State.FAILED) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Handshake message received after failure");
            return;
        }
        if (message.length < 4) {
            fail(sink, AlertDescription.DECODE_ERROR, "Truncated handshake message");
            return;
        }
        try {
            if (config.getRole() == HandshakeRole.CLIENT) {
                processAsClient(message, sink);
            } else {
                processAsServer(message, sink);
            }
        } catch (HandshakeFormatException e) {
            fail(sink, AlertDescription.DECODE_ERROR, "Malformed handshake message: " + e.getMessage());
        } catch (GeneralSecurityException e) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "Cryptographic failure: " + e.getMessage());
        }
    }

    /**
     * Whether the handshake has completed successfully.
     *
     * @return true once complete
     */
    boolean isComplete() {
        return state == State.COMPLETE;
    }

    /**
     * Whether the handshake has failed.
     *
     * @return true if a protocol error has occurred
     */
    boolean isFailed() {
        return state == State.FAILED;
    }

    // ---- client role ----

    private void processAsClient(byte[] message, Tls12EventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        int type = message[0] & 0xff;
        switch (state) {
            case EXPECT_SERVER_HELLO:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_SERVER_HELLO) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected ServerHello");
                    return;
                }
                onServerHello(message, sink);
                break;
            case EXPECT_CERTIFICATE:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Certificate");
                    return;
                }
                onCertificate(message, sink);
                break;
            case EXPECT_SERVER_KEY_EXCHANGE:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_SERVER_KEY_EXCHANGE) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected ServerKeyExchange");
                    return;
                }
                onServerKeyExchange(message, sink);
                break;
            case EXPECT_SERVER_HELLO_DONE_OR_CERT_REQUEST:
                if (type == Tls12HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE_REQUEST) {
                    onCertificateRequest(message, sink);
                } else if (type == Tls12HandshakeMessages.HANDSHAKE_TYPE_SERVER_HELLO_DONE) {
                    onServerHelloDone(message, sink);
                } else {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected CertificateRequest or ServerHelloDone");
                }
                break;
            case EXPECT_SERVER_HELLO_DONE:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_SERVER_HELLO_DONE) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected ServerHelloDone");
                    return;
                }
                onServerHelloDone(message, sink);
                break;
            case EXPECT_SERVER_FINISHED:
                if (type == Tls12HandshakeMessages.HANDSHAKE_TYPE_NEW_SESSION_TICKET) {
                    onNewSessionTicket(message, sink);
                } else if (type == Tls12HandshakeMessages.HANDSHAKE_TYPE_FINISHED) {
                    onServerFinished(message, sink);
                } else {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected NewSessionTicket or Finished");
                }
                break;
            case EXPECT_SERVER_FINISHED_RESUMED:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_FINISHED) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Finished");
                    return;
                }
                onServerFinishedResumed(message, sink);
                break;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Unexpected message in state " + state);
        }
    }

    private void onServerHello(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        Tls12HandshakeMessages.ServerHello sh = Tls12HandshakeMessages.parseServerHello(message);
        if (!sh.extendedMasterSecret) {
            fail(sink, AlertDescription.INSUFFICIENT_SECURITY,
                    "Server did not negotiate mandatory Extended Master Secret (RFC 7627)");
            return;
        }
        useEms = true;
        if (!secureRenegotiationOk(sh.renegotiationInfo)) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE,
                    "Server did not confirm RFC 5746 secure renegotiation (missing or invalid renegotiation_info)");
            return;
        }
        negotiatedSuite = sh.cipherSuite;
        serverRandom = sh.random;
        negotiatedAlpn = sh.alpnProtocol;
        expectNewSessionTicket = sh.sessionTicketOffered;

        transcript = Transcript.create(negotiatedSuite.getPrfHashAlgorithm());
        addToTranscript(savedClientHelloBytes);
        addToTranscript(message);

        boolean resuming = sentSessionId.length > 0 && Arrays.equals(sh.sessionId, sentSessionId);
        if (resuming) {
            if (pendingResumeTicket == null) {
                fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Server echoed a resumption session id we never offered");
                return;
            }
            if (pendingResumeTicket.getCipherSuite() != sh.cipherSuite) {
                fail(sink, AlertDescription.ILLEGAL_PARAMETER,
                        "Server echoed session id for resumption but selected a different cipher suite");
                return;
            }
            resumed = true;
            masterSecret = pendingResumeTicket.getMasterSecret();
            DirectionalKeyMaterial[] km = computeKeyMaterial();
            sink.keysReady(negotiatedSuite, km[0], km[1]);
            state = State.EXPECT_SERVER_FINISHED_RESUMED;
            return;
        }
        pendingResumeTicket = null;
        state = State.EXPECT_CERTIFICATE;
    }

    private void onServerFinishedResumed(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        byte[] verifyData = Tls12HandshakeMessages.parseFinished(message);
        byte[] expected = finishedVerifyData(false);
        if (!MessageDigest.isEqual(expected, verifyData)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "Server Finished verify failed (resumed handshake)");
            return;
        }
        addToTranscript(message);

        sink.sendChangeCipherSpec();
        byte[] vd = finishedVerifyData(true);
        byte[] fin = Tls12HandshakeMessages.buildFinished(vd);
        emit(fin, sink);
        finish(sink);
    }

    private void onNewSessionTicket(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        if (!expectNewSessionTicket) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Unexpected NewSessionTicket (server never echoed SessionTicket support)");
            return;
        }
        Tls12HandshakeMessages.NewSessionTicket nst = Tls12HandshakeMessages.parseNewSessionTicket(message);
        Tls12ClientTicketStore store = config.getClientTicketStore();
        if (store != null && config.getServerName() != null) {
            long lifetimeSecs = (nst.lifetimeHintSeconds == 0) ? Tls12TicketPayload.TICKET_LIFETIME_SECS : nst.lifetimeHintSeconds;
            Tls12SessionTicket ticket = new Tls12SessionTicket(
                    nst.ticket, masterSecret, negotiatedSuite, System.currentTimeMillis(), lifetimeSecs);
            store.put(config.getServerName(), ticket);
        }
        addToTranscript(message);
    }

    private void onCertificate(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        List<byte[]> der = Tls12HandshakeMessages.parseCertificate(message);
        addToTranscript(message);
        if (der.isEmpty()) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, "Server presented an empty certificate chain");
            return;
        }
        List<X509Certificate> chain;
        try {
            chain = CertificateVerifier.parseChain(der);
        } catch (CertificateException e) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, "Malformed server certificate: " + e.getMessage());
            return;
        }
        String expectedHostname = config.isVerifyHostname() ? config.getServerName() : null;
        CertificateVerifier.Result result = CertificateVerifier.verifyChain(chain, config.getTrustManager(), expectedHostname);
        if (!result.isOk()) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, result.getError());
            return;
        }
        peerCertificateChain = chain;
        state = State.EXPECT_SERVER_KEY_EXCHANGE;
    }

    private void onServerKeyExchange(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        Tls12HandshakeMessages.ServerKeyExchange ske = Tls12HandshakeMessages.parseServerKeyExchange(message);
        if (peerCertificateChain == null || peerCertificateChain.isEmpty()) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "ServerKeyExchange before Certificate");
            return;
        }
        ByteArrayOutputStream signed = new ByteArrayOutputStream();
        signed.write(clientRandom, 0, clientRandom.length);
        signed.write(serverRandom, 0, serverRandom.length);
        signed.write(ske.signedParams, 0, ske.signedParams.length);
        if (!verifySignature(peerCertificateChain.get(0), ske.scheme, signed.toByteArray(), ske.signature)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "ServerKeyExchange signature invalid");
            return;
        }
        peerEcPoint = ske.ecPoint;
        addToTranscript(message);
        state = State.EXPECT_SERVER_HELLO_DONE_OR_CERT_REQUEST;
    }

    private void onCertificateRequest(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        Tls12HandshakeMessages.parseCertificateRequest(message);
        addToTranscript(message);
        clientCertRequested = true;
        state = State.EXPECT_SERVER_HELLO_DONE;
    }

    private void onServerHelloDone(byte[] message, Tls12EventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        addToTranscript(message);
        if (peerEcPoint == null) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "Missing server key share");
            return;
        }
        KeyExchange local;
        try {
            local = KeyExchange.generate(NamedGroup.SECP256R1);
        } catch (GeneralSecurityException e) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "Key generation failed: " + e.getMessage());
            return;
        }
        byte[] clientPoint = local.getShareBytes();
        byte[] preMaster;
        try {
            preMaster = local.agree(peerEcPoint);
        } catch (GeneralSecurityException e) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Key agreement failed: " + e.getMessage());
            return;
        }

        // Client's response to CertificateRequest -- Certificate goes
        // before ClientKeyExchange (RFC 5246 section 7.4.6);
        // CertificateVerify (if a non-empty chain was sent) goes after
        // ClientKeyExchange, once the master secret is known -- see below.
        ServerCredentials sentClientCreds = null;
        if (clientCertRequested) {
            clientCertRequested = false;
            ServerCredentials creds = config.getClientCredentials();
            List<byte[]> der = certificateDer(creds, sink);
            if (der == null) {
                return;
            }
            byte[] certMsg = Tls12HandshakeMessages.buildCertificate(der);
            emit(certMsg, sink);
            if (creds != null && !creds.getCertificateChain().isEmpty()) {
                sentClientCreds = creds;
            }
        }

        byte[] cke = Tls12HandshakeMessages.buildClientKeyExchange(clientPoint);
        emit(cke, sink);
        // RFC 7627 section 3: session_hash covers handshake_messages up to
        // and including ClientKeyExchange, so the master secret can only
        // be derived once the transcript includes it -- but before
        // CertificateVerify, which the session_hash excludes even though
        // it is sent after CKE in this same flight.
        deriveMasterSecret(preMaster);

        if (sentClientCreds != null) {
            SignatureScheme scheme = selectSignatureScheme(sentClientCreds.getPrivateKey());
            if (scheme == null) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "Unsupported client private key type");
                return;
            }
            byte[] signature;
            try {
                signature = scheme.sign(sentClientCreds.getPrivateKey(), rawTranscript.toByteArray());
            } catch (GeneralSecurityException e) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "Client signing failed: " + e.getMessage());
                return;
            }
            byte[] cv = Tls12HandshakeMessages.buildCertificateVerify(scheme, signature);
            emit(cv, sink);
        }

        DirectionalKeyMaterial[] km = computeKeyMaterial();
        sink.keysReady(negotiatedSuite, km[0], km[1]);
        sink.sendChangeCipherSpec();

        byte[] vd = finishedVerifyData(true);
        byte[] fin = Tls12HandshakeMessages.buildFinished(vd);
        emit(fin, sink);
        state = State.EXPECT_SERVER_FINISHED;
    }

    private void onServerFinished(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        byte[] verifyData = Tls12HandshakeMessages.parseFinished(message);
        byte[] expected = finishedVerifyData(false);
        if (!MessageDigest.isEqual(expected, verifyData)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "Server Finished verify failed");
            return;
        }
        addToTranscript(message);
        finish(sink);
    }

    // ---- server role ----

    private void processAsServer(byte[] message, Tls12EventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        int type = message[0] & 0xff;
        switch (state) {
            case INITIAL:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_CLIENT_HELLO) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected ClientHello");
                    return;
                }
                onClientHello(message, sink);
                break;
            case EXPECT_CLIENT_CERTIFICATE:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Certificate");
                    return;
                }
                onClientCertificate(message, sink);
                break;
            case EXPECT_CLIENT_KEY_EXCHANGE:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected ClientKeyExchange");
                    return;
                }
                onClientKeyExchange(message, sink);
                break;
            case EXPECT_CLIENT_CERTIFICATE_VERIFY:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE_VERIFY) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected CertificateVerify");
                    return;
                }
                onClientCertificateVerify(message, sink);
                break;
            case EXPECT_CLIENT_FINISHED:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_FINISHED) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Finished");
                    return;
                }
                onClientFinished(message, sink);
                break;
            case EXPECT_CLIENT_FINISHED_RESUMED:
                if (type != Tls12HandshakeMessages.HANDSHAKE_TYPE_FINISHED) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Finished");
                    return;
                }
                onClientFinishedResumed(message, sink);
                break;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Unexpected message in state " + state);
        }
    }

    private void onClientHello(byte[] message, Tls12EventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        Tls12HandshakeMessages.ClientHello ch =
                Tls12HandshakeMessages.parseClientHello(message, config.isDtlsTransport());

        ServerCredentials resolvedCredentials = (config.getServerCredentialsResolver() != null)
                ? config.getServerCredentialsResolver().resolve(ch.serverName)
                : config.getServerCredentials();
        if (resolvedCredentials == null || resolvedCredentials.getCertificateChain().isEmpty()) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "No server credentials available"
                    + (ch.serverName != null ? " for " + ch.serverName : ""));
            return;
        }
        String ourKeyType = resolvedCredentials.getPrivateKey().getAlgorithm();

        if (!ch.extendedMasterSecret) {
            fail(sink, AlertDescription.INSUFFICIENT_SECURITY,
                    "ClientHello missing mandatory Extended Master Secret extension (RFC 7627)");
            return;
        }
        useEms = true;
        // RFC 5746 section 3.6: a present-but-malformed extension always
        // aborts, regardless of the SCSV -- the SCSV is only an alternate
        // signal for a peer that omits the extension entirely.
        boolean renegotiationOk = (ch.renegotiationInfo != null)
                ? Arrays.equals(ch.renegotiationInfo, new byte[] { 0 })
                : ch.emptyRenegotiationScsvOffered;
        if (!renegotiationOk) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE,
                    "ClientHello missing or invalid RFC 5746 secure renegotiation signal");
            return;
        }
        if (ch.supportedVersions != null
                && !ch.supportedVersions.contains(Tls12HandshakeMessages.TLS_1_2_LEGACY_VERSION)) {
            fail(sink, AlertDescription.PROTOCOL_VERSION, "ClientHello supported_versions doesn't include TLS 1.2");
            return;
        }

        Tls12TicketPayload resumePayload = null;
        if (ch.sessionTicket != null && ch.sessionTicket.length > 0 && config.getTicketKeys() != null) {
            List<byte[]> candidates = config.getTicketKeys().candidateKeys();
            for (int i = 0; i < candidates.size() && resumePayload == null; i++) {
                Tls12TicketPayload payload = Tls12TicketPayload.open(candidates.get(i), ch.sessionTicket);
                if (payload != null && !payload.isExpired()
                        && ch.cipherSuites.contains(Tls12CipherSuite.fromCode(payload.cipherSuiteCode))) {
                    resumePayload = payload;
                }
            }
        }
        shouldIssueTicket = config.getTicketKeys() != null && ch.sessionTicket != null && resumePayload == null;
        clientRandom = ch.random;
        peerServerName = ch.serverName;

        if (resumePayload != null) {
            negotiatedSuite = Tls12CipherSuite.fromCode(resumePayload.cipherSuiteCode);
            resumed = true;
            masterSecret = resumePayload.masterSecret;

            byte[] random = new byte[32];
            secureRandom.nextBytes(random);
            serverRandom = random;

            transcript = Transcript.create(negotiatedSuite.getPrfHashAlgorithm());
            addToTranscript(message);

            // No new ticket is minted on a resumption (see this class's
            // doc comment), so no SessionTicket echo here.
            byte[] sh = Tls12HandshakeMessages.buildServerHello(
                    serverRandom, ch.sessionId, negotiatedSuite, false, true, null);
            emit(sh, sink);

            DirectionalKeyMaterial[] km = computeKeyMaterial();
            sink.keysReady(negotiatedSuite, km[0], km[1]);
            sink.sendChangeCipherSpec();
            byte[] vd = finishedVerifyData(false);
            byte[] fin = Tls12HandshakeMessages.buildFinished(vd);
            emit(fin, sink);
            state = State.EXPECT_CLIENT_FINISHED_RESUMED;
            return;
        }

        Tls12CipherSuite suite = null;
        List<Tls12CipherSuite> preference = config.getCipherSuites();
        for (int i = 0; i < preference.size() && suite == null; i++) {
            Tls12CipherSuite candidate = preference.get(i);
            if (ch.cipherSuites.contains(candidate) && candidate.getKeyType().equals(ourKeyType)) {
                suite = candidate;
            }
        }
        if (suite == null) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE, "No mutually supported cipher suite for this server's key type");
            return;
        }
        negotiatedSuite = suite;

        negotiatedAlpn = HandshakeEngine.selectFirst(config.getApplicationProtocols(), ch.alpnProtocols);
        if (!config.getApplicationProtocols().isEmpty() && negotiatedAlpn == null) {
            fail(sink, AlertDescription.NO_APPLICATION_PROTOCOL, "No mutually acceptable application protocol");
            return;
        }

        transcript = Transcript.create(negotiatedSuite.getPrfHashAlgorithm());
        addToTranscript(message);

        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        serverRandom = random;
        byte[] sh = Tls12HandshakeMessages.buildServerHello(
                serverRandom, new byte[0], suite, shouldIssueTicket, true, negotiatedAlpn);
        emit(sh, sink);

        List<byte[]> der = certificateDer(resolvedCredentials, sink);
        if (der == null) {
            return;
        }
        byte[] certMsg = Tls12HandshakeMessages.buildCertificate(der);
        emit(certMsg, sink);

        KeyExchange local;
        try {
            local = KeyExchange.generate(NamedGroup.SECP256R1);
        } catch (GeneralSecurityException e) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "Key generation failed: " + e.getMessage());
            return;
        }
        byte[] serverPoint = local.getShareBytes();
        byte[] signedParams = Tls12HandshakeMessages.serverEcdhParamsBytes(serverPoint);
        ByteArrayOutputStream signed = new ByteArrayOutputStream();
        signed.write(clientRandom, 0, clientRandom.length);
        signed.write(serverRandom, 0, serverRandom.length);
        signed.write(signedParams, 0, signedParams.length);
        SignatureScheme scheme = selectSignatureScheme(resolvedCredentials.getPrivateKey());
        if (scheme == null) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "Unsupported server signing key type");
            return;
        }
        byte[] signature;
        try {
            signature = scheme.sign(resolvedCredentials.getPrivateKey(), signed.toByteArray());
        } catch (GeneralSecurityException e) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "Server signing failed: " + e.getMessage());
            return;
        }
        byte[] ske = Tls12HandshakeMessages.buildServerKeyExchange(serverPoint, scheme, signature);
        emit(ske, sink);
        localEcdhe = local;

        if (config.getClientAuthPolicy() != ClientAuthPolicy.NONE) {
            byte[] cr = Tls12HandshakeMessages.buildCertificateRequest(offeredSignatureAlgorithms());
            emit(cr, sink);
        }

        byte[] shd = Tls12HandshakeMessages.buildServerHelloDone();
        emit(shd, sink);

        state = (config.getClientAuthPolicy() != ClientAuthPolicy.NONE)
                ? State.EXPECT_CLIENT_CERTIFICATE : State.EXPECT_CLIENT_KEY_EXCHANGE;
    }

    private void onClientCertificate(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        List<byte[]> der = Tls12HandshakeMessages.parseCertificate(message);
        addToTranscript(message);
        if (der.isEmpty()) {
            if (config.getClientAuthPolicy() == ClientAuthPolicy.REQUIRE) {
                fail(sink, AlertDescription.HANDSHAKE_FAILURE, "Client certificate required but none presented");
                return;
            }
            state = State.EXPECT_CLIENT_KEY_EXCHANGE;
            return;
        }
        List<X509Certificate> chain;
        try {
            chain = CertificateVerifier.parseChain(der);
        } catch (CertificateException e) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, "Malformed client certificate: " + e.getMessage());
            return;
        }
        CertificateVerifier.Result result = CertificateVerifier.verifyChain(chain, config.getClientTrustManager(), null);
        if (!result.isOk() && config.getClientAuthPolicy() == ClientAuthPolicy.REQUIRE) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, result.getError());
            return;
        }
        peerCertificateChain = chain;
        expectClientCertificateVerify = true;
        state = State.EXPECT_CLIENT_KEY_EXCHANGE;
    }

    private void onClientKeyExchange(byte[] message, Tls12EventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        byte[] clientPoint = Tls12HandshakeMessages.parseClientKeyExchange(message);
        addToTranscript(message);
        if (localEcdhe == null) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "Missing server key share");
            return;
        }
        byte[] preMaster;
        try {
            preMaster = localEcdhe.agree(clientPoint);
        } catch (GeneralSecurityException e) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Key agreement failed: " + e.getMessage());
            return;
        }
        localEcdhe = null;
        deriveMasterSecret(preMaster);
        DirectionalKeyMaterial[] km = computeKeyMaterial();
        sink.keysReady(negotiatedSuite, km[0], km[1]);
        state = expectClientCertificateVerify ? State.EXPECT_CLIENT_CERTIFICATE_VERIFY : State.EXPECT_CLIENT_FINISHED;
    }

    private void onClientCertificateVerify(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        Tls12HandshakeMessages.CertificateVerify cv = Tls12HandshakeMessages.parseCertificateVerify(message);
        if (peerCertificateChain == null || peerCertificateChain.isEmpty()) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "CertificateVerify without client certificate");
            return;
        }
        // Signs the transcript through ClientKeyExchange -- this message
        // is not yet added to it below.
        if (!verifySignature(peerCertificateChain.get(0), cv.scheme, rawTranscript.toByteArray(), cv.signature)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "Client CertificateVerify signature invalid");
            return;
        }
        addToTranscript(message);
        expectClientCertificateVerify = false;
        state = State.EXPECT_CLIENT_FINISHED;
    }

    private void onClientFinished(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        byte[] verifyData = Tls12HandshakeMessages.parseFinished(message);
        byte[] expected = finishedVerifyData(true);
        if (!MessageDigest.isEqual(expected, verifyData)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "Client Finished verify failed");
            return;
        }
        addToTranscript(message);

        if (shouldIssueTicket && config.getTicketKeys() != null) {
            Tls12TicketPayload payload = new Tls12TicketPayload(
                    masterSecret, negotiatedSuite.getCode(), System.currentTimeMillis(), Tls12TicketPayload.TICKET_LIFETIME_SECS);
            byte[] ticket = payload.seal(config.getTicketKeys().getCurrentKey());
            byte[] nst = Tls12HandshakeMessages.buildNewSessionTicket(Tls12TicketPayload.TICKET_LIFETIME_SECS, ticket);
            emit(nst, sink);
        }

        sink.sendChangeCipherSpec();
        byte[] vd = finishedVerifyData(false);
        byte[] fin = Tls12HandshakeMessages.buildFinished(vd);
        emit(fin, sink);
        finish(sink);
    }

    private void onClientFinishedResumed(byte[] message, Tls12EventSink sink) throws HandshakeFormatException {
        byte[] verifyData = Tls12HandshakeMessages.parseFinished(message);
        byte[] expected = finishedVerifyData(true);
        if (!MessageDigest.isEqual(expected, verifyData)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "Client Finished verify failed (resumed handshake)");
            return;
        }
        addToTranscript(message);
        finish(sink);
    }

    // ---- shared ----

    /**
     * RFC 5746 section 3.5 (client) / section 3.6 (server): on an initial
     * handshake, {@code renegotiation_info} -- if present at all -- MUST
     * be exactly the empty {@code renegotiated_connection<0..255>} vector,
     * a single zero length-prefix byte. There is no prior handshake for a
     * real value to reference on an initial one.
     */
    private static boolean secureRenegotiationOk(byte[] info) {
        return info != null && Arrays.equals(info, new byte[] { 0 });
    }

    private void deriveMasterSecret(byte[] preMaster) {
        Prf prf = Prf.forDigest(negotiatedSuite.getPrfHashAlgorithm());
        String label;
        byte[] seed;
        if (useEms) {
            label = "extended master secret";
            seed = transcript.hash();
        } else {
            label = "master secret";
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            s.write(clientRandom, 0, clientRandom.length);
            s.write(serverRandom, 0, serverRandom.length);
            seed = s.toByteArray();
        }
        masterSecret = prf.compute(preMaster, label, seed, 48);
    }

    /** Key block (RFC 5246 section 6.3): client_write_key, server_write_key, client_write_IV, server_write_IV -- no MAC keys, AEAD only. */
    private DirectionalKeyMaterial[] computeKeyMaterial() {
        Prf prf = Prf.forDigest(negotiatedSuite.getPrfHashAlgorithm());
        int keyLen = negotiatedSuite.getAeadKeyLength();
        int ivLen = negotiatedSuite.getFixedIvLength();
        int total = 2 * keyLen + 2 * ivLen;
        ByteArrayOutputStream seed = new ByteArrayOutputStream();
        seed.write(serverRandom, 0, serverRandom.length);
        seed.write(clientRandom, 0, clientRandom.length);
        byte[] block = prf.compute(masterSecret, "key expansion", seed.toByteArray(), total);

        int i = 0;
        byte[] clientKey = Arrays.copyOfRange(block, i, i += keyLen);
        byte[] serverKey = Arrays.copyOfRange(block, i, i += keyLen);
        byte[] clientIv = Arrays.copyOfRange(block, i, i += ivLen);
        byte[] serverIv = Arrays.copyOfRange(block, i, i += ivLen);
        return new DirectionalKeyMaterial[] {
            new DirectionalKeyMaterial(clientKey, clientIv),
            new DirectionalKeyMaterial(serverKey, serverIv)
        };
    }

    /**
     * {@code Finished.verify_data} (RFC 5246 section 7.4.9) -- {@code forClient}
     * selects the {@code "client finished"}/{@code "server finished"}
     * label, independent of this engine's own role (the client engine
     * needs both: its own to send, the server's to verify -- and vice versa).
     */
    private byte[] finishedVerifyData(boolean forClient) {
        Prf prf = Prf.forDigest(negotiatedSuite.getPrfHashAlgorithm());
        String label = forClient ? "client finished" : "server finished";
        return prf.compute(masterSecret, label, transcript.hash(), 12);
    }

    private void emit(byte[] wire, Tls12EventSink sink) {
        addToTranscript(wire);
        sink.handshakeDataReady(wire);
    }

    private void addToTranscript(byte[] wire) {
        transcript.update(wire);
        rawTranscript.write(wire, 0, wire.length);
    }

    private List<byte[]> certificateDer(ServerCredentials creds, Tls12EventSink sink) {
        if (creds == null) {
            return Collections.emptyList();
        }
        List<X509Certificate> chain = creds.getCertificateChain();
        List<byte[]> der = new ArrayList<byte[]>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            try {
                der.add(chain.get(i).getEncoded());
            } catch (CertificateEncodingException e) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "Could not encode certificate: " + e.getMessage());
                return null;
            }
        }
        return der;
    }

    private boolean verifySignature(X509Certificate leaf, SignatureScheme scheme, byte[] message, byte[] signature) {
        try {
            return scheme.verify(leaf.getPublicKey(), message, signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Selects this engine's own signing scheme from a private key's type
     * -- mirrors {@link HandshakeEngine}'s own detection pattern
     * (algorithm name plus EC field size), but returns TLS 1.2's PKCS#1
     * v1.5 RSA scheme rather than TLS 1.3's RSA-PSS, matching hopf's own
     * offered list (this engine never offers or accepts RSA-PSS for its
     * own signatures, though {@link SignatureScheme#RSA_PSS_RSAE_SHA256}
     * remains a legal codepoint to parse if a peer happens to send one --
     * see {@link Tls12HandshakeMessages}). No Ed25519 branch: RFC 5246 has
     * no {@code SignatureAndHashAlgorithm} codepoint for it, and moot
     * regardless since no cipher suite here maps to an Ed25519 key type.
     *
     * @param key the private key to select a scheme for
     * @return the scheme, or null if the key type is unsupported
     */
    private static SignatureScheme selectSignatureScheme(PrivateKey key) {
        String algorithm = key.getAlgorithm();
        if ("RSA".equals(algorithm)) {
            return SignatureScheme.RSA_PKCS1_SHA256;
        }
        if ("EC".equals(algorithm) && key instanceof ECKey) {
            int fieldBits = ((ECKey) key).getParams().getCurve().getField().getFieldSize();
            return (fieldBits > 256) ? SignatureScheme.ECDSA_SECP384R1_SHA384 : SignatureScheme.ECDSA_SECP256R1_SHA256;
        }
        return null;
    }

    private static List<SignatureScheme> offeredSignatureAlgorithms() {
        List<SignatureScheme> schemes = new ArrayList<SignatureScheme>();
        schemes.add(SignatureScheme.ECDSA_SECP256R1_SHA256);
        schemes.add(SignatureScheme.RSA_PKCS1_SHA256);
        schemes.add(SignatureScheme.ECDSA_SECP384R1_SHA384);
        schemes.add(SignatureScheme.RSA_PKCS1_SHA384);
        schemes.add(SignatureScheme.RSA_PSS_RSAE_SHA256);
        return schemes;
    }

    private void finish(Tls12EventSink sink) {
        if (state == State.COMPLETE) {
            return;
        }
        state = State.COMPLETE;
        sink.handshakeComplete();
    }

    private void fail(Tls12EventSink sink, AlertDescription alert, String message) {
        if (state != State.FAILED) {
            state = State.FAILED;
            sink.protocolError(new TlsProtocolError(alert, message));
        }
    }

    // ---- getters for the record layer / SecurityInfo ----

    Tls12CipherSuite getNegotiatedCipherSuite() {
        return negotiatedSuite;
    }

    String getNegotiatedApplicationProtocol() {
        return negotiatedAlpn;
    }

    List<X509Certificate> getPeerCertificateChain() {
        return peerCertificateChain;
    }

    boolean isResumed() {
        return resumed;
    }

    byte[] getClientRandom() {
        return clientRandom;
    }

    HandshakeRole getRole() {
        return config.getRole();
    }

    String getPeerServerName() {
        return peerServerName;
    }

}
