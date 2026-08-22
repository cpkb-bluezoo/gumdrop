/*
 * H3ErrorCode.java
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

package org.bluezoo.gumdrop.http.h3;

/**
 * HTTP/3 and QPACK application error codes, used in QUIC
 * {@code CONNECTION_CLOSE} (application variant) and {@code RESET_STREAM}
 * / {@code STOP_SENDING}.
 *
 * <p>RFC 9114 section 8.1 defines the HTTP/3 namespace ({@code 0x0100}
 * through {@code 0x0110}). RFC 9204 section 6 defines the QPACK
 * namespace ({@code 0x0200} through {@code 0x0202}). RFC 9297 defines
 * {@link #H3_DATAGRAM_ERROR} ({@code 0x33}). These values are
 * distinct from HTTP/3 frame types ({@link H3FrameHandler#TYPE_DATA}
 * and so on) and from QPACK unidirectional stream types.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-8.1">RFC 9114 section 8.1</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-6">RFC 9204 section 6</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297</a>
 */
public final class H3ErrorCode {

    private H3ErrorCode() {
    }

    /** RFC 9114 section 8.1: no error or unused. */
    public static final long H3_NO_ERROR = 0x0100;
    /** RFC 9114 section 8.1: an error that cannot be described more specifically. */
    public static final long H3_GENERAL_PROTOCOL_ERROR = 0x0101;
    /** RFC 9114 section 8.1: an internal error in the HTTP stack. */
    public static final long H3_INTERNAL_ERROR = 0x0102;
    /** RFC 9114 section 8.1: the peer created a stream this endpoint will not accept. */
    public static final long H3_STREAM_CREATION_ERROR = 0x0103;
    /** RFC 9114 section 8.1: a stream required by the connection was closed or reset. */
    public static final long H3_CLOSED_CRITICAL_STREAM = 0x0104;
    /** RFC 9114 section 8.1: a frame is not permitted in the current state or on that stream. */
    public static final long H3_FRAME_UNEXPECTED = 0x0105;
    /** RFC 9114 section 8.1: a frame violated layout or size rules for its type. */
    public static final long H3_FRAME_ERROR = 0x0106;
    /** RFC 9114 section 8.1: the peer is generating excessive load. */
    public static final long H3_EXCESSIVE_LOAD = 0x0107;
    /** RFC 9114 section 8.1: a stream ID or push ID was used incorrectly. */
    public static final long H3_ID_ERROR = 0x0108;
    /** RFC 9114 section 8.1: an error was detected in a SETTINGS frame payload. */
    public static final long H3_SETTINGS_ERROR = 0x0109;
    /** RFC 9114 section 8.1: no SETTINGS frame was received at the start of the control stream. */
    public static final long H3_MISSING_SETTINGS = 0x010a;
    /** RFC 9114 section 8.1: a server rejected a request without processing it. */
    public static final long H3_REQUEST_REJECTED = 0x010b;
    /** RFC 9114 section 8.1: the request or its response is cancelled. */
    public static final long H3_REQUEST_CANCELLED = 0x010c;
    /** RFC 9114 section 8.1: the client's stream ended without a fully formed request. */
    public static final long H3_REQUEST_INCOMPLETE = 0x010d;
    /** RFC 9114 section 8.1: a malformed request or response message. */
    public static final long H3_MESSAGE_ERROR = 0x010e;
    /** RFC 9114 section 8.1: the TCP connection for a CONNECT request was reset or closed abnormally. */
    public static final long H3_CONNECT_ERROR = 0x010f;
    /** RFC 9114 section 8.1: the operation cannot be served over HTTP/3; retry over HTTP/1.1. */
    public static final long H3_VERSION_FALLBACK = 0x0110;

    /** RFC 9204 section 6: a field section could not be decoded. */
    public static final long QPACK_DECOMPRESSION_FAILED = 0x0200;
    /** RFC 9204 section 6: the peer's encoder stream sent a malformed instruction. */
    public static final long QPACK_ENCODER_STREAM_ERROR = 0x0201;
    /** RFC 9204 section 6: the peer's decoder stream sent a malformed instruction. */
    public static final long QPACK_DECODER_STREAM_ERROR = 0x0202;

    /**
     * RFC 9297: HTTP Datagram protocol violation (unknown stream,
     * malformed quarter-stream-ID, or SETTINGS_H3_DATAGRAM not agreed).
     * Distinct from the {@code 0x0100}-range codes in RFC 9114 section 8.1.
     */
    public static final long H3_DATAGRAM_ERROR = 0x33;
}
