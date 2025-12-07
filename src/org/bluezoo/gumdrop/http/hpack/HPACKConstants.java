/*
 * HPACKConstants.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.hpack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

/**
 * Static constants for HPACK decoder and encoder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7541
 */
abstract class HPACKConstants {

    /**
     * @see https://www.rfc-editor.org/rfc/rfc7541.html#appendix-A
     */
    protected static final List<Header> STATIC_TABLE = Collections.unmodifiableList(Arrays.asList(new Header[] {
        null,
        new Header(":authority", null),
        new Header(":method", "GET"),
        new Header(":method", "POST"),
        new Header(":path", "/"),
        new Header(":path", "/index.html"),
        new Header(":scheme", "http"),
        new Header(":scheme", "https"),
        new Header(":status", "200"),
        new Header(":status", "204"),
        new Header(":status", "206"),
        new Header(":status", "304"),
        new Header(":status", "400"),
        new Header(":status", "404"),
        new Header(":status", "500"),
        new Header("accept-charset", null),
        new Header("accept-encoding", "gzip, deflate"),
        new Header("accept-language", null),
        new Header("accept-ranges", null),
        new Header("accept", null),
        new Header("access-control-allow-origin", null),
        new Header("age", null),
        new Header("allow", null),
        new Header("authorization", null),
        new Header("cache-control", null),
        new Header("content-disposition", null),
        new Header("content-encoding", null),
        new Header("content-language", null),
        new Header("content-length", null),
        new Header("content-location", null),
        new Header("content-range", null),
        new Header("content-type", null),
        new Header("cookie", null),
        new Header("date", null),
        new Header("etag", null),
        new Header("expect", null),
        new Header("expires", null),
        new Header("from", null),
        new Header("host", null),
        new Header("if-match", null),
        new Header("if-modified-since", null),
        new Header("if-none-match", null),
        new Header("if-range", null),
        new Header("if-unmodified-since", null),
        new Header("last-modified", null),
        new Header("link", null),
        new Header("location", null),
        new Header("max-forwards", null),
        new Header("proxy-authenticate", null),
        new Header("proxy-authorization", null),
        new Header("range", null),
        new Header("referer", null),
        new Header("refresh", null),
        new Header("retry-after", null),
        new Header("server", null),
        new Header("set-cookie", null),
        new Header("strict-transport-security", null),
        new Header("transfer-encoding", null),
        new Header("user-agent", null),
        new Header("vary", null),
        new Header("via", null),
        new Header("www-authenticate", null)
    }));

    protected static final int STATIC_TABLE_SIZE = STATIC_TABLE.size(); // 62

    /**
     * The size of a dynamic table is the sum of the size of its entries.
     */
    protected static int tableSize(List<Header> table) {
        int acc = 0;
        for (Header header : table) {
            acc += headerSize(header);
        }
        return acc;
    }

    /**
     * The size of an entry is the sum of its name's length in octets (as
     * defined in Section 5.2), its value's length in octets, and 32.
     * The size of an entry is calculated using the length of its name and
     * value without any Huffman encoding applied.
     */
    protected static int headerSize(Header header) {
        return header.getName().length() + header.getValue().length() + 32;
    }

}
