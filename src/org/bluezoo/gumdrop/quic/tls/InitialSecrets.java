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

/**
 * Derivation of QUIC Initial packet protection secrets (RFC 9001
 * section 5.2).
 *
 * <p>Initial packets are protected with a secret derived directly from the
 * Destination Connection ID of the client's first Initial packet, salted
 * with a fixed, version-specific, publicly known value -- Initial
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

    /**
     * The QUIC version 1 Initial salt (RFC 9001 section 5.2). Future QUIC
     * versions define their own salt so that a middlebox that recognises
     * only one version cannot inspect or modify Initial packets of another.
     */
    private static final byte[] SALT_V1 = {
        (byte) 0x38, (byte) 0x76, (byte) 0x2c, (byte) 0xf7,
        (byte) 0xf5, (byte) 0x59, (byte) 0x34, (byte) 0xb3,
        (byte) 0x4d, (byte) 0x17, (byte) 0x9a, (byte) 0xe6,
        (byte) 0xa4, (byte) 0xc8, (byte) 0x0c, (byte) 0xad,
        (byte) 0xcc, (byte) 0xbb, (byte) 0x7f, (byte) 0x0a
    };

    /** RFC 9369 section 3.3.1: QUIC version 2 uses a distinct Initial salt. */
    private static final byte[] SALT_V2 = {
        (byte) 0x0d, (byte) 0xed, (byte) 0xe3, (byte) 0xde,
        (byte) 0xf7, (byte) 0x00, (byte) 0xa6, (byte) 0xdb,
        (byte) 0x81, (byte) 0x93, (byte) 0x81, (byte) 0xbe,
        (byte) 0x6e, (byte) 0x26, (byte) 0x9d, (byte) 0xcb,
        (byte) 0xf9, (byte) 0xbd, (byte) 0x2e, (byte) 0xd9
    };

    private static final byte[] EMPTY_CONTEXT = new byte[0];

    private static final Hkdf HKDF = Hkdf.sha256();

    private InitialSecrets() {
    }

    /**
     * Derives the secret used to protect Initial packets sent by the
     * client, for QUIC version 1.
     *
     * @param clientDestinationConnectionId the Destination Connection ID
     *        of the client's first Initial packet
     * @return the 32-byte client Initial secret
     */
    public static byte[] clientSecretV1(byte[] clientDestinationConnectionId) {
        return clientSecret(clientDestinationConnectionId, SALT_V1);
    }

    /**
     * Derives the secret used to protect Initial packets sent by the
     * server, for QUIC version 1.
     *
     * @param clientDestinationConnectionId the Destination Connection ID
     *        of the client's first Initial packet
     * @return the 32-byte server Initial secret
     */
    public static byte[] serverSecretV1(byte[] clientDestinationConnectionId) {
        return serverSecret(clientDestinationConnectionId, SALT_V1);
    }

    /**
     * Derives the secret used to protect Initial packets sent by the
     * client, for QUIC version 2 (RFC 9369).
     *
     * @param clientDestinationConnectionId the Destination Connection ID
     *        of the client's first Initial packet
     * @return the 32-byte client Initial secret
     */
    public static byte[] clientSecretV2(byte[] clientDestinationConnectionId) {
        return clientSecret(clientDestinationConnectionId, SALT_V2);
    }

    /**
     * Derives the secret used to protect Initial packets sent by the
     * server, for QUIC version 2 (RFC 9369).
     *
     * @param clientDestinationConnectionId the Destination Connection ID
     *        of the client's first Initial packet
     * @return the 32-byte server Initial secret
     */
    public static byte[] serverSecretV2(byte[] clientDestinationConnectionId) {
        return serverSecret(clientDestinationConnectionId, SALT_V2);
    }

    private static byte[] clientSecret(byte[] connectionId, byte[] salt) {
        byte[] initialSecret = HKDF.extract(salt, connectionId);
        return HKDF.expandLabel(initialSecret, "client in", EMPTY_CONTEXT, HKDF.getHashLength());
    }

    private static byte[] serverSecret(byte[] connectionId, byte[] salt) {
        byte[] initialSecret = HKDF.extract(salt, connectionId);
        return HKDF.expandLabel(initialSecret, "server in", EMPTY_CONTEXT, HKDF.getHashLength());
    }
}
