/*
 * QPACKConstants.java
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

package org.bluezoo.gumdrop.http.qpack;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

/**
 * Static constants for QPACK encoder and decoder (RFC 9204).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204">RFC 9204</a>
 */
abstract class QPACKConstants {

    /**
     * RFC 9204 Appendix A: Static Table Definition.
     * 99 entries (indices 0-98) -- unlike HPACK's, QPACK's static
     * table is zero-indexed.
     */
    protected static final List<Header> STATIC_TABLE = Collections.unmodifiableList(Arrays.asList(new Header[] {
        new Header(":authority", null),
        new Header(":path", "/"),
        new Header("age", "0"),
        new Header("content-disposition", null),
        new Header("content-length", "0"),
        new Header("cookie", null),
        new Header("date", null),
        new Header("etag", null),
        new Header("if-modified-since", null),
        new Header("if-none-match", null),
        new Header("last-modified", null),
        new Header("link", null),
        new Header("location", null),
        new Header("referer", null),
        new Header("set-cookie", null),
        new Header(":method", "CONNECT"),
        new Header(":method", "DELETE"),
        new Header(":method", "GET"),
        new Header(":method", "HEAD"),
        new Header(":method", "OPTIONS"),
        new Header(":method", "POST"),
        new Header(":method", "PUT"),
        new Header(":scheme", "http"),
        new Header(":scheme", "https"),
        new Header(":status", "103"),
        new Header(":status", "200"),
        new Header(":status", "304"),
        new Header(":status", "404"),
        new Header(":status", "503"),
        new Header("accept", "*/*"),
        new Header("accept", "application/dns-message"),
        new Header("accept-encoding", "gzip, deflate, br"),
        new Header("accept-ranges", "bytes"),
        new Header("access-control-allow-headers", "cache-control"),
        new Header("access-control-allow-headers", "content-type"),
        new Header("access-control-allow-origin", "*"),
        new Header("cache-control", "max-age=0"),
        new Header("cache-control", "max-age=2592000"),
        new Header("cache-control", "max-age=604800"),
        new Header("cache-control", "no-cache"),
        new Header("cache-control", "no-store"),
        new Header("cache-control", "public, max-age=31536000"),
        new Header("content-encoding", "br"),
        new Header("content-encoding", "gzip"),
        new Header("content-type", "application/dns-message"),
        new Header("content-type", "application/javascript"),
        new Header("content-type", "application/json"),
        new Header("content-type", "application/x-www-form-urlencoded"),
        new Header("content-type", "image/gif"),
        new Header("content-type", "image/jpeg"),
        new Header("content-type", "image/png"),
        new Header("content-type", "text/css"),
        new Header("content-type", "text/html; charset=utf-8"),
        new Header("content-type", "text/plain"),
        new Header("content-type", "text/plain;charset=utf-8"),
        new Header("range", "bytes=0-"),
        new Header("strict-transport-security", "max-age=31536000"),
        new Header("strict-transport-security", "max-age=31536000; includesubdomains"),
        new Header("strict-transport-security", "max-age=31536000; includesubdomains; preload"),
        new Header("vary", "accept-encoding"),
        new Header("vary", "origin"),
        new Header("x-content-type-options", "nosniff"),
        new Header("x-xss-protection", "1; mode=block"),
        new Header(":status", "100"),
        new Header(":status", "204"),
        new Header(":status", "206"),
        new Header(":status", "302"),
        new Header(":status", "400"),
        new Header(":status", "403"),
        new Header(":status", "421"),
        new Header(":status", "425"),
        new Header(":status", "500"),
        new Header("accept-language", null),
        new Header("access-control-allow-credentials", "FALSE"),
        new Header("access-control-allow-credentials", "TRUE"),
        new Header("access-control-allow-headers", "*"),
        new Header("access-control-allow-methods", "get"),
        new Header("access-control-allow-methods", "get, post, options"),
        new Header("access-control-allow-methods", "options"),
        new Header("access-control-expose-headers", "content-length"),
        new Header("access-control-request-headers", "content-type"),
        new Header("access-control-request-method", "get"),
        new Header("access-control-request-method", "post"),
        new Header("alt-svc", "clear"),
        new Header("authorization", null),
        new Header("content-security-policy",
                "script-src 'none'; object-src 'none'; base-uri 'none'"),
        new Header("early-data", "1"),
        new Header("expect-ct", null),
        new Header("forwarded", null),
        new Header("if-range", null),
        new Header("origin", null),
        new Header("purpose", "prefetch"),
        new Header("server", null),
        new Header("timing-allow-origin", "*"),
        new Header("upgrade-insecure-requests", "1"),
        new Header("user-agent", null),
        new Header("x-forwarded-for", null),
        new Header("x-frame-options", "deny"),
        new Header("x-frame-options", "sameorigin")
    }));

    protected static final int STATIC_TABLE_SIZE = STATIC_TABLE.size(); // 99

}
