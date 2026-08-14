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
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import tech.kwik.agent15.NewSessionTicket;
import tech.kwik.agent15.TlsConstants;
import tech.kwik.agent15.TlsProtocolException;
import tech.kwik.agent15.engine.ClientMessageSender;
import tech.kwik.agent15.engine.TlsClientEngine;
import tech.kwik.agent15.engine.TlsClientEngineFactory;
import tech.kwik.agent15.engine.TlsMessageParser;
import tech.kwik.agent15.engine.TlsStatusEventHandler;
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
 * match) is not done yet. ALPN is not added yet either.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsServerEngine
 */
public final class QuicTlsClientEngine
        implements ClientMessageSender, TlsStatusEventHandler, QuicTlsEngine {

    private final TlsClientEngine engine;
    private final QuicTlsEngineListener listener;
    private final TlsMessageParser messageParser = new TlsMessageParser();

    private final CryptoStreamBuffer initialReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer handshakeReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer applicationReceiveBuffer = new CryptoStreamBuffer();

    private long initialSendOffset;
    private long handshakeSendOffset;

    /**
     * Creates a client-side TLS engine.
     *
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in the ClientHello
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     */
    public QuicTlsClientEngine(TransportParameters transportParameters, QuicTlsEngineListener listener) {
        this.listener = listener;
        this.engine = TlsClientEngineFactory.createClientEngine(this, this);

        List<TlsConstants.CipherSuite> ciphers = new ArrayList<TlsConstants.CipherSuite>();
        ciphers.add(TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256);
        ciphers.add(TlsConstants.CipherSuite.TLS_AES_256_GCM_SHA384);
        ciphers.add(TlsConstants.CipherSuite.TLS_CHACHA20_POLY1305_SHA256);
        engine.addSupportedCiphers(ciphers);
        engine.add(new QuicTransportParametersExtension(transportParameters));
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
     * Starts the TLS handshake, producing a ClientHello via
     * {@link QuicTlsEngineListener#cryptoDataReady} at
     * {@link EncryptionLevel#INITIAL}.
     *
     * @param serverName the SNI server name
     * @throws IOException if the handshake cannot be started
     */
    public void startHandshake(String serverName) throws IOException {
        engine.setServerName(serverName);
        engine.startHandshake();
    }

    /**
     * Feeds received CRYPTO frame data at the given level into
     * handshake message reassembly, dispatching complete messages to
     * Agent15 as they become available.
     *
     * @param level the encryption level the data was received at
     * @param offset the byte offset of {@code data} within this level's
     *               CRYPTO stream
     * @param data the received handshake data
     * @throws TlsProtocolException if Agent15 rejects a handshake message
     * @throws IOException if Agent15 fails to process a handshake message
     */
    @Override
    public void receiveCryptoData(EncryptionLevel level, long offset, ByteBuffer data)
            throws TlsProtocolException, IOException {
        bufferFor(level).receive(offset, data, messageParser, engine, level);
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
        long offset = initialSendOffset;
        initialSendOffset += data.length;
        listener.cryptoDataReady(EncryptionLevel.INITIAL, offset, data);
    }

    private void sendAtHandshakeLevel(byte[] data) {
        long offset = handshakeSendOffset;
        handshakeSendOffset += data.length;
        listener.cryptoDataReady(EncryptionLevel.HANDSHAKE, offset, data);
    }

    // ── TlsStatusEventHandler ──

    @Override
    public void earlySecretsKnown() {
        // 0-RTT is not implemented yet.
    }

    @Override
    public void handshakeSecretsKnown() {
        listener.handshakeSecretsAvailable();
    }

    @Override
    public void handshakeFinished() {
        listener.handshakeFinished();
    }

    @Override
    public void newSessionTicketReceived(NewSessionTicket ticket) {
        // Session resumption is not implemented yet.
    }

    @Override
    public void extensionsReceived(List<Extension> extensions) throws TlsProtocolException {
        // RFC 9000 section 7.3 requires this extension to be present;
        // that is not enforced yet, so a missing extension is silently
        // ignored rather than closing the connection.
        TransportParameters transportParameters = QuicTransportParametersExtension.find(extensions);
        if (transportParameters != null) {
            listener.transportParametersReceived(transportParameters);
        }
    }

    @Override
    public boolean isEarlyDataAccepted() {
        return false;
    }
}
