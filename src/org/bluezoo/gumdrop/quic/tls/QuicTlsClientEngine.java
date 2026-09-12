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

import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeEngine;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.SessionTicket;
import org.bluezoo.gumdrop.tls.TlsEventSink;
import org.bluezoo.gumdrop.tls.TlsProtocolError;

/**
 * Bridges gumdrop's in-tree {@link HandshakeEngine} to the QUIC
 * transport: routes handshake message bytes to and from per-level
 * {@link CryptoStreamBuffer}s, and forwards secret-availability and
 * completion events to a {@link QuicTlsEngineListener}. Replaces the
 * former Agent15-backed implementation; the {@link QuicTlsEngine}/
 * {@link QuicTlsEngineListener} seam this class sits behind, and every
 * other public method on this class, are unchanged -- only what drives
 * the handshake underneath.
 *
 * <p>The QUIC transport-parameters extension (RFC 9000 section 7.4,
 * RFC 9001 section 8.2) is added to the handshake and its receipt is
 * surfaced via {@link QuicTlsEngineListener#transportParametersReceived};
 * validating it against RFC 9000 section 7.3's requirements (e.g. that
 * it MUST be present, that original_destination_connection_id MUST
 * match) is not done yet.
 *
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsServerEngine
 */
public final class QuicTlsClientEngine implements QuicTlsEngine {

    private static final Logger LOGGER = Logger.getLogger(QuicTlsClientEngine.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.quic.L10N");

    private final HandshakeEngine engine;
    private final HandshakeConfig config;
    private final QuicTlsEngineListener listener;
    private final QuicHandshakeAsyncOffload asyncOffload;
    private final Sink sink = new Sink();

    private final CryptoStreamBuffer initialReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer handshakeReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer applicationReceiveBuffer = new CryptoStreamBuffer();

    // Raw CRYPTO frame bytes received while a batch of handshake message
    // processing is already in flight on a crypto thread -- HandshakeEngine
    // is not safe for concurrent use, so these wait, in arrival order,
    // until the in-flight batch completes (see #drainPendingFrames).
    private final Deque<PendingFrame> pendingFrames = new ArrayDeque<PendingFrame>();

    private long initialSendOffset;
    private long handshakeSendOffset;

    // The first message this engine ever emits (ClientHello) goes at
    // EncryptionLevel.INITIAL (RFC 9001 section 4.1.3); every message
    // after that (this engine's own Finished) goes at
    // EncryptionLevel.HANDSHAKE. HandshakeEngine's events don't carry a
    // level of their own, so this flag tracks which regime the next
    // outbound message falls into.
    private boolean clientHelloSent;

    // Set from TlsEventSink#quicEarlyKeysReady, when a presented session
    // ticket's 0-RTT is actually attempted -- the suite half backs
    // #getEarlyDataCipher, the secret half QuicTlsEngine#getClientEarlyTrafficSecret.
    private CipherSuite earlyDataCipherSuite;
    private byte[] clientEarlyTrafficSecret;

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
     *                    already documents), or null for this engine's
     *                    own default order (hybrid PQC group first).
     *                    Unrecognised names are skipped with a logged
     *                    warning rather than silently substituted or
     *                    failing the connection.
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
     * @param namedGroups colon-separated preferred named group(s), or
     *                    null for this engine's own default order
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

