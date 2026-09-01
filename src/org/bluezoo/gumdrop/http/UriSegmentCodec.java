/*
 * UriSegmentCodec.java
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

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * RFC 3986 section 2.1 percent-encoding of a single URI path segment,
 * shared by the MASQUE URI Templates that need it: RFC 9298 section 3
 * ({@link ConnectUdpTarget}, an IPv6 literal's colons) and RFC 9484
 * section 3 ({@link ConnectIpTarget}, an IPv6 prefix's colons and its
 * {@code "/"} prefix-length separator, per that RFC's {@code
 * IPv6prefix}/{@code IPv4prefix} ABNF).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class UriSegmentCodec {

    private UriSegmentCodec() {
    }

    private static final String UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    /**
     * Percent-decodes a URI path segment. Decodes strictly -- any
     * malformed escape (not exactly {@code %XX} with two hex digits)
     * fails the whole decode rather than silently passing through
     * garbage bytes, since callers feed the result into a DNS lookup,
     * connection attempt, or address parse.
     *
     * @param s the encoded segment
     * @return the decoded segment, or {@code null} if malformed
     */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        byte[] out = new byte[s.length()];
        int outLen = 0;
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= len) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out[outLen++] = (byte) ((hi << 4) | lo);
                i += 3;
            } else {
                if (c > 0x7f) {
                    return null;
                }
                out[outLen++] = (byte) c;
                i++;
            }
        }
        try {
            return new String(out, 0, outLen, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always supported
        }
    }

    /**
     * Percent-encodes a URI path segment: unreserved characters (RFC
     * 3986 section 2.3) pass through as-is, everything else -- including
     * {@code ":"} and {@code "/"}, both meaningful in the MASQUE targets
     * that use this -- becomes {@code %XX}.
     *
     * @param s the raw segment value
     * @return the percent-encoded segment
     */
    static String percentEncode(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            char c = (char) (b & 0xff);
            if (c < 0x80 && UNRESERVED.indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append('%');
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
        }
        return sb.toString();
    }
}
