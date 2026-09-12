/*
 * AlertDescription.java
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
 * TLS 1.3 alert descriptions (RFC 8446 section 6.2) -- the complete
 * RFC-defined list, so {@link #fromCode} can be total over every value a
 * real peer might send. In QUIC mode these never travel as a TLS Alert
 * record (QUIC has no record layer) -- they map to a QUIC CRYPTO_ERROR
 * transport error code (0x0100 + this alert's wire value, RFC 9001
 * section 4.8), which is the caller's responsibility, not this enum's. In
 * {@link HandshakeMode#TCP_RECORD_LAYER} mode, {@link TlsRecordEngine}
 * sends these as real wire {@code Alert} records.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-6.2">RFC 8446 section 6.2</a>
 */
public enum AlertDescription {

    /** Orderly connection shutdown, warning-level only. */
    CLOSE_NOTIFY(0),
    UNEXPECTED_MESSAGE(10),
    BAD_RECORD_MAC(20),
    /** A record exceeded its maximum permitted length. */
    RECORD_OVERFLOW(22),
    HANDSHAKE_FAILURE(40),
    BAD_CERTIFICATE(42),
    UNSUPPORTED_CERTIFICATE(43),
    /** A certificate has been revoked by its signer. */
    CERTIFICATE_REVOKED(44),
    CERTIFICATE_EXPIRED(45),
    CERTIFICATE_UNKNOWN(46),
    ILLEGAL_PARAMETER(47),
    UNKNOWN_CA(48),
    /** A valid certificate was rejected by local policy. */
    ACCESS_DENIED(49),
    DECODE_ERROR(50),
    DECRYPT_ERROR(51),
    PROTOCOL_VERSION(70),
    INSUFFICIENT_SECURITY(71),
    INTERNAL_ERROR(80),
    /** A client's fallback to a lower protocol version was rejected (RFC 7507). */
    INAPPROPRIATE_FALLBACK(86),
    /** The handshake was canceled for a reason unrelated to a protocol failure. */
    USER_CANCELED(90),
    MISSING_EXTENSION(109),
    UNSUPPORTED_EXTENSION(110),
    UNRECOGNIZED_NAME(112),
    /** The OCSP response carried in the {@code status_request} extension was invalid. */
    BAD_CERTIFICATE_STATUS_RESPONSE(113),
    /** The offered PSK identity isn't recognized. */
    UNKNOWN_PSK_IDENTITY(115),
    CERTIFICATE_REQUIRED(116),
    NO_APPLICATION_PROTOCOL(120);

    private final int code;

    AlertDescription(int code) {
        this.code = code;
    }

    /**
     * Returns the one-octet wire value for this alert.
     *
     * @return the alert code
     */
    public int getCode() {
        return code;
    }

    /**
     * Looks up an alert by its wire code -- used to decode a peer's
     * {@code Alert} record.
     *
     * @param code the one-octet wire value
     * @return the matching alert, or null if the code is not one RFC
     *         8446 section 6.2 defines
     */
    public static AlertDescription fromCode(int code) {
        AlertDescription[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (values[i].code == code) {
                return values[i];
            }
        }
        return null;
    }

}
