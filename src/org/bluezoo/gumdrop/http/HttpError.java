/*
 * HttpError.java
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

package org.bluezoo.gumdrop.http;

/**
 * Why a parser gave up on an HTTP message, delivered through {@link
 * HttpMessageHandler#error(HttpError, String)}.
 *
 * <p>Each value carries the status code a server would answer with
 * (RFC 9110 section 15.5 and 15.6). A client receiving a bad response, or
 * any peer reading HTTP/2 or HTTP/3, uses the value only to tell the causes
 * apart; framing is lost after an HTTP/1.x error, so the connection is closed
 * whatever the status.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum HttpError {

    /** The message is syntactically wrong: start line, field line or chunk framing. */
    MALFORMED(400),

    /**
     * The message length is ambiguous or contradictory, which is how request
     * smuggling is attempted (RFC 9112 section 6.3): conflicting
     * {@code Content-Length} values, or {@code Content-Length} together with
     * {@code Transfer-Encoding}.
     */
    FRAMING_CONFLICT(400),

    /** The request target is longer than the parser accepts. */
    URI_TOO_LONG(414),

    /** A field line, or the number of fields, exceeds the parser's limits. */
    FIELD_SECTION_TOO_LARGE(431),

    /** A transfer coding the parser cannot decode. */
    UNSUPPORTED_TRANSFER_CODING(501),

    /** An HTTP version this parser does not speak. */
    UNSUPPORTED_VERSION(505);

    private final int statusCode;

    private HttpError(int statusCode) {
        this.statusCode = statusCode;
    }

    /**
     * Returns the status code a server would answer with.
     *
     * @return the HTTP status code
     */
    public int getStatusCode() {
        return statusCode;
    }

}
