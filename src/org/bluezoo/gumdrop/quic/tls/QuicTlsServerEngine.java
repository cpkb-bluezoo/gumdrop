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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import tech.kwik.agent15.NewSessionTicket;
import tech.kwik.agent15.TlsConstants;
import tech.kwik.agent15.TlsProtocolException;
import tech.kwik.agent15.engine.ServerMessageSender;
import tech.kwik.agent15.engine.TlsMessageParser;
import tech.kwik.agent15.engine.TlsServerEngine;
import tech.kwik.agent15.engine.TlsServerEngineFactory;
import tech.kwik.agent15.engine.TlsStatusEventHandler;
import tech.kwik.agent15.extension.ApplicationLayerProtocolNegotiationExtension;
import tech.kwik.agent15.extension.Extension;
import tech.kwik.agent15.handshake.CertificateMessage;
import tech.kwik.agent15.handshake.CertificateVerifyMessage;
import tech.kwik.agent15.handshake.EncryptedExtensions;
import tech.kwik.agent15.handshake.FinishedMessage;
import tech.kwik.agent15.handshake.NewSessionTicketMessage;
import tech.kwik.agent15.handshake.ServerHello;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

/**
 * Bridges Agent15's {@link TlsServerEngine} to gumdrop's QUIC transport,
 * the server-side counterpart of {@link QuicTlsClientEngine}. See that
 * class's documentation for the scope of this implementation's minimal
 * ALPN (RFC 7301) support.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsClientEngine
 */
