/*
 * QuicTlsClientEngine.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import tech.kwik.agent15.NewSessionTicket;
import tech.kwik.agent15.TlsConstants;
import tech.kwik.agent15.TlsProtocolException;
import tech.kwik.agent15.engine.ClientMessageSender;
import tech.kwik.agent15.engine.TlsClientEngine;
import tech.kwik.agent15.engine.TlsClientEngineFactory;
import tech.kwik.agent15.engine.TlsMessageParser;
import tech.kwik.agent15.engine.TlsStatusEventHandler;
import tech.kwik.agent15.extension.ApplicationLayerProtocolNegotiationExtension;
import tech.kwik.agent15.extension.EarlyDataExtension;
import tech.kwik.agent15.extension.Extension;
import tech.kwik.agent15.handshake.CertificateMessage;
import tech.kwik.agent15.handshake.CertificateVerifyMessage;
import tech.kwik.agent15.handshake.ClientHello;
import tech.kwik.agent15.handshake.FinishedMessage;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

/**
 * Bridges Agent15's {@link TlsClientEngine} to gumdrop's QUIC transport:
 * routes handshake message bytes to and from per-level
 * {@link CryptoStreamBuffer}s, and forwards secret-availability and
 * completion events to a {@link QuicTlsEngineListener}.
 *
 * <p>The QUIC transport-parameters extension (RFC 9000 section 7.4,
 * RFC 9001 section 8.2) is added to the handshake and its receipt is
 * surfaced via {@link QuicTlsEngineListener#transportParametersReceived};
 * validating it against RFC 9000 section 7.3's requirements (e.g. that
 * it MUST be present, that original_destination_connection_id MUST
 * match) is not done yet.
 *
 * <p>ALPN (RFC 7301) is added when an application protocol list is
 * configured: this is minimal by design (gumdrop only ever configures
 * one protocol per QUIC listener/client today, e.g. "h3" or "doq" --
 * see {@code QuicTransportFactory#setApplicationProtocols}) and does not
 * enforce RFC 7301's negotiation-failure closing behaviour; the server
 * side (see {@link QuicTlsServerEngine}) silently leaves the selected
 * protocol unset rather than aborting the handshake if nothing matches.
 * A real ALPN implementation was added as a dependency of RFC 9001
 * section 4.6.1's 0-RTT accept gate, which requires it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsServerEngine
 */
