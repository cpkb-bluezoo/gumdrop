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
import java.util.ArrayList;
import java.util.List;

import tech.kwik.agent15.NewSessionTicket;
import tech.kwik.agent15.TlsConstants;
import tech.kwik.agent15.TlsProtocolException;
import tech.kwik.agent15.engine.ServerMessageSender;
import tech.kwik.agent15.engine.TlsMessageParser;
import tech.kwik.agent15.engine.TlsServerEngine;
import tech.kwik.agent15.engine.TlsServerEngineFactory;
import tech.kwik.agent15.engine.TlsStatusEventHandler;
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
 * class's documentation for what this stage deliberately does not do
 * yet (ALPN).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicTlsClientEngine
 */
public final class QuicTlsServerEngine
        implements ServerMessageSender, TlsStatusEventHandler, QuicTlsEngine {

    private final TlsServerEngine engine;
    private final QuicTlsEngineListener listener;
    private final TlsMessageParser messageParser = new TlsMessageParser();

    private final CryptoStreamBuffer initialReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer handshakeReceiveBuffer = new CryptoStreamBuffer();
    private final CryptoStreamBuffer applicationReceiveBuffer = new CryptoStreamBuffer();

    private long initialSendOffset;
    private long handshakeSendOffset;
    private long applicationSendOffset;

    /**
     * Creates a server-side TLS engine.
     *
     * @param certificateFactory factory holding the server's certificate
     *                           chain and private key
     * @param transportParameters this endpoint's QUIC transport
     *                            parameters, sent in EncryptedExtensions
     *                            (RFC 9001 section 8.2)
     * @param listener notified of handshake progress
     */
    public QuicTlsServerEngine(TlsServerEngineFactory certificateFactory,
            TransportParameters transportParameters, QuicTlsEngineListener listener) {
        this.listener = listener;
        this.engine = certificateFactory.createServerEngine(this, this);

        List<TlsConstants.CipherSuite> ciphers = new ArrayList<TlsConstants.CipherSuite>();
        ciphers.add(TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256);
        ciphers.add(TlsConstants.CipherSuite.TLS_AES_256_GCM_SHA384);
        ciphers.add(TlsConstants.CipherSuite.TLS_CHACHA20_POLY1305_SHA256);
        engine.addSupportedCiphers(ciphers);
        engine.addServerExtensions(new QuicTransportParametersExtension(transportParameters));
    }

    /**
     * Feeds received CRYPTO frame data at the given level into
     * handshake message reassembly, dispatching complete messages to
     * Agent15 as they become available. The ClientHello, received at
     * {@link EncryptionLevel#INITIAL}, starts the server's handshake
     * processing and its own reply.
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
        byte[] data = ticket.getBytes();
        long offset = applicationSendOffset;
        applicationSendOffset += data.length;
        listener.cryptoDataReady(EncryptionLevel.ONE_RTT, offset, data);
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
        // Not applicable server-side; NewSessionTicket is sent, not received, here.
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
