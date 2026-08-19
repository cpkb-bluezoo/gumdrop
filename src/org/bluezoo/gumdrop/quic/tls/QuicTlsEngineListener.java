/*
 * QuicTlsEngineListener.java
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

import tech.kwik.agent15.NewSessionTicket;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

/**
 * Callback interface through which {@link QuicTlsClientEngine} and
 * {@link QuicTlsServerEngine} notify the QUIC transport of handshake
 * progress.
 *
 * <p>Implementations are expected to buffer bytes from
 * {@link #cryptoDataReady} into outgoing CRYPTO frames at the given
 * level, and to derive {@link org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys}
 * from the corresponding traffic secret (read from the engine, e.g.
 * {@code getClientHandshakeTrafficSecret()}) when secrets become
 * available.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface QuicTlsEngineListener {

    /**
     * Called when the TLS engine has produced handshake message bytes
     * to send at the given encryption level, in CRYPTO stream order.
     *
     * @param level the encryption level to send this data at
     * @param offset the byte offset of {@code data} within this level's
     *               outgoing CRYPTO stream
     * @param data the handshake message bytes
     */
    void cryptoDataReady(EncryptionLevel level, long offset, byte[] data);

    /**
     * Called when {@link EncryptionLevel#HANDSHAKE} traffic secrets have
     * become available (RFC 9001 section 4.1: after ServerHello has
     * been sent/received). Handshake-level packet protection keys can
     * now be derived.
     */
    void handshakeSecretsAvailable();

    /**
     * Called when this side's TLS handshake has finished: the client
     * after verifying the server's Finished message and sending its
     * own; the server after verifying the client's Finished message.
     * {@link EncryptionLevel#ONE_RTT} traffic secrets are available
     * from this point.
     *
     * <p>This is a TLS-layer event, not the QUIC-layer "handshake
     * confirmed" state (RFC 9001 section 4.1.2): a client only
     * considers the handshake confirmed once it receives a
     * HANDSHAKE_DONE frame from the server.
     */
    void handshakeFinished();

    /**
     * Called when the peer's {@code quic_transport_parameters} extension
     * (RFC 9001 section 8.2) has been received and decoded: after
     * ClientHello for a server engine, after EncryptedExtensions for a
     * client engine. Flow control and stream limits should be
     * initialised from these values.
     *
     * @param transportParameters the peer's transport parameters
     */
    void transportParametersReceived(TransportParameters transportParameters);

    /**
     * Called when early (0-RTT) traffic secrets have become available
     * (RFC 9001 section 4.6.1): client-side, right after the ClientHello
     * is sent, if a previous session's ticket was presented; server-side,
     * once the server has determined the client's presented PSK resumes
     * a valid session -- in both cases, before either side has decided
     * whether 0-RTT will actually be accepted (see
     * {@code QuicTlsServerEngine#isEarlyDataAccepted}). The client early
     * traffic secret is available from this point via
     * {@link QuicTlsEngine#getClientEarlyTrafficSecret()}.
     */
    void earlySecretsAvailable();

    /**
     * Called when a new session ticket is received (client-side only;
     * post-handshake, RFC 8446 section 4.6.1). The ticket may be
     * presented on a future connection to the same server to attempt
     * PSK resumption and, if the ticket allows it, 0-RTT.
     *
     * @param ticket the received session ticket
     */
    void newSessionTicketReceived(NewSessionTicket ticket);

    /**
     * Client-only: called once EncryptedExtensions arrives, reporting
     * whether the server accepted 0-RTT (RFC 9001 section 4.6.1 / RFC
     * 8446 section 4.2.10 -- presence of an {@code early_data} extension
     * in EncryptedExtensions). Called on every handshake, unconditionally,
     * regardless of whether this connection ever attempted 0-RTT at all
     * -- implementations that never offered a ticket with early data can
     * simply ignore this.
     *
     * @param accepted whether the server accepted 0-RTT
     */
    void earlyDataOutcomeKnown(boolean accepted);
}