public final class QuicTlsServerEngine
        implements ServerMessageSender, TlsStatusEventHandler, QuicTlsEngine {

    private final TlsServerEngine engine;
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
    private long applicationSendOffset;

    // RFC 9001 section 4.6.1: whether this listener/connection is willing
    // to accept 0-RTT at all -- consulted (and its outcome cached) in
    // isEarlyDataAccepted(), called by Agent15 only after PSK resumption
    // has already succeeded and the client asked for early data.
    private final boolean earlyDataEnabled;
    private boolean earlyDataAccepted;
    private final List<String> supportedApplicationProtocols;

    /**
     * Creates a server-side TLS engine that offers no ALPN application
     * protocols.
     *
     * @param certificateFactory factory holding the server's certificate
     *                           chain and private key
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in EncryptedExtensions
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param earlyDataEnabled whether to accept 0-RTT early data when a
     *                         client offers it (RFC 9001 section 4.6.1)
     */
    public QuicTlsServerEngine(TlsServerEngineFactory certificateFactory,
            TransportParameters transportParameters, QuicTlsEngineListener listener,
            boolean earlyDataEnabled) {
        this(certificateFactory, transportParameters, listener, earlyDataEnabled, null);
    }

    /**
     * Creates a server-side TLS engine with no cipher-suite preference
     * (offers gumdrop's full default list).
     *
     * @param certificateFactory factory holding the server's certificate
     *                           chain and private key
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in EncryptedExtensions
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param earlyDataEnabled whether to accept 0-RTT early data when a
     *                         client offers it (RFC 9001 section 4.6.1)
     * @param applicationProtocols the ALPN application protocol(s) this
     *                             server supports (RFC 7301),
     *                             comma-separated, or null to support none
     */
    public QuicTlsServerEngine(TlsServerEngineFactory certificateFactory,
            TransportParameters transportParameters, QuicTlsEngineListener listener,
            boolean earlyDataEnabled, String applicationProtocols) {
        this(certificateFactory, transportParameters, listener, earlyDataEnabled, applicationProtocols, null);
    }

    /**
     * Creates a server-side TLS engine.
     *
     * @param certificateFactory factory holding the server's certificate
     *                           chain and private key
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in EncryptedExtensions
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     * @param earlyDataEnabled whether to accept 0-RTT early data when a
     *                         client offers it (RFC 9001 section 4.6.1)
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
    public QuicTlsServerEngine(TlsServerEngineFactory certificateFactory,
            TransportParameters transportParameters, QuicTlsEngineListener listener,
            boolean earlyDataEnabled, String applicationProtocols, String cipherSuites) {
        this.listener = listener;
        this.asyncOffload = new QuicHandshakeAsyncOffload(listener);
        this.earlyDataEnabled = earlyDataEnabled;
        this.supportedApplicationProtocols = applicationProtocols != null && !applicationProtocols.isEmpty()
                ? java.util.Arrays.asList(applicationProtocols.split(","))
                : java.util.Collections.<String>emptyList();
        this.engine = certificateFactory.createServerEngine(this, this);

        engine.addSupportedCiphers(QuicCipherSuites.resolve(cipherSuites));
        engine.addServerExtensions(new QuicTransportParametersExtension(transportParameters));
    }

    /**
     * Feeds received CRYPTO frame data at the given level into
     * handshake message reassembly. Complete messages are dispatched to
     * Agent15 asynchronously, off the caller's thread, via {@link
     * QuicHandshakeAsyncOffload}; a processing failure reaches {@link
     * QuicTlsEngineListener#cryptoProcessingFailed} rather than being
     * thrown back through this call. The ClientHello, received at
     * {@link EncryptionLevel#INITIAL}, starts the server's handshake
     * processing and its own reply.
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

    private void dispatchFrame(EncryptionLevel level, long offset, ByteBuffer data)
            throws StreamReassembler.BufferLimitExceededException {
        final List<ByteBuffer> messages = bufferFor(level).receiveAndExtractMessages(offset, data);
        if (messages.isEmpty()) {
            return;
        }
        asyncOffload.submit(level, new QuicHandshakeAsyncOffload.BatchProcessor() {
            @Override
            public void process() throws TlsProtocolException, IOException {
                for (ByteBuffer msg : messages) {
                    messageParser.parseAndProcessHandshakeMessage(msg, engine, level.getProtectionKeysType());
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                drainPendingFrames();
            }
        });
    }

    // Called once a batch completes, on the loop thread: dispatches
    // whatever CRYPTO frames queued up while it was running, one at a
    // time, until either the queue empties or a new batch is submitted
    // (whose own completion will call back here again).
    private void drainPendingFrames() {
        while (!asyncOffload.isBusy()) {
            PendingFrame next = pendingFrames.poll();
            if (next == null) {
                return;
            }
            try {
                dispatchFrame(next.level, next.offset, ByteBuffer.wrap(next.data));
            } catch (StreamReassembler.BufferLimitExceededException e) {
                listener.cryptoProcessingFailed(next.level, e);
                pendingFrames.clear();
                return;
            }
        }
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
     * Returns the negotiated cipher suite, valid once the ClientHello
     * has been processed.
     *
     * @return the negotiated cipher suite
     */
    public TlsConstants.CipherSuite getSelectedCipher() {
        return engine.getSelectedCipher();
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

    // ── ServerMessageSender ──

    @Override
    public void send(ServerHello sh) throws IOException {
        sendAtInitialLevel(sh.getBytes());
    }

    @Override
    public void send(EncryptedExtensions ee) throws IOException {
        sendAtHandshakeLevel(ee.getBytes());
    }

    @Override
    public void send(CertificateMessage cm) throws IOException {
        sendAtHandshakeLevel(cm.getBytes());
    }

    @Override
    public void send(CertificateVerifyMessage cv) throws IOException {
        sendAtHandshakeLevel(cv.getBytes());
    }

    @Override
    public void send(FinishedMessage finished) throws IOException {
        sendAtHandshakeLevel(finished.getBytes());
    }

    @Override
    public void send(NewSessionTicketMessage ticket) throws IOException {
        // RFC 9001 section 4.6: post-handshake messages are sent at 1-RTT.
        final byte[] data = ticket.getBytes();
        final long offset = applicationSendOffset;
        applicationSendOffset += data.length;
        asyncOffload.dispatch(new Runnable() {
            @Override
            public void run() {
                listener.cryptoDataReady(EncryptionLevel.ONE_RTT, offset, data);
            }
        });
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
    public void newSessionTicketReceived(NewSessionTicket ticket) {
        // Not applicable server-side; NewSessionTicket is sent, not received, here.
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
        // RFC 7301: pick the first client-offered protocol this server
        // also supports. No match (or nothing configured either side)
        // just leaves the selected protocol unset -- this minimal
        // implementation doesn't enforce RFC 7301's negotiation-failure
        // closing behaviour (see the class documentation). Selecting the
        // protocol on the engine itself is not a listener callback, so
        // it runs immediately rather than through asyncOffload.
        for (Extension extension : extensions) {
            if (extension instanceof ApplicationLayerProtocolNegotiationExtension) {
                for (String offered : ((ApplicationLayerProtocolNegotiationExtension) extension).getProtocols()) {
                    if (supportedApplicationProtocols.contains(offered)) {
                        engine.setSelectedApplicationLayerProtocol(offered);
                        break;
                    }
                }
                break;
            }
        }
    }

    @Override
    public boolean isEarlyDataAccepted() {
        earlyDataAccepted = earlyDataEnabled;
        return earlyDataAccepted;
    }

    /**
     * Returns whether 0-RTT early data was accepted for this connection.
     * Only meaningful after {@link #isEarlyDataAccepted()} has been
     * called by Agent15 (i.e. once a client's PSK resumption attempt
     * that also requested early data has been processed) -- false
     * beforehand, and false if the client never attempted 0-RTT at all.
     *
     * @return whether early data was accepted
     */
    public boolean wasEarlyDataAccepted() {
        return earlyDataAccepted;
    }
}
