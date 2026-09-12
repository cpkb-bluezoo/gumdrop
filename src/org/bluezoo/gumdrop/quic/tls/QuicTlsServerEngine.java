/*
 * QuicTlsServerEngine.java
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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.tls.AntiReplay;
import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.ClientAuthPolicy;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeEngine;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.ServerCredentialsResolver;
import org.bluezoo.gumdrop.tls.TicketKeys;
import org.bluezoo.gumdrop.tls.TlsEventSink;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TransportParameterConsistencyChecker;

/**
 * Bridges gumdrop's in-tree {@link HandshakeEngine} to the QUIC
 * transport, the server-side counterpart of {@link QuicTlsClientEngine}.
 *
 * <p>{@code earlyDataEnabled} governs whether 0-RTT is accepted at all;
 * ticket issuance (required for a peer to ever have something to resume)
 * is a separate, optional opt-in via {@link #setTicketKeys}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsClientEngine
 */
public final class QuicTlsServerEngine implements QuicTlsEngine {

    private final HandshakeEngine engine;
    private final HandshakeConfig config;
    private final QuicTlsEngineListener listener;
    private final QuicHandshakeAsyncOffload asyncOffload;
    private final Sink sink = new Sink();
    private final QuicTlsDeferredDispatch deferredDispatch;

    private List<ByteBuffer> activeMessages;

    private final QuicHandshakeAsyncOffload.BatchProcessor messageBatchProcessor =
            new QuicHandshakeAsyncOffload.BatchProcessor() {
                @Override
                public void process() {
                    for (int i = 0; i < activeMessages.size(); i++) {
                        ByteBuffer msg = activeMessages.get(i);
                        byte[] message = new byte[msg.remaining()];
                        msg.get(message);
                        deferredDispatch.resetSlots();
                        engine.processMessage(message, sink);
                    }
                }
            };
    private final QuicHandshakeAsyncOffload.CompletionHandler drainCompletionHandler =
            new QuicHandshakeAsyncOffload.CompletionHandler() {
                @Override
                public boolean onBatchDone() {
                    return drainPendingFrames();
                }
            };

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

    // The first message this engine ever emits (ServerHello) goes at
    // EncryptionLevel.INITIAL (RFC 9001 section 4.1.3); every message
    // after that (EncryptedExtensions, Certificate, CertificateVerify,
    // Finished) goes at EncryptionLevel.HANDSHAKE. HandshakeEngine's
    // events don't carry a level of their own, so this flag tracks which
    // regime the next outbound message falls into.
    private boolean serverHelloSent;

    // Set from TlsEventSink#quicEarlyKeysReady when the client's offered
    // PSK is accepted with 0-RTT.
    private byte[] clientEarlyTrafficSecret;

    /**
     * Creates a server-side TLS engine that offers no ALPN application
     * protocols.
     *
     * @param serverCredentials the server's certificate chain and private key
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in EncryptedExtensions
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param earlyDataEnabled whether 0-RTT is accepted -- see
     *                         {@link #setTicketKeys} for the other half
     *                         resumption needs (a ticket to resume)
     */
    public QuicTlsServerEngine(ServerCredentials serverCredentials,
            TransportParameters transportParameters, QuicTlsEngineListener listener,
            boolean earlyDataEnabled) {
        this(serverCredentials, transportParameters, listener, earlyDataEnabled, null);
    }

    /**
     * Creates a server-side TLS engine with no cipher-suite preference
     * (offers gumdrop's full default list).
     *
     * @param serverCredentials the server's certificate chain and private key
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in EncryptedExtensions
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param earlyDataEnabled whether 0-RTT is accepted -- see {@link #setTicketKeys}
     *                         for the other half resumption needs
     * @param applicationProtocols the ALPN application protocol(s) this
     *                             server supports (RFC 7301),
     *                             comma-separated, or null to support none
     */
    public QuicTlsServerEngine(ServerCredentials serverCredentials,
            TransportParameters transportParameters, QuicTlsEngineListener listener,
            boolean earlyDataEnabled, String applicationProtocols) {
        this(serverCredentials, transportParameters, listener, earlyDataEnabled, applicationProtocols, null);
    }

