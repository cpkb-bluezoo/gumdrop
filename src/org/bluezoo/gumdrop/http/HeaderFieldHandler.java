/*
 * HeaderFieldHandler.java
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

import java.nio.ByteBuffer;

/**
 * Receives the header fields of one message, as the octets that were on the
 * wire, from whichever protocol parser decoded them (HPACK, QPACK or the
 * HTTP/1.x line scanner).
 *
 * <p>The wire formats agree on very little about what a field value means:
 * HPACK (RFC 7541 section 5.2) and QPACK carry arbitrary octets, and
 * RFC 9110 section 5.5 treats octets above 0x7F as opaque, naming no charset.
 * This interface therefore passes octets, never text. A consumer that wants
 * text, or a structured value such as a media type, decides how the octets
 * are to be read and parses them itself.
 *
 * <p>Parsers do not judge whether a field is acceptable. That is the
 * receiver's decision, taken once the whole field section has been consumed,
 * so that a field the receiver rejects cannot leave a compression table out of
 * step with the peer's (RFC 9113 section 4.3, RFC 9204 section 2.2.3).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface HeaderFieldHandler {

    /**
     * Delivers one header field.
     *
     * <p>Both buffers are read-only views whose position is the first octet
     * and whose limit is just past the last. They are valid only until this
     * method returns: the parser may reuse the storage behind them, so a
     * handler that needs the octets later must copy them. A handler must not
     * assume the name is lower case, or valid as a field name or value, or
     * (for HTTP/1.x) free of the whitespace that RFC 9112 section 5.2 allows
     * around a value; it checks what it needs to.
     *
     * @param name the field name octets
     * @param value the field value octets (empty if the field has none)
     */
    void field(ByteBuffer name, ByteBuffer value);

}
