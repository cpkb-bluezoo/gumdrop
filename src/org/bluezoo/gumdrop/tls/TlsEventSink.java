/*
 * TlsEventSink.java
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

/**
 * Events pushed by a {@link HandshakeEngine} as it progresses. The
 * engine is driven purely reactively -- {@link HandshakeEngine#start}
 * and {@link HandshakeEngine#processMessage} have no meaningful return
 * value, and every outcome (bytes to send, a milestone reached, a
 * failure) is reported here instead, the same "events in, events out"
 * convention {@code QuicTlsEngineListener} and {@code SSLState.Callback}
 * already use elsewhere in gumdrop.
 *
 * <p>Certificate chain verification is deliberately <em>not</em> an
 * asynchronous gate on this interface (contrast with hopf's own
 * {@code verification_requested}/{@code feed_verification_result}):
 * {@link HandshakeEngine#processMessage} is submitted through the same
 * {@code CryptoExecutor}-backed offload every production caller of this
 * engine uses ({@link org.bluezoo.gumdrop.tls.TlsRecordEngine},
 * {@link org.bluezoo.gumdrop.tls.Dtls13RecordEngine},
 * {@link org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine}), mirroring how
 * agent15's own synchronous certificate validation was already offloaded
 * the same way). A separate per-verification gate would only be useful
 * for a caller that does not already offload the whole handshake step --
 * no caller in this codebase works that way today.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface TlsEventSink {

    /**
     * Handshake bytes to send to the peer (a QUIC CRYPTO stream, in this
     * milestone -- there is no TLS record layer to frame these into yet).
     *
     * @param data the handshake message bytes to send
     */
    void handshakeDataReady(byte[] data);

    /**
     * The handshake traffic secrets are available; the caller may now
     * pull {@link HandshakeEngine#getClientHandshakeTrafficSecret} and
     * {@link HandshakeEngine#getServerHandshakeTrafficSecret} to install
     * Handshake-space packet protection keys.
     */
    void handshakeSecretsReady();

    /**
     * The handshake has completed on this side: application traffic
     * secrets are available via
     * {@link HandshakeEngine#getClientApplicationTrafficSecret} and
     * {@link HandshakeEngine#getServerApplicationTrafficSecret}, and (for
     * a client) the verified peer certificate chain is available via
     * {@link HandshakeEngine#getPeerCertificateChain}.
     */
    void applicationSecretsReady();

    /**
     * The peer's QUIC {@code transport_parameters} extension payload was
     * received. Default no-op since a caller not using this engine for
     * QUIC has nothing to do with it.
     *
     * @param parameters the raw RFC 9000 transport parameters bytes
     */
    default void peerTransportParameters(byte[] parameters) {
    }

    /**
     * A non-fatal-to-the-JVM handshake failure -- malformed input,
     * certificate verification failure, negotiation failure, and the
     * like. The engine does not throw for these; this is how they are
     * reported.
     *
     * @param error the failure
     */
    void protocolError(TlsProtocolError error);

    /**
     * The peer closed the connection cleanly. Default no-op since not
     * every caller needs to distinguish this from any other teardown.
     */
    default void peerClosed() {
    }

    /**
     * 0-RTT (early data) traffic secrets are available: client-side,
     * right after the ClientHello is sent, if a session ticket with
     * early-data support was presented; server-side, once the server has
     * determined the client's presented PSK resumes a valid session and
     * decided to accept early data -- in both cases, before either side
     * has otherwise decided or learned whether 0-RTT will actually be
     * used end to end. Default no-op since a caller not attempting 0-RTT
     * has nothing to do with it.
     *
     * @param suite the cipher suite 0-RTT data is protected under (the
     *              same suite the real handshake will end up negotiating,
     *              by construction -- see {@link HandshakeEngine#start})
     * @param clientEarlyTrafficSecret the client early traffic secret
     */
    default void quicEarlyKeysReady(CipherSuite suite, byte[] clientEarlyTrafficSecret) {
    }

    /**
     * Client-only: fired once EncryptedExtensions arrives, reporting
     * whether the server accepted 0-RTT (presence of an {@code early_data}
     * extension in EncryptedExtensions). Fired on every handshake,
     * unconditionally, regardless of whether this connection ever
     * attempted 0-RTT at all -- a caller that never offered a ticket with
     * early data can simply ignore this. Default no-op.
     *
     * @param accepted whether the server accepted 0-RTT
     */
    default void earlyDataAccepted(boolean accepted) {
    }

    /**
     * Client-only: a post-handshake {@code NewSessionTicket} was
     * received and its resumption PSK derived. The ticket may be
     * presented on a future connection to the same server (via
     * {@link HandshakeConfig#setSessionTicket}) to attempt PSK resumption
     * and, if the ticket allows it, 0-RTT. Default no-op since a caller
     * not interested in resumption has nothing to do with it.
     *
     * @param ticket the received session ticket
     */
    default void sessionTicketReceived(SessionTicket ticket) {
    }

    /**
     * A post-handshake {@code KeyUpdate} (RFC 8446 section 4.6.3/7.2)
     * ratcheted one direction's application traffic secret forward --
     * TCP-TLS-1.3 only ({@link HandshakeMode#TCP_RECORD_LAYER}; RFC 9001
     * section 4.6 forbids this over QUIC entirely). Fired both when this
     * side sends its own {@code KeyUpdate} ({@link KeyUpdateDirection#WRITE})
     * and when it receives the peer's ({@link KeyUpdateDirection#READ}).
     * Default no-op since a caller not using this engine over TCP has
     * nothing to do with it.
     *
     * @param direction which direction ratcheted
     * @param newSecret the new application traffic secret for that direction
     */
    default void applicationTrafficSecretUpdated(KeyUpdateDirection direction, byte[] newSecret) {
    }

}