    /**
     * Creates a server-side TLS engine.
     *
     * @param serverCredentials the server's certificate chain and private key
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in EncryptedExtensions
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param earlyDataEnabled whether 0-RTT is accepted -- see {@link #setTicketKeys}
     *                         for the other half resumption needs
     * @param applicationProtocols the ALPN application protocol(s) this
     *                             server supports (RFC 7301),
     *                             comma-separated, or null to support none
     * @param cipherSuites colon-separated preferred cipher suite(s) in
     *                     IANA form (e.g. {@code
     *                     "TLS_CHACHA20_POLY1305_SHA256"}, matching
     *                     {@code TransportFactory#setCipherSuites}'s
     *                     javadoc), or null to accept gumdrop's full
     *                     default list. Only names gumdrop's own AEAD
     *                     layer actually implements are accepted -- see
     *                     {@link QuicCipherSuites#resolve}.
     */
    public QuicTlsServerEngine(ServerCredentials serverCredentials,
            TransportParameters transportParameters, QuicTlsEngineListener listener,
            boolean earlyDataEnabled, String applicationProtocols, String cipherSuites) {
        this(serverCredentials, null, transportParameters, listener, earlyDataEnabled,
                applicationProtocols, cipherSuites, false, null);
    }

    /**
     * Creates a server-side TLS engine with optional SNI credential
     * selection and client certificate authentication.
     *
     * @param serverCredentials the default server credentials, or null if
     *                            {@code serverCredentialsResolver} selects them
     * @param serverCredentialsResolver resolves credentials from the client's
     *                                    SNI hostname, or null
     * @param transportParameters this endpoint's QUIC transport parameters
     * @param listener notified of handshake progress
     * @param earlyDataEnabled whether 0-RTT is accepted
     * @param applicationProtocols ALPN application protocol(s), or null
     * @param cipherSuites colon-separated preferred cipher suite(s), or null
     * @param requireClientAuth whether client certificates are required
     * @param clientTrustManager trust manager for validating client certs
     */
    public QuicTlsServerEngine(ServerCredentials serverCredentials,
            ServerCredentialsResolver serverCredentialsResolver,
            TransportParameters transportParameters, QuicTlsEngineListener listener,
            boolean earlyDataEnabled, String applicationProtocols, String cipherSuites,
            boolean requireClientAuth, X509TrustManager clientTrustManager) {
        this.listener = listener;
        this.asyncOffload = new QuicHandshakeAsyncOffload(listener);
        this.deferredDispatch = new QuicTlsDeferredDispatch(asyncOffload, sink);
        this.config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(serverCredentials);
        config.setServerCredentialsResolver(serverCredentialsResolver);
        if (requireClientAuth) {
            config.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            config.setClientTrustManager(clientTrustManager);
        }
        config.setLocalTransportParameters(transportParameters.encode());
        config.setCipherSuites(QuicCipherSuites.resolve(cipherSuites));
        config.setApplicationProtocols(applicationProtocols != null && !applicationProtocols.isEmpty()
                ? Arrays.asList(applicationProtocols.split(","))
                : Collections.<String>emptyList());
        config.setEnableEarlyData(earlyDataEnabled);
        config.setTransportParameterConsistencyChecker(new TransportParameterConsistencyChecker() {
            @Override
            public boolean isConsistent(byte[] remembered, byte[] current) {
                if (remembered == null || current == null) {
                    return false;
                }
                TransportParameters rememberedParams = TransportParameters.decode(ByteBuffer.wrap(remembered));
                TransportParameters currentParams = TransportParameters.decode(ByteBuffer.wrap(current));
                return rememberedParams.getInitialMaxData() <= currentParams.getInitialMaxData()
                        && rememberedParams.getInitialMaxStreamsBidi() <= currentParams.getInitialMaxStreamsBidi()
                        && rememberedParams.getInitialMaxStreamsUni() <= currentParams.getInitialMaxStreamsUni()
                        && rememberedParams.getInitialMaxStreamDataBidiLocal() <= currentParams.getInitialMaxStreamDataBidiLocal()
                        && rememberedParams.getInitialMaxStreamDataBidiRemote() <= currentParams.getInitialMaxStreamDataBidiRemote()
                        && rememberedParams.getInitialMaxStreamDataUni() <= currentParams.getInitialMaxStreamDataUni()
                        && rememberedParams.getMaxDatagramFrameSize() <= currentParams.getMaxDatagramFrameSize();
            }
        });
        this.engine = new HandshakeEngine(config);
    }

