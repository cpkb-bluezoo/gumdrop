/*
 * HeaderCollector.java
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

import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;

/**
 * A {@link HeaderFieldHandler} that gathers the fields of one message into a
 * list of {@link Header}s, the form the rest of the stack still consumes.
 *
 * <p>A field that is not valid HTTP field syntax is not added, and sets
 * {@link #isMalformed()}. Collection carries on regardless, so the parser
 * delivering the fields can finish the whole field section and keep its
 * compression table in step with the peer's (RFC 9113 section 4.3, RFC 9204
 * section 2.2.3). The caller then treats the message as malformed, which both
 * HTTP/2 and HTTP/3 make a stream error (RFC 9113 section 8.1.1, RFC 9114
 * section 4.1.2).
 *
 * <p>Each octet of a name or value becomes the character with the same value;
 * see {@link Header#ofOctets(ByteBuffer, ByteBuffer)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HeaderCollector implements HeaderFieldHandler {

    private final List<Header> headers = new ArrayList<Header>();
    private boolean malformed;

    @Override
    public void field(ByteBuffer name, ByteBuffer value) {
        try {
            headers.add(Header.ofOctets(name, value));
        } catch (IllegalArgumentException e) {
            malformed = true;
        }
    }

    /**
     * Returns the valid fields collected so far, in the order delivered.
     *
     * @return the headers
     */
    public List<Header> headers() {
        return headers;
    }

    /**
     * Returns whether any delivered field was not valid HTTP field syntax.
     *
     * @return true if a field was refused
     */
    public boolean isMalformed() {
        return malformed;
    }

}