public final class QuicTlsClientEngine
        implements ClientMessageSender, TlsStatusEventHandler, QuicTlsEngine {

    private static final Logger LOGGER = Logger.getLogger(QuicTlsClientEngine.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.quic.L10N");

    private final TlsClientEngine engine;
    private final QuicTlsEngineListener listener;
    private final TlsMessageParser messageParser = new TlsMessageParser();
    private final QuicHandshakeAsyncOffload asyncOffload;

    private final CryptoStreamBuffer initialReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer handshakeReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer applicationReceiveBuffer = new CryptoStreamBuffer();

    // Raw CRYPTO frame bytes received while a batch of handshake message
    // processing is already in flight on a crypto thread -- Agent15's
    // engines are not safe for concurrent use, so these wait, in arrival
    // order, until the in-flight batch completes (see #drainPendingFrames).
    private final Deque<PendingFrame> pendingFrames = new ArrayDeque<PendingFrame>();

    private long initialSendOffset;
    private long handshakeSendOffset;

    // RFC 9001 section 4.6.1: the cipher suite a presented session
    // ticket was originally issued under -- needed to derive 0-RTT keys
    // in earlySecretsKnown(), which fires right after ClientHello is
    // sent, before ServerHello negotiates this handshake's own cipher
    // (engine.getSelectedCipher() isn't populated yet at that point).
    private TlsConstants.CipherSuite earlyDataCipher;

    // The single named group to offer a key_share for (RFC 8446 section
    // 4.2.8), resolved from QuicTransportFactory#setNamedGroups -- null
    // means "let Agent15 pick its own default", today's existing
    // behaviour. Agent15's TlsClientEngine only accepts one group here
    // (it sends exactly one key_share, not a list), so a caller who
    // configures several falls back to the first one Agent15 actually
    // supports.
    private final TlsConstants.NamedGroup preferredNamedGroup;

    /**
     * Creates a client-side TLS engine, offering no ALPN application
     * protocols and no named-group preference.
     *
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in the ClientHello
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     */
    public QuicTlsClientEngine(TransportParameters transportParameters, QuicTlsEngineListener listener) {
        this(transportParameters, listener, null);
    }

    /**
     * Creates a client-side TLS engine with no named-group preference.
     *
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in the ClientHello
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param applicationProtocols the ALPN application protocol(s) to
     *                             offer (RFC 7301), comma-separated, or
     *                             null to offer none
     */
    public QuicTlsClientEngine(TransportParameters transportParameters, QuicTlsEngineListener listener,
            String applicationProtocols) {
        this(transportParameters, listener, applicationProtocols, null);
    }

    /**
     * Creates a client-side TLS engine with no cipher-suite preference
     * (offers gumdrop's full default list).
     *
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in the ClientHello
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param applicationProtocols the ALPN application protocol(s) to
     *                             offer (RFC 7301), comma-separated, or
     *                             null to offer none
     * @param namedGroups colon-separated preferred named group(s) (e.g.
     *                    {@code "x25519:secp256r1"}, matching the same
     *                    IANA/TLS-registry names {@code
     *                    TransportFactory#setNamedGroups}'s javadoc
     *                    already documents), or null for Agent15's
     *                    default. The first name Agent15 actually
     *                    supports is used; unrecognised names (e.g. a
     *                    hybrid PQC group -- Agent15 has no ML-KEM
     *                    support, see {@link TlsConstants.NamedGroup})
     *                    are skipped with a logged warning rather than
     *                    silently substituted or failing the connection.
     */
    public QuicTlsClientEngine(TransportParameters transportParameters, QuicTlsEngineListener listener,
            String applicationProtocols, String namedGroups) {
        this(transportParameters, listener, applicationProtocols, namedGroups, null);
    }

    /**
     * Creates a client-side TLS engine.
     *
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in the ClientHello
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param applicationProtocols the ALPN application protocol(s) to
     *                             offer (RFC 7301), comma-separated, or
     *                             null to offer none
     * @param namedGroups colon-separated preferred named group(s) (e.g.
     *                    {@code "x25519:secp256r1"}, matching the same
     *                    IANA/TLS-registry names {@code
     *                    TransportFactory#setNamedGroups}'s javadoc
     *                    already documents), or null for Agent15's
     *                    default. The first name Agent15 actually
     *                    supports is used; unrecognised names (e.g. a
     *                    hybrid PQC group -- Agent15 has no ML-KEM
     *                    support, see {@link TlsConstants.NamedGroup})
     *                    are skipped with a logged warning rather than
     *                    silently substituted or failing the connection.
     * @param cipherSuites colon-separated preferred cipher suite(s) in
     *                     IANA form (e.g. {@code
     *                     "TLS_CHACHA20_POLY1305_SHA256"}, matching
     *                     {@code TransportFactory#setCipherSuites}'s
     *                     javadoc), or null to offer gumdrop's full
     *                     default list. Only names gumdrop's own AEAD
     *                     layer actually implements are offered -- see
     *                     {@link QuicCipherSuites#resolve}.
     */
    public QuicTlsClientEngine(TransportParameters transportParameters, QuicTlsEngineListener listener,
            String applicationProtocols, String namedGroups, String cipherSuites) {
        this.listener = listener;
        this.asyncOffload = new QuicHandshakeAsyncOffload(listener);
        this.engine = TlsClientEngineFactory.createClientEngine(this, this);
        this.preferredNamedGroup = resolvePreferredNamedGroup(namedGroups);

        engine.addSupportedCiphers(QuicCipherSuites.resolve(cipherSuites));
        engine.add(new QuicTransportParametersExtension(transportParameters));
        if (applicationProtocols != null && !applicationProtocols.isEmpty()) {
            engine.add(new ApplicationLayerProtocolNegotiationExtension(
                    java.util.Arrays.asList(applicationProtocols.split(","))));
        }
    }

    private static TlsConstants.NamedGroup resolvePreferredNamedGroup(String namedGroups) {
        if (namedGroups == null || namedGroups.isEmpty()) {
            return null;
        }
        for (String name : namedGroups.split(":")) {
            name = name.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                return TlsConstants.NamedGroup.valueOf(name.toLowerCase());
            } catch (IllegalArgumentException e) {
                // Tried in order below; not every name here is necessarily
                // unsupported by Agent15 -- keep trying the rest before
                // warning.
            }
        }
        if (LOGGER.isLoggable(Level.WARNING)) {
            String message = MessageFormat.format(
                    L10N.getString("warn.named_groups_fallback"), namedGroups);
            LOGGER.warning(message);
        }
        return null;
    }

    /**
     * Sets a custom trust manager for verifying the server certificate.
     * If not called, the platform default trust store is used.
     *
     * @param trustManager the trust manager
     */
    public void setTrustManager(X509TrustManager trustManager) {
        engine.setTrustManager(trustManager);
    }

    /**
     * Disables hostname verification of the peer's certificate against
     * the server name presented in the handshake.
     *
     * <p>Agent15 requires a non-null server name to start any client
     * handshake at all (its own {@code startHandshake} throws otherwise),
     * but RFC 6066 section 3 disallows IP literals in a real SNI value --
     * a caller with no real hostname to offer (e.g. a DNS-over-QUIC
     * client connecting directly to a resolved IP, RFC 9250) has nothing
     * for Agent15's hostname check to meaningfully match against, since
     * its default verifier has no notion of comparing a literal address
     * to a certificate's IP-typed SAN entries. Such a caller is expected
     * to establish trust another way instead (a pinned certificate
     * fingerprint or a private CA via {@link #setTrustManager}), matching
     * RFC 8310 section 8.1's SPKI-pinning-as-alternative precedent for
     * DNS-over-TLS clients in the same situation.
     *
     * <p>Not called at all (the default) leaves Agent15's own hostname
     * verifier in place, unchanged.
     *
     * @param verify false to accept any hostname/certificate pairing
     */
    public void setVerifyHostname(boolean verify) {
        if (!verify) {
            engine.setHostnameVerifier(new tech.kwik.agent15.engine.HostnameVerifier() {
                @Override
                public boolean verify(String hostName, X509Certificate serverCertificate) {
                    return true;
                }
            });
        }
    }

    /**
     * Starts the TLS handshake, producing a ClientHello via
     * {@link QuicTlsEngineListener#cryptoDataReady} at
     * {@link EncryptionLevel#INITIAL}. Offers a key_share for the
     * configured preferred named group, if one resolved successfully at
     * construction time; otherwise leaves the choice to Agent15's own
     * default.
     *
     * @param serverName the SNI server name
     * @throws IOException if the handshake cannot be started
     */
    public void startHandshake(String serverName) throws IOException {
        engine.setServerName(serverName);
        if (preferredNamedGroup != null) {
            engine.startHandshake(preferredNamedGroup);
        } else {
            engine.startHandshake();
        }
    }

    /**
     * Feeds received CRYPTO frame data at the given level into
     * handshake message reassembly. Complete messages are dispatched to
     * Agent15 asynchronously, off the caller's thread, via {@link
     * QuicHandshakeAsyncOffload}; a processing failure reaches {@link
     * QuicTlsEngineListener#cryptoProcessingFailed} rather than being
     * thrown back through this call.
     *
     * @param level the encryption level the data was received at
     * @param offset the byte offset of {@code data} within this level's
     *               CRYPTO stream
     * @param data the received handshake data
     * @throws StreamReassembler.BufferLimitExceededException if reordered
     *         data exceeds the per-level reassembly buffer's limit
     */
    @Override
    public void receiveCryptoData(EncryptionLevel level, long offset, ByteBuffer data)
            throws StreamReassembler.BufferLimitExceededException {
        if (asyncOffload.isBusy()) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            pendingFrames.add(new PendingFrame(level, offset, copy));
            return;
        }
        dispatchFrame(level, offset, data);
    }

    // Returns whether this actually submitted a new batch -- false if the
    // reassembled data didn't yet complete a message, in which case
    // there's nothing in flight for this call.
    private boolean dispatchFrame(EncryptionLevel level, long offset, ByteBuffer data)
            throws StreamReassembler.BufferLimitExceededException {
        final List<ByteBuffer> messages = bufferFor(level).receiveAndExtractMessages(offset, data);
        if (messages.isEmpty()) {
            return false;
        }
        asyncOffload.submit(level, new QuicHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() throws TlsProtocolException, IOException {
                for (ByteBuffer msg : messages) {
                    messageParser.parseAndProcessHandshakeMessage(msg, engine, level.getProtectionKeysType());
                }
            }
        }, new QuicHandshakeAsyncOffload.CompletionHandler() {
            @Override
            public boolean onBatchDone() {
                return drainPendingFrames();
            }
        });
        return true;
    }

    // Called once a batch completes: dispatches queued CRYPTO frames, one
    // at a time, until either the queue empties or one of them actually
    // submits a follow-up batch. Returns whether a follow-up batch is in
    // flight -- QuicHandshakeAsyncOffload.submit's caller-visible busy
    // state must stay set across it (issue #351), so this reports that
    // precisely rather than the caller re-querying isBusy() (the exact
    // flag this method's own follow-up submission is about to set).
    private boolean drainPendingFrames() {
        PendingFrame next;
        while ((next = pendingFrames.poll()) != null) {
            try {
                if (dispatchFrame(next.level, next.offset, ByteBuffer.wrap(next.data))) {
                    return true;
                }
            } catch (StreamReassembler.BufferLimitExceededException e) {
                listener.cryptoProcessingFailed(next.level, e);
                pendingFrames.clear();
                return false;
            }
        }
        return false;
    }

    private static final class PendingFrame {
        final EncryptionLevel level;
        final long offset;
        final byte[] data;

        PendingFrame(EncryptionLevel level, long offset, byte[] data) {
            this.level = level;
            this.offset = offset;
            this.data = data;
        }
    }

    private CryptoStreamBuffer bufferFor(EncryptionLevel level) {
        switch (level) {
            case INITIAL:
                return initialReceiveBuffer;
            case HANDSHAKE:
                return handshakeReceiveBuffer;
            default:
                return applicationReceiveBuffer;
        }
    }

    /**
     * Whether a batch of handshake message processing is currently
     * running off the caller's thread. Test-harness synchronization only
     * (e.g. a hand-scripted, no-socket peer that needs to know when it is
     * safe to read state a just-submitted batch's deferred callbacks
     * will populate); production code has no need to poll this, since
     * {@link QuicTlsEngineListener} callbacks already arrive back on the
     * loop thread in order.
     *
     * @return true if a batch is in flight
     */
    public boolean isHandshakeProcessingBusy() {
        return asyncOffload.isBusy();
    }

    /**
     * Returns whether this side's TLS handshake has finished.
     *
     * @return true once {@link TlsStatusEventHandler#handshakeFinished}
     *         has fired
     */
    public boolean isTlsHandshakeFinished() {
        return engine.handshakeFinished();
    }

    /**
     * Returns the negotiated cipher suite, valid once the ServerHello
     * has been processed.
     *
     * @return the negotiated cipher suite
     */
    public TlsConstants.CipherSuite getSelectedCipher() {
        return engine.getSelectedCipher();
    }

    /**
     * Returns the server's certificate chain, valid once received.
     *
     * @return the server certificate chain
     */
    public List<X509Certificate> getServerCertificateChain() {
        return engine.getServerCertificateChain();
    }

    /**
     * Returns the client Handshake traffic secret (RFC 9001 section 4.1).
     *
     * @return the client Handshake traffic secret
     */
    @Override
    public byte[] getClientHandshakeTrafficSecret() {
        return engine.getClientHandshakeTrafficSecret();
    }

    /**
     * Returns the server Handshake traffic secret (RFC 9001 section 4.1).
     *
     * @return the server Handshake traffic secret
     */
    @Override
    public byte[] getServerHandshakeTrafficSecret() {
        return engine.getServerHandshakeTrafficSecret();
    }

    /**
     * Returns the client 1-RTT (Application) traffic secret.
     *
     * @return the client Application traffic secret
     */
    @Override
    public byte[] getClientApplicationTrafficSecret() {
        return engine.getClientApplicationTrafficSecret();
    }

    /**
     * Returns the server 1-RTT (Application) traffic secret.
     *
     * @return the server Application traffic secret
     */
    @Override
    public byte[] getServerApplicationTrafficSecret() {
        return engine.getServerApplicationTrafficSecret();
    }

    /**
     * Returns the client early (0-RTT) traffic secret.
     *
     * @return the client early traffic secret
     */
    @Override
    public byte[] getClientEarlyTrafficSecret() {
        return engine.getClientEarlyTrafficSecret();
    }

    /**
     * Presents a previously received session ticket, attempting PSK
     * resumption on the next {@link #startHandshake}. If the ticket
     * itself advertises early-data support ({@link
     * NewSessionTicket#hasEarlyDataExtension()}), also adds an {@link
     * EarlyDataExtension} to the ClientHello -- RFC 8446 section 4.2.10:
     * presenting a resumable ticket and requesting 0-RTT are separate,
     * independent signals; a server never calls {@code
     * isEarlyDataAccepted()} at all unless this extension is present,
     * regardless of PSK resumption succeeding. Must be called before
     * {@link #startHandshake}.
     *
     * @param ticket the session ticket to present
     */
    public void presentSessionTicket(NewSessionTicket ticket) {
        this.earlyDataCipher = ticket.getCipher();
        engine.setNewSessionTicket(ticket);
        if (ticket.hasEarlyDataExtension()) {
            engine.add(new EarlyDataExtension());
        }
    }

    /**
     * Returns the cipher suite of the session ticket presented via
     * {@link #presentSessionTicket}, or null if none was presented.
     * Needed to derive 0-RTT keys in {@link #earlySecretsKnown()}, which
     * fires before {@code engine.getSelectedCipher()} is populated.
     *
     * @return the presented ticket's cipher suite, or null
     */
    public TlsConstants.CipherSuite getEarlyDataCipher() {
        return earlyDataCipher;
    }

    // ── ClientMessageSender ──

    @Override
    public void send(ClientHello clientHello) throws IOException {
        sendAtInitialLevel(clientHello.getBytes());
    }

    @Override
    public void send(FinishedMessage finishedMessage) throws IOException {
        // RFC 9001 section 4.1.3: the client's Finished message (and any
        // preceding client Certificate/CertificateVerify) is sent under
        // Handshake keys, not 1-RTT keys.
        sendAtHandshakeLevel(finishedMessage.getBytes());
    }

    @Override
    public void send(CertificateMessage certificateMessage) throws IOException {
        sendAtHandshakeLevel(certificateMessage.getBytes());
    }

    @Override
    public void send(CertificateVerifyMessage certificateVerifyMessage) {
        sendAtHandshakeLevel(certificateVerifyMessage.getBytes());
    }

    private void sendAtInitialLevel(byte[] data) {
        final long offset = initialSendOffset;
        initialSendOffset += data.length;
        final byte[] finalData = data;
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.cryptoDataReady(EncryptionLevel.INITIAL, offset, finalData);
            }
        });
    }

    private void sendAtHandshakeLevel(byte[] data) {
        final long offset = handshakeSendOffset;
        handshakeSendOffset += data.length;
        final byte[] finalData = data;
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.cryptoDataReady(EncryptionLevel.HANDSHAKE, offset, finalData);
            }
        });
    }

    // ── TlsStatusEventHandler ──

    @Override
    public void earlySecretsKnown() {
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.earlySecretsAvailable();
            }
        });
    }

    @Override
    public void handshakeSecretsKnown() {
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.handshakeSecretsAvailable();
            }
        });
    }

    @Override
    public void handshakeFinished() {
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.handshakeFinished();
            }
        });
    }

    @Override
    public void newSessionTicketReceived(final NewSessionTicket ticket) {
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.newSessionTicketReceived(ticket);
            }
        });
    }

    @Override
    public void extensionsReceived(List<Extension> extensions) throws TlsProtocolException {
        // RFC 9000 section 7.3 requires this extension to be present;
        // that is not enforced yet, so a missing extension is silently
        // ignored rather than closing the connection.
        final TransportParameters transportParameters = QuicTransportParametersExtension.find(extensions);
        if (transportParameters != null) {
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.transportParametersReceived(transportParameters);
                }
            });
        }
        // RFC 9001 section 4.6.1 / RFC 8446 section 4.2.10: the server
        // includes an EarlyDataExtension in EncryptedExtensions only if
        // it accepted 0-RTT. Reported unconditionally, on every
        // handshake, regardless of whether 0-RTT was ever attempted on
        // this connection -- the listener (QuicConnection) is the one
        // that knows whether that's meaningful here.
        boolean earlyDataAccepted = false;
        for (Extension extension : extensions) {
            if (extension instanceof EarlyDataExtension) {
                earlyDataAccepted = true;
                break;
            }
        }
        final boolean finalEarlyDataAccepted = earlyDataAccepted;
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.earlyDataOutcomeKnown(finalEarlyDataAccepted);
            }
        });
    }

    @Override
    public boolean isEarlyDataAccepted() {
        return false;
    }
}