    /**
     * Sets the server's session-ticket encryption keyring, enabling
     * automatic {@code NewSessionTicket} issuance on every completed
     * handshake -- without this, {@code earlyDataEnabled} alone is not
     * enough for a peer to ever have something to resume, since no
     * ticket is ever issued.
     *
     * @param ticketKeys the ticket keyring
     */
    public void setTicketKeys(TicketKeys ticketKeys) {
        config.setTicketKeys(ticketKeys);
    }

    /**
     * Sets the server's 0-RTT anti-replay cache.
     *
     * @param antiReplay the anti-replay cache, or null to accept every 0-RTT attempt
     */
    public void setAntiReplay(AntiReplay antiReplay) {
        config.setAntiReplay(antiReplay);
    }

    /**
     * Feeds received CRYPTO frame data at the given level into
     * handshake message reassembly. Complete messages are dispatched to
     * {@link HandshakeEngine} asynchronously, off the caller's thread,
     * via {@link QuicHandshakeAsyncOffload}; a processing failure
     * reaches {@link QuicTlsEngineListener#cryptoProcessingFailed}
     * rather than being thrown back through this call. The ClientHello,
     * received at {@link EncryptionLevel#INITIAL}, starts the server's
     * handshake processing and its own reply.
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
        activeMessages = messages;
        asyncOffload.submit(level, messageBatchProcessor, drainCompletionHandler);
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
     * Returns the negotiated cipher suite, valid once the ClientHello
     * has been processed.
     *
     * @return the negotiated cipher suite
     */
    public CipherSuite getSelectedCipher() {
        return engine.getNegotiatedCipherSuite();
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
     * Returns whether 0-RTT early data was accepted for this connection.
     *
     * @return true if early data was accepted
     */
    public boolean wasEarlyDataAccepted() {
        return engine.wasEarlyDataAccepted();
    }

    /** Translates {@link HandshakeEngine} events into {@link QuicTlsEngineListener} calls. */
    private final class Sink implements TlsEventSink, QuicTlsDeferredDispatch.Target {

        @Override
        public void handshakeDataReady(byte[] data) {
            if (!serverHelloSent) {
                serverHelloSent = true;
                sendAtInitialLevel(data);
            } else {
                sendAtHandshakeLevel(data);
            }
        }

        @Override
        public void handshakeSecretsReady() {
            deferredDispatch.handshakeSecretsAvailable();
        }

        @Override
        public void applicationSecretsReady() {
            deferredDispatch.handshakeFinished();
        }

        @Override
        public void peerTransportParameters(byte[] parameters) {
            deferredDispatch.transportParameters(TransportParameters.decode(ByteBuffer.wrap(parameters)));
        }

        @Override
        public void protocolError(TlsProtocolError error) {
            deferredDispatch.protocolError(EncryptionLevel.HANDSHAKE, error);
        }

        @Override
        public void quicEarlyKeysReady(CipherSuite suite, byte[] secret) {
            clientEarlyTrafficSecret = secret;
            deferredDispatch.earlySecretsAvailable();
        }

        @Override
        public void deliverCryptoData(EncryptionLevel level, long offset, byte[] data) {
            listener.cryptoDataReady(level, offset, data);
        }

        @Override
        public void deliverHandshakeSecretsAvailable() {
            listener.handshakeSecretsAvailable();
        }

        @Override
        public void deliverHandshakeFinished() {
            listener.handshakeFinished();
        }

        @Override
        public void deliverTransportParameters(TransportParameters parameters) {
            listener.transportParametersReceived(parameters);
        }

        @Override
        public void deliverCryptoProcessingFailed(EncryptionLevel level, Throwable cause) {
            listener.cryptoProcessingFailed(level, cause);
        }

        @Override
        public void deliverEarlySecretsAvailable() {
            listener.earlySecretsAvailable();
        }

        @Override
        public void deliverEarlyDataOutcomeKnown(boolean accepted) {
            listener.earlyDataOutcomeKnown(accepted);
        }

        @Override
        public void deliverNewSessionTicketReceived(org.bluezoo.gumdrop.tls.SessionTicket ticket) {
            listener.newSessionTicketReceived(ticket);
        }
    }

    private void sendAtInitialLevel(byte[] data) {
        long offset = initialSendOffset;
        initialSendOffset += data.length;
        deferredDispatch.initialCryptoData(offset, data);
    }

    private void sendAtHandshakeLevel(byte[] data) {
        long offset = handshakeSendOffset;
        handshakeSendOffset += data.length;
        deferredDispatch.handshakeCryptoData(offset, data);
    }

}
