/*
 * package-info.java
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

/**
 * QUIC frame encoding and decoding (RFC 9000 section 19), the transport
 * layer's analogue of {@code org.bluezoo.gumdrop.http.h2}'s frame codec:
 * a push-parser ({@link org.bluezoo.gumdrop.quic.frame.QuicFrameParser})
 * delivering typed callbacks to a
 * {@link org.bluezoo.gumdrop.quic.frame.QuicFrameHandler}, plus a writer
 * for the same frame types.
 *
 * <p>PADDING, PING, ACK, CRYPTO, CONNECTION_CLOSE, HANDSHAKE_DONE,
 * STREAM, and the flow-control frame family (MAX_DATA,
 * MAX_STREAM_DATA, MAX_STREAMS, DATA_BLOCKED, STREAM_DATA_BLOCKED,
 * STREAMS_BLOCKED) are implemented. NEW_CONNECTION_ID,
 * RETIRE_CONNECTION_ID, PATH_CHALLENGE, and PATH_RESPONSE are not --
 * connection ID rotation and migration are not supported yet.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19">RFC 9000 section 19</a>
 */
package org.bluezoo.gumdrop.quic.frame;
