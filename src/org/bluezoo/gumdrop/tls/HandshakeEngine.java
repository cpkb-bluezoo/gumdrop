/*
 * HandshakeEngine.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.crypto.KeyExchange;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;

/**
 * A TLS 1.3 handshake state machine (RFC 8446 section 4), driven
 * reactively: {@link #start} and {@link #processMessage} take complete
 * handshake messages and push every outcome -- bytes to send, secrets
 * becoming available, failures -- to a {@link TlsEventSink}, with no
 * meaningful return value of their own. There is no
 * "call a method and block until the handshake finishes" entry point.
 *
 * <p>Scope (see the class's own package documentation for the fuller
 * picture): a full handshake, HelloRetryRequest, session resumption/PSK/
 * 0-RTT, post-handshake NewSessionTicket, and client certificate
 * authentication (mTLS, {@link HandshakeConfig#getClientAuthPolicy}),
 * over either QUIC's CRYPTO stream framing or ({@link HandshakeMode#TCP_RECORD_LAYER})
 * a TLS record layer. {@code KeyUpdate} is real only in
 * {@link HandshakeMode#TCP_RECORD_LAYER} mode -- see
 * {@link #requestKeyUpdate} -- since RFC 9001 section 4.6 forbids it over
 * QUIC entirely. No TLS 1.2, no DTLS.
 *
 * <p>A client offers a key share for <em>every</em> configured group
 * (not just its top preference) specifically so that a full handshake
 * against another gumdrop peer never needs {@code HelloRetryRequest} --
 * this engine's own HRR support exists for interop with a peer whose
 * preferred group this client didn't happen to list, not because
 * gumdrop-to-gumdrop traffic needs it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4">RFC 8446 section 4</a>
 */
public final class HandshakeEngine {

    private enum State {
        INITIAL,
        WAIT_SERVER_HELLO,
        WAIT_ENCRYPTED_EXTENSIONS,
        WAIT_CERTIFICATE,
        WAIT_CERTIFICATE_VERIFY,
        WAIT_SERVER_FINISHED,
        WAIT_CLIENT_CERTIFICATE,
        WAIT_CLIENT_CERTIFICATE_VERIFY,
        WAIT_CLIENT_FINISHED,
        COMPLETE,
        FAILED
    }

    private final HandshakeConfig config;
    private final SecureRandom secureRandom = new SecureRandom();

    private State state = State.INITIAL;
    private Transcript transcript;
    private CipherSuite negotiatedSuite;
    private KeySchedule keySchedule;
    private String negotiatedAlpn;
    private byte[] hashThroughServerFinished;
    private List<X509Certificate> peerCertificateChain;
    private boolean resumed;
    private byte[] resumptionMasterSecret;

    // Client-only state.
    private byte[] savedClientHelloBytes;
    private Map<NamedGroup, KeyExchange> clientKeyExchanges;
    private byte[] clientHelloRandom;
    private boolean clientRetried;
    private byte[] clientRetryCookie;
    private byte[] clientCertRequestContext;

    // Server-only state.
    private NamedGroup serverRetryRequestedGroup;
    private boolean earlyDataAccepted;

    // Set by whichever side sends/receives a HelloRetryRequest, to check
    // the eventual real ServerHello (client) or followup ClientHello
    // (server) did not change cipher suite across the retry -- an engine
    // instance only ever runs one role, so one field safely serves both.
    private CipherSuite retryCipherSuite;

    /**
     * Creates a handshake engine.
     *
     * @param config this side's configuration
     */
    public HandshakeEngine(HandshakeConfig config) {
        this.config = config;
    }

    /**
     * Starts the handshake. Only meaningful for the client role, which
     * builds and sends ClientHello (offering a PSK and, if configured, requesting
     * 0-RTT, when {@link HandshakeConfig#getSessionTicket} is set); a
     * no-op for the server role, which simply waits for
     * {@link #processMessage} to be called with one.
     *
     * @param sink where to push resulting events
     */
    public void start(TlsEventSink sink) {
        if (config.getRole() != HandshakeRole.CLIENT || state != State.INITIAL) {
            return;
        }
        sendClientHello(null, sink);
    }

