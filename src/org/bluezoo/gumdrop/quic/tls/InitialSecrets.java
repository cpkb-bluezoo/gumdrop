/*
 * InitialSecrets.java
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

import org.bluezoo.gumdrop.crypto.CryptoServices;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;

/**
 * Derivation of QUIC Initial packet protection secrets (RFC 9001
 * section 5.2).
 *
 * <p>Initial packets are protected with a secret derived directly from the
 * Destination Connection ID of the client's first Initial packet, salted
 * with a fixed, version-specific (see {@link QuicVersion}), publicly known value -- Initial
 * protection provides only a modest obstacle to on-path observers, not
 * confidentiality; its purpose is to require that a peer have seen the
 * connection's Initial packet, and to be replaced by real, TLS-negotiated
 * keys as soon as the handshake proceeds. The hash function for this
 * derivation is always SHA-256, independent of the cipher suite eventually
 * negotiated by the TLS handshake.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.2">RFC 9001 section 5.2</a>
 */
public final class InitialSecrets {

    private static final byte[] EMPTY_CONTEXT = new byte[0];

    /** RFC 9001 section 5.2: Initial secrets always use HKDF-SHA256. */
    private static final String HASH = "SHA-256";

    private InitialSecrets() {
    }

    /**
     * Derives the secret used to protect Initial packets sent by the client.
     *
     * @param version the QUIC version, which selects the salt
     * @param clientDestinationConnectionId the Destination Connection ID
     *        of the client's first Initial packet
     * @param crypto the services the HKDF comes from
     * @return the 32-byte client Initial secret
     */
    public static byte[] clientSecret(QuicVersion version, byte[] clientDestinationConnectionId,
            CryptoServices crypto) {
        return derive(version, clientDestinationConnectionId, "client in", crypto);
    }

    /**
     * Derives the secret used to protect Initial packets sent by the server.
     *
     * @param version the QUIC version, which selects the salt
     * @param clientDestinationConnectionId the Destination Connection ID
     *        of the client's first Initial packet
     * @param crypto the services the HKDF comes from
     * @return the 32-byte server Initial secret
     */
    public static byte[] serverSecret(QuicVersion version, byte[] clientDestinationConnectionId,
            CryptoServices crypto) {
        return derive(version, clientDestinationConnectionId, "server in", crypto);
    }

    private static byte[] derive(QuicVersion version, byte[] connectionId, String label, CryptoServices crypto) {
        byte[] initialSecret = crypto.hkdfExtract(HASH, version.getInitialSalt(), connectionId);
        return crypto.hkdfExpandLabel(HASH, initialSecret, label, EMPTY_CONTEXT, CryptoServices.hashLength(HASH));
    }
}
