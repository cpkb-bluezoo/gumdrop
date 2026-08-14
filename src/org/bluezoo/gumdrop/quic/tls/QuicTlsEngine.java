/*
 * QuicTlsEngine.java
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

import tech.kwik.agent15.TlsProtocolException;

/**
 * The common shape of {@link QuicTlsClientEngine} and
 * {@link QuicTlsServerEngine} that a QUIC connection needs regardless of
 * which side of the handshake it is driving: feeding received CRYPTO
 * frame data in.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface QuicTlsEngine {

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
    void receiveCryptoData(EncryptionLevel level, long offset, ByteBuffer data)
            throws TlsProtocolException, IOException;

    /**
     * Returns the client Handshake traffic secret (RFC 9001 section 4.1),
     * valid once {@link QuicTlsEngineListener#handshakeSecretsAvailable()}
     * has fired.
     *
     * @return the client Handshake traffic secret
     */
    byte[] getClientHandshakeTrafficSecret();

    /**
     * Returns the server Handshake traffic secret (RFC 9001 section 4.1),
     * valid once {@link QuicTlsEngineListener#handshakeSecretsAvailable()}
     * has fired.
     *
     * @return the server Handshake traffic secret
     */
    byte[] getServerHandshakeTrafficSecret();

    /**
     * Returns the client 1-RTT (Application) traffic secret, valid once
     * {@link QuicTlsEngineListener#handshakeFinished()} has fired.
     *
     * @return the client Application traffic secret
     */
    byte[] getClientApplicationTrafficSecret();

    /**
     * Returns the server 1-RTT (Application) traffic secret, valid once
     * {@link QuicTlsEngineListener#handshakeFinished()} has fired.
     *
     * @return the server Application traffic secret
     */
    byte[] getServerApplicationTrafficSecret();
}
