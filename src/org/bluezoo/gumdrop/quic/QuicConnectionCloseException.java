/*
 * QuicConnectionCloseException.java
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

package org.bluezoo.gumdrop.quic;

import java.io.IOException;

/**
 * Signals that a QUIC connection closed abnormally -- either the peer sent
 * a CONNECTION_CLOSE with a transport or application error code (RFC 9000
 * section 19.19), or this endpoint detected a local transport-level
 * violation and closed the connection itself.
 *
 * <p>Delivered to a stream's {@link org.bluezoo.gumdrop.ProtocolHandler
 * #error(Exception)} in place of {@link org.bluezoo.gumdrop.ProtocolHandler
 * #disconnected()}, so a caller can distinguish an error close from a clean
 * one -- both previously collapsed to the same argument-free
 * {@code disconnected()} call.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000.html#section-19.19">RFC 9000 section 19.19</a>
 */
public final class QuicConnectionCloseException extends IOException {

    private static final long serialVersionUID = 1L;

    private final boolean applicationError;
    private final long errorCode;
    private final String reason;

    /**
     * Creates a new connection-close exception.
     *
     * @param applicationError true for an application-level (0x1d) close,
     *        false for a transport-level (0x1c) close
     * @param errorCode the error code -- an RFC 9000 section 20.1 transport
     *        error code if {@code applicationError} is false, otherwise an
     *        ALPN-scoped application error code (e.g. an RFC 9114 section
     *        8.1 HTTP/3 error) this layer cannot decode
     * @param reason the close reason phrase, or null if none was given
     */
    public QuicConnectionCloseException(boolean applicationError, long errorCode, String reason) {
        super(buildMessage(applicationError, errorCode, reason));
        this.applicationError = applicationError;
        this.errorCode = errorCode;
        this.reason = reason;
    }

    /**
     * Returns true if this was an application-level (0x1d) close, false
     * if it was a transport-level (0x1c) close.
     *
     * @return whether this was an application-level close
     */
    public boolean isApplicationError() {
        return applicationError;
    }

    /**
     * Returns the error code. An RFC 9000 section 20.1 transport error code
     * if {@link #isApplicationError()} is false; otherwise an ALPN-scoped
     * application error code this layer does not attempt to decode.
     *
     * @return the error code
     */
    public long getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the close reason phrase.
     *
     * @return the reason, or null if none was given
     */
    public String getReason() {
        return reason;
    }

    private static String buildMessage(boolean applicationError, long errorCode, String reason) {
        StringBuilder sb = new StringBuilder("QUIC connection closed with ");
        if (applicationError) {
            sb.append("application error 0x");
            sb.append(Long.toHexString(errorCode));
        } else {
            sb.append("transport error ");
            sb.append(transportErrorToString(errorCode));
        }
        if (reason != null && !reason.isEmpty()) {
            sb.append(": ");
            sb.append(reason);
        }
        return sb.toString();
    }

    /**
     * Returns the RFC 9000 section 20.1 transport error code name (e.g.
     * "FLOW_CONTROL_ERROR"), or "UNKNOWN(code)"/"CRYPTO_ERROR(alert)" for
     * codes not in the fixed table.
     *
     * @param errorCode the transport error code
     * @return the error name
     */
    static String transportErrorToString(long errorCode) {
        if (errorCode >= 0x0100 && errorCode <= 0x01ff) {
            // RFC 9000 section 20.1: CRYPTO_ERROR carries the TLS alert
            // description code in its low byte.
            return "CRYPTO_ERROR(" + (errorCode - 0x0100) + ")";
        }
        switch ((int) errorCode) {
            case 0x0: return "NO_ERROR";
            case 0x1: return "INTERNAL_ERROR";
            case 0x2: return "CONNECTION_REFUSED";
            case 0x3: return "FLOW_CONTROL_ERROR";
            case 0x4: return "STREAM_LIMIT_ERROR";
            case 0x5: return "STREAM_STATE_ERROR";
            case 0x6: return "FINAL_SIZE_ERROR";
            case 0x7: return "FRAME_ENCODING_ERROR";
            case 0x8: return "TRANSPORT_PARAMETER_ERROR";
            case 0x9: return "CONNECTION_ID_LIMIT_ERROR";
            case 0xa: return "PROTOCOL_VIOLATION";
            case 0xb: return "INVALID_TOKEN";
            case 0xc: return "APPLICATION_ERROR";
            case 0xd: return "CRYPTO_BUFFER_EXCEEDED";
            case 0xe: return "KEY_UPDATE_ERROR";
            case 0xf: return "AEAD_LIMIT_REACHED";
            case 0x10: return "NO_VIABLE_PATH";
            default: return "UNKNOWN(" + errorCode + ")";
        }
    }
}