    /**
     * Builds and sends a ClientHello -- the initial one ({@code retryGroup}
     * null) or a followup after a HelloRetryRequest ({@code retryGroup}
     * the group it requested). The initial case generates fresh key
     * shares for every configured group and a fresh random; the followup
     * case reuses both (RFC 8446 section 4.1.4: the same
     * {@code client_hello_random}, and only the retry-requested group's
     * already-generated share, narrowed from the initial offer of every
     * group), never offers early data (forbidden after a retry, RFC 8446
     * section 4.1.2), and echoes back any cookie the retry carried.
     *
     * @param retryGroup the HelloRetryRequest-requested group, or null
     *                   for the initial ClientHello
     * @param sink where to push resulting events
     */
    private void sendClientHello(NamedGroup retryGroup, TlsEventSink sink) {
        boolean isRetry = (retryGroup != null);
        Map<NamedGroup, byte[]> shares = new LinkedHashMap<NamedGroup, byte[]>();
        if (isRetry) {
            shares.put(retryGroup, clientKeyExchanges.get(retryGroup).getShareBytes());
        } else {
            clientKeyExchanges = new LinkedHashMap<NamedGroup, KeyExchange>();
            try {
                List<NamedGroup> groups = config.getNamedGroups();
                for (int i = 0; i < groups.size(); i++) {
                    NamedGroup group = groups.get(i);
                    KeyExchange kx = KeyExchange.generate(group);
                    clientKeyExchanges.put(group, kx);
                    shares.put(group, kx.getShareBytes());
                }
            } catch (GeneralSecurityException e) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "Could not generate key shares: " + e.getMessage());
                return;
            }
            byte[] random = new byte[32];
            secureRandom.nextBytes(random);
            clientHelloRandom = random;
        }

        List<SignatureScheme> signatureAlgorithms = new ArrayList<SignatureScheme>();
        for (SignatureScheme scheme : SignatureScheme.values()) {
            signatureAlgorithms.add(scheme);
        }

        HandshakeMessages.ClientHelloParams params = new HandshakeMessages.ClientHelloParams();
        params.random = clientHelloRandom;
        params.cipherSuites = config.getCipherSuites();
        params.groups = config.getNamedGroups();
        params.keyShares = shares;
        params.signatureAlgorithms = signatureAlgorithms;
        params.applicationProtocols = config.getApplicationProtocols();
        params.serverName = config.getServerName();
        params.quicTransportParameters = config.getLocalTransportParameters();
        params.cookie = isRetry ? clientRetryCookie : null;

        SessionTicket ticket = config.getSessionTicket();
        byte[] clientHello;
        boolean wantEarly = false;
        if (ticket != null) {
            params.pskIdentity = ticket.getIdentity();
            params.obfuscatedTicketAge = ticket.obfuscatedTicketAge();
            wantEarly = !isRetry && config.isEnableEarlyData() && ticket.getMaxEarlyDataSize() > 0;
            params.earlyDataRequested = wantEarly;

            KeySchedule pskSchedule = new KeySchedule(ticket.getCipherSuite());
            byte[] truncated = HandshakeMessages.buildClientHelloTruncatedForBinder(params);
            byte[] truncatedHash = truncatedClientHelloHash(ticket.getCipherSuite(), truncated);
            byte[] binder = pskSchedule.computePskBinder(ticket.getPsk(), truncatedHash);
            clientHello = HandshakeMessages.buildClientHelloWithBinder(params, binder);

            if (wantEarly) {
                byte[] clientHelloHash = truncatedClientHelloHash(ticket.getCipherSuite(), clientHello);
                byte[] earlyTrafficSecret = pskSchedule.deriveEarlyTrafficSecret(ticket.getPsk(), clientHelloHash);
                sink.quicEarlyKeysReady(ticket.getCipherSuite(), earlyTrafficSecret);
            }
        } else {
            clientHello = HandshakeMessages.buildClientHelloWithBinder(params, null);
        }

        savedClientHelloBytes = clientHello;
        state = State.WAIT_SERVER_HELLO;
        sink.handshakeDataReady(clientHello);
    }

    /**
     * The transcript hash of {@code bytes} as if appended next -- either
     * fresh (the initial ClientHello, before any transcript exists) or
     * continuing a running transcript already holding a
     * HelloRetryRequest (see {@link Transcript#hashWith}).
     */
    private byte[] truncatedClientHelloHash(CipherSuite suite, byte[] bytes) {
        if (transcript != null) {
            return transcript.hashWith(bytes);
        }
        Transcript t = Transcript.create(suite);
        t.update(bytes);
        return t.hash();
    }

    /**
     * Feeds one complete handshake message to the state machine.
     *
     * @param message the complete framed message (RFC 8446 section 4
     *                header included), as produced by e.g.
     *                {@code CryptoStreamBuffer.receiveAndExtractMessages}
     * @param sink where to push resulting events
     */
    public void processMessage(byte[] message, TlsEventSink sink) {
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

    // ---- client role ----

    private void processAsClient(byte[] message, TlsEventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        int type = message[0] & 0xff;
        switch (state) {
            case WAIT_SERVER_HELLO:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_SERVER_HELLO) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected ServerHello");
                    return;
                }
                onServerHello(message, sink);
                break;
            case WAIT_ENCRYPTED_EXTENSIONS:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_ENCRYPTED_EXTENSIONS) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected EncryptedExtensions");
                    return;
                }
                onEncryptedExtensions(message, sink);
                break;
            case WAIT_CERTIFICATE:
                // RFC 8446 section 4.3.2: an optional CertificateRequest
                // may precede the server's own Certificate -- a one
                // -message lookahead in this same state, not a separate one.
                if (type == HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE_REQUEST) {
                    onCertificateRequest(message, sink);
                } else if (type == HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE) {
                    onCertificate(message, sink);
                } else {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected CertificateRequest or Certificate");
                }
                break;
            case WAIT_CERTIFICATE_VERIFY:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE_VERIFY) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected CertificateVerify");
                    return;
                }
                onCertificateVerify(message, sink, true);
                break;
            case WAIT_SERVER_FINISHED:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_FINISHED) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Finished");
                    return;
                }
                onServerFinishedAsClient(message, sink);
                break;
            case COMPLETE:
                onPostHandshakeMessageAsClient(type, message, sink);
                break;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Unexpected message in state " + state);
        }
    }

    private void onServerHello(byte[] message, TlsEventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        if (HandshakeMessages.isHelloRetryRequest(message)) {
            onHelloRetryRequest(message, sink);
            return;
        }
        HandshakeMessages.ServerHello sh = HandshakeMessages.parseServerHello(message);
        if (!sh.selectedTls13 || sh.cipherSuite == null || !config.getCipherSuites().contains(sh.cipherSuite)) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE, "Server selected an unacceptable protocol version or cipher suite");
            return;
        }
        if (clientRetried && !sh.cipherSuite.equals(retryCipherSuite)) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Cipher suite changed across HelloRetryRequest");
            return;
        }
        KeyExchange kx = clientKeyExchanges.get(sh.keyShareGroup);
        if (kx == null) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE, "Server selected a group we did not offer a key share for");
            return;
        }
        if (sh.pskSelected && config.getSessionTicket() == null) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Server selected a PSK we did not offer");
            return;
        }
        resumed = sh.pskSelected;

        negotiatedSuite = sh.cipherSuite;
        if (transcript == null) {
            transcript = Transcript.create(negotiatedSuite);
            transcript.update(savedClientHelloBytes);
        } else if (clientRetried) {
            transcript.update(savedClientHelloBytes);
        }
        transcript.update(message);

        byte[] sharedSecret = kx.agree(sh.keyShareData);
        keySchedule = new KeySchedule(negotiatedSuite);
        byte[] psk = resumed ? config.getSessionTicket().getPsk() : null;
        keySchedule.deriveEarlySecret(psk);
        keySchedule.deriveHandshakeSecret(sharedSecret, transcript.hash());
        state = State.WAIT_ENCRYPTED_EXTENSIONS;
        sink.handshakeSecretsReady();
    }

    private void onHelloRetryRequest(byte[] message, TlsEventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        if (clientRetried) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Server sent a second HelloRetryRequest");
            return;
        }
        HandshakeMessages.HelloRetryRequest hrr = HandshakeMessages.parseHelloRetryRequest(message);
        if (hrr.cipherSuite == null || !config.getCipherSuites().contains(hrr.cipherSuite)) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE, "HelloRetryRequest selected an unacceptable cipher suite");
            return;
        }
        if (hrr.selectedGroup == null || !clientKeyExchanges.containsKey(hrr.selectedGroup)) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "HelloRetryRequest requested a group we don't offer");
            return;
        }

        Transcript t = Transcript.create(hrr.cipherSuite);
        t.update(savedClientHelloBytes);
        byte[] ch1Hash = t.hash();
        transcript = t;
        transcript.retry(ch1Hash);
        transcript.update(message);

        clientRetried = true;
        retryCipherSuite = hrr.cipherSuite;
        clientRetryCookie = hrr.cookie;

        sendClientHello(hrr.selectedGroup, sink);
    }

    private void onEncryptedExtensions(byte[] message, TlsEventSink sink) throws HandshakeFormatException {
        HandshakeMessages.EncryptedExtensions ee = HandshakeMessages.parseEncryptedExtensions(message);
        transcript.update(message);
        negotiatedAlpn = ee.selectedAlpn;
        if (ee.quicTransportParameters != null) {
            sink.peerTransportParameters(ee.quicTransportParameters);
        }
        sink.earlyDataAccepted(ee.earlyDataAccepted);
        state = resumed ? State.WAIT_SERVER_FINISHED : State.WAIT_CERTIFICATE;
    }

    /**
     * Client role: the server requested client certificate authentication
     * (RFC 8446 section 4.3.2). Captures the context to echo back in the
     * client's own {@code Certificate} response, sent later, right before
     * the client's {@code Finished} -- see {@link #onServerFinishedAsClient}.
     * Stays in {@code WAIT_CERTIFICATE}: the server's own {@code Certificate}
     * is still to come.
     */
    private void onCertificateRequest(byte[] message, TlsEventSink sink) throws HandshakeFormatException {
        clientCertRequestContext = HandshakeMessages.parseCertificateRequest(message);
        transcript.update(message);
    }

    private void onCertificate(byte[] message, TlsEventSink sink) throws HandshakeFormatException {
        List<byte[]> der = HandshakeMessages.parseCertificate(message);
        transcript.update(message);
        if (der.isEmpty()) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, "Server presented an empty certificate chain");
            return;
        }
        try {
            peerCertificateChain = CertificateVerifier.parseChain(der);
        } catch (CertificateException e) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, "Malformed server certificate: " + e.getMessage());
            return;
        }
        String expectedHostname = config.isVerifyHostname() ? config.getServerName() : null;
        CertificateVerifier.Result result = CertificateVerifier.verifyChain(peerCertificateChain,
                config.getTrustManager(), expectedHostname);
        if (!result.isOk()) {
            fail(sink, AlertDescription.BAD_CERTIFICATE, result.getError());
            return;
        }
        state = State.WAIT_CERTIFICATE_VERIFY;
    }

    private void onServerFinishedAsClient(byte[] message, TlsEventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        byte[] hashBeforeFinished = transcript.hash();
        byte[] verifyData = HandshakeMessages.parseFinished(message);
        byte[] expected = keySchedule.computeFinishedVerifyData(
                keySchedule.getServerHandshakeTrafficSecret(), hashBeforeFinished);
        if (!MessageDigest.isEqual(expected, verifyData)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "Server Finished verify-data mismatch");
            return;
        }
        transcript.update(message);
        hashThroughServerFinished = transcript.hash();
        keySchedule.deriveMasterSecret();
        keySchedule.deriveApplicationTrafficSecrets(hashThroughServerFinished);

        // RFC 8446 section 4.3.2: respond to a requested client
        // certificate before our own Finished, so its verify-data covers
        // these messages too.
        if (clientCertRequestContext != null) {
            if (!sendClientCertificateResponse(sink)) {
                return;
            }
        }

        byte[] clientVerifyData = keySchedule.computeFinishedVerifyData(
                keySchedule.getClientHandshakeTrafficSecret(), transcript.hash());
        byte[] clientFinished = HandshakeMessages.buildFinished(clientVerifyData);
        transcript.update(clientFinished);
        resumptionMasterSecret = keySchedule.deriveResumptionMasterSecret(transcript.hash());
        state = State.COMPLETE;
        sink.handshakeDataReady(clientFinished);
        sink.applicationSecretsReady();
    }

    /**
     * Client role: sends {@code Certificate} (our chain, or empty if
     * {@link HandshakeConfig#getClientCredentials} is unset -- RFC 8446
     * section 4.4.2 permits this) in response to a requested
     * {@code CertificateRequest}, and {@code CertificateVerify} when a
     * non-empty chain was actually sent.
     *
     * @return false if a failure was reported (caller must return without
     *         proceeding to the client's own Finished)
     */
    private boolean sendClientCertificateResponse(TlsEventSink sink) throws GeneralSecurityException {
        ServerCredentials creds = config.getClientCredentials();
        List<byte[]> der;
        if (creds != null) {
            List<X509Certificate> chain = creds.getCertificateChain();
            der = new ArrayList<byte[]>(chain.size());
            for (int i = 0; i < chain.size(); i++) {
                try {
                    der.add(chain.get(i).getEncoded());
                } catch (CertificateEncodingException e) {
                    fail(sink, AlertDescription.INTERNAL_ERROR, "Could not encode client certificate: " + e.getMessage());
                    return false;
                }
            }
        } else {
            der = Collections.emptyList();
        }
        byte[] clientCertificate = HandshakeMessages.buildCertificate(clientCertRequestContext, der);
        transcript.update(clientCertificate);
        sink.handshakeDataReady(clientCertificate);

        if (!der.isEmpty()) {
            SignatureScheme scheme = selectSignatureScheme(creds.getPrivateKey());
            if (scheme == null) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "Unsupported client private key type");
                return false;
            }
            byte[] signedContent = HandshakeMessages.certificateVerifySignedContent(false, transcript.hash());
            byte[] signature = scheme.sign(creds.getPrivateKey(), signedContent);
            byte[] clientCertificateVerify = HandshakeMessages.buildCertificateVerify(scheme, signature);
            transcript.update(clientCertificateVerify);
            sink.handshakeDataReady(clientCertificateVerify);
        }
        return true;
    }

    private void onPostHandshakeMessageAsClient(int type, byte[] message, TlsEventSink sink)
            throws HandshakeFormatException {
        if (type == HandshakeMessages.HANDSHAKE_TYPE_NEW_SESSION_TICKET) {
            HandshakeMessages.NewSessionTicket nst = HandshakeMessages.parseNewSessionTicket(message);
            byte[] psk = keySchedule.deriveResumptionPsk(resumptionMasterSecret, nst.nonce);
            SessionTicket ticket = new SessionTicket(nst.ticket, nst.lifetimeSeconds, nst.ageAdd,
                    System.currentTimeMillis(), nst.maxEarlyDataSize, negotiatedSuite, psk);
            sink.sessionTicketReceived(ticket);
        } else if (type == HandshakeMessages.HANDSHAKE_TYPE_KEY_UPDATE) {
            onKeyUpdate(message, sink);
        } else {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Unexpected post-handshake message type " + type);
        }
    }

    // ---- server role ----

    private void processAsServer(byte[] message, TlsEventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        int type = message[0] & 0xff;
        switch (state) {
            case INITIAL:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_CLIENT_HELLO) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected ClientHello");
                    return;
                }
                onClientHello(message, sink);
                break;
            case WAIT_CLIENT_CERTIFICATE:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Certificate");
                    return;
                }
                onClientCertificate(message, sink);
                break;
            case WAIT_CLIENT_CERTIFICATE_VERIFY:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE_VERIFY) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected CertificateVerify");
                    return;
                }
                onCertificateVerify(message, sink, false);
                break;
            case WAIT_CLIENT_FINISHED:
                if (type != HandshakeMessages.HANDSHAKE_TYPE_FINISHED) {
                    fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Expected Finished");
                    return;
                }
                onClientFinished(message, sink);
                break;
            case COMPLETE:
                onPostHandshakeMessageAsServer(type, message, sink);
                break;
            default:
                fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Unexpected message in state " + state);
        }
    }

    private void onClientHello(byte[] message, TlsEventSink sink)
            throws HandshakeFormatException, GeneralSecurityException {
        HandshakeMessages.ClientHello ch = HandshakeMessages.parseClientHello(message);
        if (!ch.supportsTls13) {
            fail(sink, AlertDescription.PROTOCOL_VERSION, "Client did not offer TLS 1.3");
            return;
        }
        negotiatedSuite = selectFirst(config.getCipherSuites(), ch.cipherSuites);
        if (negotiatedSuite == null) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE, "No mutually acceptable cipher suite");
            return;
        }
        if (serverRetryRequestedGroup != null && !negotiatedSuite.equals(retryCipherSuite)) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Cipher suite changed across HelloRetryRequest");
            return;
        }
        ServerCredentials resolvedCredentials = (config.getServerCredentialsResolver() != null)
                ? config.getServerCredentialsResolver().resolve(ch.serverName)
                : config.getServerCredentials();
        if (resolvedCredentials == null) {
            fail(sink, AlertDescription.INTERNAL_ERROR, "No server credentials configured");
            return;
        }

        // RFC 8446 section 4.1.4: select the negotiated group from what
        // both sides support, not merely from what the client sent a key
        // share for -- a mutually supported group the client only listed
        // in supported_groups (no matching share) is precisely the
        // "need a retry" condition, not a negotiation failure.
        NamedGroup group = selectFirst(config.getNamedGroups(), ch.supportedGroups);
        if (group == null) {
            fail(sink, AlertDescription.HANDSHAKE_FAILURE, "No mutually acceptable group");
            return;
        }
        CookieValidator validator = config.getCookieValidator();
        boolean needCookie = validator != null
                && (ch.cookie == null || !validator.validateCookie(ch.random, ch.cookie));
        boolean needGroupRetry = !ch.keyShares.containsKey(group);
        if ((needCookie || needGroupRetry) && serverRetryRequestedGroup == null) {
            byte[] cookie = needCookie ? validator.computeCookie(ch.random) : null;
            sendHelloRetryRequest(group, ch.legacySessionId, negotiatedSuite, message, sink, cookie);
            return;
        }
        if (needCookie) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Invalid or missing cookie in followup ClientHello");
            return;
        }
        if (serverRetryRequestedGroup != null && !serverRetryRequestedGroup.equals(group)) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Server-selected group changed across HelloRetryRequest");
            return;
        }
        if (needGroupRetry) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER,
                    "Client key share group still mismatched after HelloRetryRequest");
            return;
        }
        serverRetryRequestedGroup = null;

        TicketPayload resumedPayload = tryResumePsk(ch, message);
        resumed = (resumedPayload != null);
        byte[] presentedPsk = resumed ? resumedPayload.psk : null;

        if (transcript == null) {
            transcript = Transcript.create(negotiatedSuite);
        }
        transcript.update(message);

        KeyExchange.ServerResult kxResult = KeyExchange.agreeAsServer(group, ch.keyShares.get(group));

        byte[] serverRandom = new byte[32];
        secureRandom.nextBytes(serverRandom);
        byte[] serverHello = HandshakeMessages.buildServerHello(serverRandom, ch.legacySessionId,
                negotiatedSuite, group, kxResult.getShareBytes(), resumed);
        transcript.update(serverHello);
        sink.handshakeDataReady(serverHello);

        keySchedule = new KeySchedule(negotiatedSuite);
        keySchedule.deriveEarlySecret(presentedPsk);
        keySchedule.deriveHandshakeSecret(kxResult.getSharedSecret(), transcript.hash());
        sink.handshakeSecretsReady();

        negotiatedAlpn = selectFirst(config.getApplicationProtocols(), ch.alpnProtocols);
        if (!config.getApplicationProtocols().isEmpty() && negotiatedAlpn == null) {
            fail(sink, AlertDescription.NO_APPLICATION_PROTOCOL, "No mutually acceptable application protocol");
            return;
        }

        earlyDataAccepted = tryAcceptEarlyData(ch, message, resumedPayload, sink);

        byte[] encryptedExtensions = HandshakeMessages.buildEncryptedExtensions(
                negotiatedAlpn, config.getLocalTransportParameters(), earlyDataAccepted);
        transcript.update(encryptedExtensions);
        sink.handshakeDataReady(encryptedExtensions);
        if (ch.quicTransportParameters != null) {
            sink.peerTransportParameters(ch.quicTransportParameters);
        }

        boolean requestClientCert = !resumed && config.getClientAuthPolicy() != ClientAuthPolicy.NONE;

        if (!resumed) {
            // RFC 8446 section 4.3.2: CertificateRequest precedes the
            // server's own Certificate.
            if (requestClientCert) {
                byte[] certificateRequest = HandshakeMessages.buildCertificateRequest();
                transcript.update(certificateRequest);
                sink.handshakeDataReady(certificateRequest);
            }

            List<X509Certificate> chain = resolvedCredentials.getCertificateChain();
            List<byte[]> der = new ArrayList<byte[]>(chain.size());
            for (int i = 0; i < chain.size(); i++) {
                try {
                    der.add(chain.get(i).getEncoded());
                } catch (CertificateEncodingException e) {
                    fail(sink, AlertDescription.INTERNAL_ERROR, "Could not encode server certificate: " + e.getMessage());
                    return;
                }
            }
            byte[] certificate = HandshakeMessages.buildCertificate(new byte[0], der);
            transcript.update(certificate);
            sink.handshakeDataReady(certificate);

            SignatureScheme scheme = selectSignatureScheme(resolvedCredentials.getPrivateKey());
            if (scheme == null) {
                fail(sink, AlertDescription.INTERNAL_ERROR, "Unsupported server private key type");
                return;
            }
            byte[] signedContent = HandshakeMessages.certificateVerifySignedContent(true, transcript.hash());
            byte[] signature = scheme.sign(resolvedCredentials.getPrivateKey(), signedContent);
            byte[] certificateVerify = HandshakeMessages.buildCertificateVerify(scheme, signature);
            transcript.update(certificateVerify);
            sink.handshakeDataReady(certificateVerify);
        }

        byte[] serverVerifyData = keySchedule.computeFinishedVerifyData(
                keySchedule.getServerHandshakeTrafficSecret(), transcript.hash());
        byte[] finished = HandshakeMessages.buildFinished(serverVerifyData);
        transcript.update(finished);
        sink.handshakeDataReady(finished);

        hashThroughServerFinished = transcript.hash();
        keySchedule.deriveMasterSecret();
        keySchedule.deriveApplicationTrafficSecrets(hashThroughServerFinished);

        state = requestClientCert ? State.WAIT_CLIENT_CERTIFICATE : State.WAIT_CLIENT_FINISHED;
    }

    /**
     * Server role: sends a HelloRetryRequest requesting {@code group},
     * seeding the running transcript with RFC 8446 section 4.4.1's
     * {@code message_hash} substitution for the just-received
     * ClientHello1 -- {@code onClientHello} is re-entered naturally when
     * the followup ClientHello2 arrives (the state machine stays in
     * {@code INITIAL}).
     */
    private void sendHelloRetryRequest(NamedGroup group, byte[] legacySessionId, CipherSuite cipherSuite,
            byte[] clientHello1, TlsEventSink sink, byte[] cookie) {
        Transcript t = Transcript.create(cipherSuite);
        t.update(clientHello1);
        byte[] ch1Hash = t.hash();
        transcript = t;
        transcript.retry(ch1Hash);

        byte[] hrr = HandshakeMessages.buildHelloRetryRequest(legacySessionId, cipherSuite, group, cookie);
        transcript.update(hrr);
        sink.handshakeDataReady(hrr);

        serverRetryRequestedGroup = group;
        retryCipherSuite = cipherSuite;
    }

    /**
     * Attempts PSK resumption against a parsed ClientHello's
     * {@code pre_shared_key} extension: opens the ticket identity against
     * every {@link HandshakeConfig#getTicketKeys} candidate key, verifies
     * the binder over the raw message's truncated bytes (the last 33
     * bytes -- a 1-byte binder-entry length plus the 32-byte binder --
     * sliced off directly, per {@link HandshakeMessages#buildClientHelloTruncatedForBinder}'s
     * documentation), and checks the ticket has not exceeded its
     * lifetime. Falls through to a full handshake (returns null, no
     * alert) on any failure -- an unrecognised, expired, or
     * binder-mismatched PSK is not itself a protocol error.
     *
     * @return the opened ticket payload, or null for a full handshake
     */
    private TicketPayload tryResumePsk(HandshakeMessages.ClientHello ch, byte[] message) {
        if (ch.pskIdentity == null || ch.pskBinder == null || config.getTicketKeys() == null) {
            return null;
        }
        TicketPayload payload = null;
        List<byte[]> candidates = config.getTicketKeys().candidateKeys();
        for (int i = 0; i < candidates.size(); i++) {
            payload = TicketPayload.open(candidates.get(i), ch.pskIdentity);
            if (payload != null) {
                break;
            }
        }
        if (payload == null || message.length <= 33) {
            return null;
        }
        // RFC 8446 section 4.2.11: the PSK's associated hash algorithm
        // (fixed at issuance) must match the negotiated cipher suite's.
        if (!payload.cipherSuite.getHashAlgorithm().equals(negotiatedSuite.getHashAlgorithm())) {
            return null;
        }
        byte[] truncated = Arrays.copyOfRange(message, 0, message.length - 33);
        Transcript truncatedTranscript = Transcript.create(payload.cipherSuite);
        truncatedTranscript.update(truncated);
        KeySchedule pskSchedule = new KeySchedule(payload.cipherSuite);
        byte[] expectedBinder = pskSchedule.computePskBinder(payload.psk, truncatedTranscript.hash());
        if (!MessageDigest.isEqual(expectedBinder, ch.pskBinder)) {
            return null;
        }
        int recoveredAgeMillis = ch.obfuscatedTicketAge - payload.ageAdd;
        long lifetimeMillis = ((long) payload.lifetimeSeconds) * 1000L;
        if (recoveredAgeMillis < 0 || recoveredAgeMillis > lifetimeMillis) {
            return null;
        }
        return payload;
    }

    /**
     * Decides whether to accept 0-RTT for an already-resumed ClientHello
     * (RFC 9001 section 4.6.1 / RFC 8446 section 4.2.10), and if so
     * derives and pushes the early traffic secret. Requires the client to
     * have requested it, this side to allow it, the ticket to actually
     * carry 0-RTT permission, the ticket's age to be within
     * {@link HandshakeConfig#getEarlyDataFreshnessMs}, the configured
     * {@link TransportParameterConsistencyChecker} to accept the
     * remembered-vs-current transport parameters (RFC 9000 section
     * 7.4.1), and the configured {@link AntiReplay} (if any) to accept
     * this identity.
     *
     * @param payload the opened ticket, or null if this is not a resumed handshake
     * @return true if 0-RTT was accepted
     */
    private boolean tryAcceptEarlyData(HandshakeMessages.ClientHello ch, byte[] message, TicketPayload payload,
            TlsEventSink sink) {
        if (payload == null || !ch.earlyDataRequested || !config.isEnableEarlyData()
                || payload.maxEarlyDataSize <= 0) {
            return false;
        }
        int recoveredAgeMillis = ch.obfuscatedTicketAge - payload.ageAdd;
        if (recoveredAgeMillis > config.getEarlyDataFreshnessMs()) {
            return false;
        }
        if (!config.getTransportParameterConsistencyChecker().isConsistent(
                payload.rememberedTransportParameters, config.getLocalTransportParameters())) {
            return false;
        }
        if (config.getAntiReplay() != null && !config.getAntiReplay().checkAndRecord(ch.pskIdentity)) {
            return false;
        }
        Transcript chTranscript = Transcript.create(payload.cipherSuite);
        chTranscript.update(message);
        KeySchedule pskSchedule = new KeySchedule(payload.cipherSuite);
        byte[] earlyTrafficSecret = pskSchedule.deriveEarlyTrafficSecret(payload.psk, chTranscript.hash());
        sink.quicEarlyKeysReady(payload.cipherSuite, earlyTrafficSecret);
        return true;
    }

    /**
     * Server role: the client's response to a requested
     * {@code CertificateRequest} (RFC 8446 section 4.3.2, mTLS).
     * An empty chain fails under {@link ClientAuthPolicy#REQUIRE} (RFC
     * 8446 section 4.4.2 permits sending one, but this server does not
     * accept it under that policy) and otherwise proceeds straight to
     * {@code WAIT_CLIENT_FINISHED} with no peer chain to record. A
     * non-empty chain is parsed and checked against
     * {@link HandshakeConfig#getClientTrustManager} -- an untrusted chain
     * fails under {@link ClientAuthPolicy#REQUIRE}, but otherwise the
     * handshake proceeds regardless of trust outcome (matching
     * {@link ClientAuthPolicy#REQUEST}'s contract) to
     * {@code WAIT_CLIENT_CERTIFICATE_VERIFY}, since RFC 8446 still
     * requires the client's {@code CertificateVerify} whenever it sent a
     * non-empty chain, independent of whether this server trusts it.
     */
    private void onClientCertificate(byte[] message, TlsEventSink sink) throws HandshakeFormatException {
        byte[] context = HandshakeMessages.parseCertificateContext(message);
        if (context.length != 0) {
            fail(sink, AlertDescription.ILLEGAL_PARAMETER, "Client Certificate context does not match CertificateRequest");
            return;
        }
        List<byte[]> der = HandshakeMessages.parseCertificate(message);
        transcript.update(message);

        if (der.isEmpty()) {
            if (config.getClientAuthPolicy() == ClientAuthPolicy.REQUIRE) {
                fail(sink, AlertDescription.CERTIFICATE_REQUIRED, "Client did not present a certificate");
                return;
            }
            peerCertificateChain = null;
            state = State.WAIT_CLIENT_FINISHED;
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
        state = State.WAIT_CLIENT_CERTIFICATE_VERIFY;
    }

    private void onClientFinished(byte[] message, TlsEventSink sink) throws HandshakeFormatException {
        byte[] verifyData = HandshakeMessages.parseFinished(message);
        // Not hashThroughServerFinished -- the client's own Finished
        // verify-data covers the transcript through whatever it sent
        // right before its Finished, which (mTLS) may include its own
        // Certificate/CertificateVerify beyond the server's Finished.
        byte[] expected = keySchedule.computeFinishedVerifyData(
                keySchedule.getClientHandshakeTrafficSecret(), transcript.hash());
        if (!MessageDigest.isEqual(expected, verifyData)) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "Client Finished verify-data mismatch");
            return;
        }
        transcript.update(message);
        resumptionMasterSecret = keySchedule.deriveResumptionMasterSecret(transcript.hash());
        state = State.COMPLETE;
        sink.applicationSecretsReady();
        issueSessionTicketIfConfigured(sink);
    }

    /**
     * Mints and sends one {@code NewSessionTicket}, if
     * {@link HandshakeConfig#getTicketKeys} is configured -- automatic,
     * unconditional on every completed server handshake, matching hopf.
     * Post-handshake: the ticket message is sent as ordinary handshake
     * data but deliberately never joins the transcript (nothing derived
     * from the transcript hash is computed after this point).
     */
    private void issueSessionTicketIfConfigured(TlsEventSink sink) {
        TicketKeys ticketKeys = config.getTicketKeys();
        if (ticketKeys == null) {
            return;
        }
        byte[] nonce = new byte[8];
        secureRandom.nextBytes(nonce);
        byte[] psk = keySchedule.deriveResumptionPsk(resumptionMasterSecret, nonce);

        byte[] ageAddBytes = new byte[4];
        secureRandom.nextBytes(ageAddBytes);
        int ageAdd = ((ageAddBytes[0] & 0xff) << 24) | ((ageAddBytes[1] & 0xff) << 16)
                | ((ageAddBytes[2] & 0xff) << 8) | (ageAddBytes[3] & 0xff);

        int lifetimeSeconds = 24 * 60 * 60;
        int maxEarlyDataSize = config.isEnableEarlyData() ? config.getMaxEarlyDataSize() : 0;

        TicketPayload payload = new TicketPayload(System.currentTimeMillis(), lifetimeSeconds, ageAdd, psk,
                maxEarlyDataSize, negotiatedSuite, config.getLocalTransportParameters());
        byte[] identity = payload.seal(ticketKeys.getCurrentKey());

        byte[] ticketMessage = HandshakeMessages.buildNewSessionTicket(lifetimeSeconds, ageAdd, nonce, identity,
                maxEarlyDataSize);
        sink.handshakeDataReady(ticketMessage);
    }

    private void onPostHandshakeMessageAsServer(int type, byte[] message, TlsEventSink sink)
            throws HandshakeFormatException {
        if (type == HandshakeMessages.HANDSHAKE_TYPE_KEY_UPDATE) {
            onKeyUpdate(message, sink);
        } else {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "Unexpected post-handshake message type " + type);
        }
    }

    /**
     * A post-handshake {@code KeyUpdate} (RFC 8446 section 4.6.3/7.2),
     * either role -- ratchets the peer's application traffic secret
     * forward, fires {@link TlsEventSink#applicationTrafficSecretUpdated}
     * with {@link KeyUpdateDirection#READ}, and -- if the peer requested a
     * reciprocal update -- sends our own {@code KeyUpdate} back before
     * returning, since RFC 8446 section 4.6.3 requires that to happen
     * before any further application data goes out. Rejected outright
     * outside {@link HandshakeMode#TCP_RECORD_LAYER}, since RFC 9001
     * section 4.6 forbids this message over QUIC entirely (QUIC has its
     * own, separate packet-level key update).
     */
    private void onKeyUpdate(byte[] message, TlsEventSink sink) throws HandshakeFormatException {
        if (config.getMode() != HandshakeMode.TCP_RECORD_LAYER) {
            fail(sink, AlertDescription.UNEXPECTED_MESSAGE, "KeyUpdate is not supported over QUIC (RFC 9001 section 4.6)");
            return;
        }
        int kind = HandshakeMessages.parseKeyUpdate(message);
        if (kind != HandshakeMessages.KEY_UPDATE_NOT_REQUESTED && kind != HandshakeMessages.KEY_UPDATE_REQUESTED) {
            fail(sink, AlertDescription.DECODE_ERROR, "Malformed KeyUpdateRequest");
            return;
        }
        byte[] newSecret = (config.getRole() == HandshakeRole.CLIENT)
                ? keySchedule.updateServerApplicationTrafficSecret()
                : keySchedule.updateClientApplicationTrafficSecret();
        sink.applicationTrafficSecretUpdated(KeyUpdateDirection.READ, newSecret);
        if (kind == HandshakeMessages.KEY_UPDATE_REQUESTED) {
            sendKeyUpdate(sink, false);
        }
    }

    /**
     * Builds, sends, and applies one outgoing {@code KeyUpdate}: ratchets
     * this role's own application traffic secret forward and fires
     * {@link TlsEventSink#applicationTrafficSecretUpdated} with
     * {@link KeyUpdateDirection#WRITE}. Wire bytes go out before the
     * secret changes, so the message itself is (correctly) protected
     * under the pre-update key.
     */
    private void sendKeyUpdate(TlsEventSink sink, boolean requestPeerUpdate) {
        int kind = requestPeerUpdate ? HandshakeMessages.KEY_UPDATE_REQUESTED : HandshakeMessages.KEY_UPDATE_NOT_REQUESTED;
        sink.handshakeDataReady(HandshakeMessages.buildKeyUpdate(kind));
        byte[] newSecret = (config.getRole() == HandshakeRole.CLIENT)
                ? keySchedule.updateClientApplicationTrafficSecret()
                : keySchedule.updateServerApplicationTrafficSecret();
        sink.applicationTrafficSecretUpdated(KeyUpdateDirection.WRITE, newSecret);
    }

    // ---- shared helpers ----

    /**
     * Verifies a peer's {@code CertificateVerify} against
     * {@link #peerCertificateChain}'s leaf -- the client verifying the
     * server's ({@code verifyingServer} true, from {@link #processAsClient}),
     * or the server verifying a requested client certificate's
     * ({@code verifyingServer} false, from {@link #processAsServer}, mTLS).
     * {@code verifyingServer} also picks the next state, since it already
     * exactly distinguishes the two call sites: the client still has the
     * server's own Finished to wait for; the server still has the
     * client's.
     */
    private void onCertificateVerify(byte[] message, TlsEventSink sink, boolean verifyingServer)
            throws HandshakeFormatException, GeneralSecurityException {
        byte[] hashBeforeThisMessage = transcript.hash();
        HandshakeMessages.CertificateVerify cv = HandshakeMessages.parseCertificateVerify(message);
        byte[] signedContent = HandshakeMessages.certificateVerifySignedContent(verifyingServer, hashBeforeThisMessage);
        boolean ok = cv.scheme.verify(peerCertificateChain.get(0).getPublicKey(), signedContent, cv.signature);
        if (!ok) {
            fail(sink, AlertDescription.DECRYPT_ERROR, "CertificateVerify signature did not verify");
            return;
        }
        transcript.update(message);
        state = verifyingServer ? State.WAIT_SERVER_FINISHED : State.WAIT_CLIENT_FINISHED;
    }

    private static SignatureScheme selectSignatureScheme(PrivateKey key) {
        String algorithm = key.getAlgorithm();
        if ("RSA".equals(algorithm)) {
            return SignatureScheme.RSA_PSS_RSAE_SHA256;
        }
        if ("EC".equals(algorithm) && key instanceof ECKey) {
            int fieldBits = ((ECKey) key).getParams().getCurve().getField().getFieldSize();
            return (fieldBits > 256) ? SignatureScheme.ECDSA_SECP384R1_SHA384 : SignatureScheme.ECDSA_SECP256R1_SHA256;
        }
        if ("Ed25519".equals(algorithm) || "EdDSA".equals(algorithm)) {
            return SignatureScheme.ED25519;
        }
        return null;
    }

    // Package-private (not private): a genuinely generic overlap-preference
    // rule, reused by Tls12HandshakeEngine for ALPN negotiation so both
    // engines share the exact same server-preference-wins tie-breaking.
    static <T> T selectFirst(List<T> preferenceOrder, List<T> offered) {
        for (int i = 0; i < preferenceOrder.size(); i++) {
            T candidate = preferenceOrder.get(i);
            if (offered.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void fail(TlsEventSink sink, AlertDescription alert, String message) {
        state = State.FAILED;
        sink.protocolError(new TlsProtocolError(alert, message));
    }

    /**
     * Requests a post-handshake key update (RFC 8446 section 4.6.3/7.2):
     * ratchets this side's own application traffic secret forward,
     * optionally asking the peer to reciprocate. Only meaningful once the
     * handshake has completed and in {@link HandshakeMode#TCP_RECORD_LAYER}
     * mode -- RFC 9001 section 4.6 forbids {@code KeyUpdate} over QUIC
     * entirely (QUIC has its own, separate packet-level key update); this
     * method returns {@code false} with no effect in every other case,
     * local API misuse rather than a peer-caused protocol error, so no
     * sink event fires for that.
     *
     * @param sink where to push resulting events
     * @param requestPeerUpdate whether to also request the peer update
     *                          its own sending keys
     * @return true if a KeyUpdate was actually sent
     */
    public boolean requestKeyUpdate(TlsEventSink sink, boolean requestPeerUpdate) {
        if (state != State.COMPLETE || config.getMode() != HandshakeMode.TCP_RECORD_LAYER) {
            return false;
        }
        sendKeyUpdate(sink, requestPeerUpdate);
        return true;
    }

    // ---- state accessors ----

    /**
     * Returns whether the handshake has completed successfully.
     *
     * @return true once application traffic secrets are available
     */
    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    /**
     * Returns whether the handshake has failed.
     *
     * @return true if a protocol error has occurred
     */
    public boolean isFailed() {
        return state == State.FAILED;
    }

    /**
     * Returns whether this handshake resumed a previous session via PSK
     * (RFC 8446 section 2.2), valid once negotiated (after ServerHello on
     * the client side, after ClientHello on the server side).
     *
     * @return true if this is a resumed handshake
     */
    public boolean isResumed() {
        return resumed;
    }

    /**
     * Returns whether 0-RTT early data was accepted. Server role only --
     * always false for a client engine (use
     * {@link TlsEventSink#earlyDataAccepted} instead, which fires for
     * both roles' actual use).
     *
     * @return true if early data was accepted
     */
    public boolean wasEarlyDataAccepted() {
        return earlyDataAccepted;
    }

    /**
     * Returns the negotiated cipher suite, once known (after ServerHello
     * on the client side, after ClientHello on the server side).
     *
     * @return the negotiated cipher suite, or null if not yet negotiated
     */
    public CipherSuite getNegotiatedCipherSuite() {
        return negotiatedSuite;
    }

    /**
     * Returns the negotiated ALPN application protocol, once known.
     *
     * @return the negotiated protocol name, or null if none was negotiated
     */
    public String getNegotiatedApplicationProtocol() {
        return negotiatedAlpn;
    }

    /**
     * Returns the client handshake traffic secret, once
     * {@link TlsEventSink#handshakeSecretsReady} has fired.
     *
     * @return the client handshake traffic secret
     */
    public byte[] getClientHandshakeTrafficSecret() {
        return keySchedule == null ? null : keySchedule.getClientHandshakeTrafficSecret();
    }

    /**
     * Returns the server handshake traffic secret, once
     * {@link TlsEventSink#handshakeSecretsReady} has fired.
     *
     * @return the server handshake traffic secret
     */
    public byte[] getServerHandshakeTrafficSecret() {
        return keySchedule == null ? null : keySchedule.getServerHandshakeTrafficSecret();
    }

    /**
     * Returns the client application traffic secret, once
     * {@link TlsEventSink#applicationSecretsReady} has fired.
     *
     * @return the client application traffic secret
     */
    public byte[] getClientApplicationTrafficSecret() {
        return keySchedule == null ? null : keySchedule.getClientApplicationTrafficSecret();
    }

    /**
     * Returns the server application traffic secret, once
     * {@link TlsEventSink#applicationSecretsReady} has fired.
     *
     * @return the server application traffic secret
     */
    public byte[] getServerApplicationTrafficSecret() {
        return keySchedule == null ? null : keySchedule.getServerApplicationTrafficSecret();
    }

    /**
     * Returns the peer's certificate chain: the server's (always verified)
     * for the client role, or -- once {@link HandshakeConfig#getClientAuthPolicy}
     * requests it (mTLS) -- the client's for the server role, verified
     * only when {@link HandshakeConfig#getClientTrustManager} is
     * consulted, per {@link ClientAuthPolicy}'s own contract (an
     * unverified chain under {@link ClientAuthPolicy#REQUEST} is still
     * returned here as-is). Also null for a resumed handshake, which does
     * not exchange a certificate at all, and for the server role when the
     * client presented none under {@link ClientAuthPolicy#REQUEST}.
     *
     * @return the peer's certificate chain, leaf first, or null if not
     *         yet available or not applicable to this role/handshake
     */
    public List<X509Certificate> getPeerCertificateChain() {
        return peerCertificateChain;
    }

}