        this.config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setLocalTransportParameters(transportParameters.encode());
        config.setCipherSuites(QuicCipherSuites.resolve(cipherSuites));
        config.setApplicationProtocols(applicationProtocols != null && !applicationProtocols.isEmpty()
                ? Arrays.asList(applicationProtocols.split(","))
                : Collections.<String>emptyList());
        List<NamedGroup> resolvedGroups = resolveNamedGroups(namedGroups);
        if (resolvedGroups != null) {
            config.setNamedGroups(resolvedGroups);
        }
        this.engine = new HandshakeEngine(config);
    }

    private static List<NamedGroup> resolveNamedGroups(String namedGroups) {
        if (namedGroups == null || namedGroups.isEmpty()) {
            return null;
        }
        List<NamedGroup> resolved = new ArrayList<NamedGroup>();
        for (String name : namedGroups.split(":")) {
            name = name.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                resolved.add(NamedGroup.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Tried in order; not every name is necessarily unsupported.
            }
        }
        if (resolved.isEmpty()) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                String message = MessageFormat.format(
                        L10N.getString("warn.named_groups_fallback"), namedGroups);
                LOGGER.warning(message);
            }
            return null;
        }
        return resolved;
    }

    /**
     * Sets a custom trust manager for verifying the server certificate.
     * If not called, the platform default trust store is used.
     *
     * @param trustManager the trust manager
     */
    public void setTrustManager(X509TrustManager trustManager) {
        config.setTrustManager(trustManager);
    }

    /**
     * Disables hostname verification of the peer's certificate against
     * the server name presented in the handshake. See
     * {@link HandshakeConfig#setVerifyHostname} for the full rationale
     * (unchanged from the previous Agent15-backed implementation): a
     * caller with no real hostname to offer (e.g. a DNS-over-QUIC client
     * connecting directly to a resolved IP, RFC 9250) should disable this
     * and establish trust another way instead (a pinned certificate
     * fingerprint or a private CA via {@link #setTrustManager}).
     *
     * <p>Not called at all (the default) leaves hostname verification on.
     *
     * @param verify false to accept any hostname/certificate pairing
     */
    public void setVerifyHostname(boolean verify) {
        config.setVerifyHostname(verify);
    }

    /**
     * Starts the TLS handshake, producing a ClientHello via
     * {@link QuicTlsEngineListener#cryptoDataReady} at
     * {@link EncryptionLevel#INITIAL}.
     *
     * @param serverName the SNI server name
     */
    public void startHandshake(String serverName) {
        config.setServerName(serverName);
        engine.start(sink);
    }

    /**
     * Feeds received CRYPTO frame data at the given level into
     * handshake message reassembly. Complete messages are dispatched to
     * {@link HandshakeEngine} asynchronously, off the caller's thread,
     * via {@link QuicHandshakeAsyncOffload}; a processing failure
     * reaches {@link QuicTlsEngineListener#cryptoProcessingFailed}
     * rather than being thrown back through this call.
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
        synchronized (asyncOffload.lock()) {
            if (asyncOffload.isBusy()) {
                byte[] copy = new byte[data.remaining()];
                data.get(copy);
                pendingFrames.add(new PendingFrame(level, offset, copy));
                return;
            }
            dispatchFrame(level, offset, data);
        }
    }

    private boolean dispatchFrame(final EncryptionLevel level, long offset, ByteBuffer data)
            throws StreamReassembler.BufferLimitExceededException {
        final List<ByteBuffer> messages = bufferFor(level).receiveAndExtractMessages(offset, data);
        if (messages.isEmpty()) {
            return false;
        }
        asyncOffload.submit(level, new QuicHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() {
                for (ByteBuffer msg : messages) {
                    byte[] message = new byte[msg.remaining()];
                    msg.get(message);
                    engine.processMessage(message, sink);
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
     * running off the caller's thread. Test-harness synchronization only;
     * production code has no need to poll this, since
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
     * @return true once application traffic secrets are available
     */
    public boolean isTlsHandshakeFinished() {
        return engine.isComplete();
    }

    /**
     * Returns the negotiated cipher suite, valid once the ServerHello
     * has been processed.
     *
     * @return the negotiated cipher suite
     */
    public CipherSuite getSelectedCipher() {
        return engine.getNegotiatedCipherSuite();
    }

    /**
     * Returns the server's certificate chain, valid once received and verified.
     *
     * @return the server certificate chain
     */
    public List<X509Certificate> getServerCertificateChain() {
        return engine.getPeerCertificateChain();
    }

    @Override
    public byte[] getClientHandshakeTrafficSecret() {
        return engine.getClientHandshakeTrafficSecret();
    }

    @Override
    public byte[] getServerHandshakeTrafficSecret() {
        return engine.getServerHandshakeTrafficSecret();
    }

    @Override
    public byte[] getClientApplicationTrafficSecret() {
        return engine.getClientApplicationTrafficSecret();
    }

    @Override
    public byte[] getServerApplicationTrafficSecret() {
        return engine.getServerApplicationTrafficSecret();
    }

    /**
     * Returns the client early (0-RTT) traffic secret, once
     * {@link QuicTlsEngineListener#earlySecretsAvailable} has fired.
     *
     * @return the client early traffic secret, or null if 0-RTT was never attempted
     */
    @Override
    public byte[] getClientEarlyTrafficSecret() {
        return clientEarlyTrafficSecret;
    }

    /**
     * Presents a session ticket to attempt PSK resumption -- and, if the
     * ticket allows it, 0-RTT -- on the handshake this engine is about to
     * run. Must be called before {@link #startHandshake}.
     *
     * @param ticket the session ticket, from a prior connection's
     *               {@link QuicTlsEngineListener#newSessionTicketReceived}
     */
    public void presentSessionTicket(SessionTicket ticket) {
        config.setSessionTicket(ticket);
        config.setEnableEarlyData(true);
    }

    /**
     * Returns the cipher suite 0-RTT data is protected under, once
     * {@link QuicTlsEngineListener#earlySecretsAvailable} has fired.
     *
     * @return the early data cipher suite, or null if 0-RTT was never attempted
     */
    public CipherSuite getEarlyDataCipher() {
        return earlyDataCipherSuite;
    }

    /** Translates {@link HandshakeEngine} events into {@link QuicTlsEngineListener} calls. */
    private final class Sink implements TlsEventSink {

        @Override
        public void handshakeDataReady(byte[] data) {
            if (!clientHelloSent) {
                clientHelloSent = true;
                sendAtInitialLevel(data);
            } else {
                sendAtHandshakeLevel(data);
            }
        }

        @Override
        public void handshakeSecretsReady() {
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.handshakeSecretsAvailable();
                }
            });
        }

        @Override
        public void applicationSecretsReady() {
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.handshakeFinished();
                }
            });
        }

        @Override
        public void peerTransportParameters(byte[] parameters) {
            final TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(parameters));
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.transportParametersReceived(decoded);
                }
            });
        }

        @Override
        public void protocolError(final TlsProtocolError error) {
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.cryptoProcessingFailed(EncryptionLevel.HANDSHAKE,
                            new java.io.IOException(error.toString()));
                }
            });
        }

        @Override
        public void quicEarlyKeysReady(CipherSuite suite, byte[] secret) {
            earlyDataCipherSuite = suite;
            clientEarlyTrafficSecret = secret;
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.earlySecretsAvailable();
                }
            });
        }

        @Override
        public void earlyDataAccepted(final boolean accepted) {
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.earlyDataOutcomeKnown(accepted);
                }
            });
        }

        @Override
        public void sessionTicketReceived(final SessionTicket ticket) {
            asyncOffload.dispatch(new Runnable() {
                @Override
                public void run() {
                    listener.newSessionTicketReceived(ticket);
                }
            });
        }
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

}
