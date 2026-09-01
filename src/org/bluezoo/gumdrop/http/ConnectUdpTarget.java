/*
 * ConnectUdpTarget.java
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
 * The target host/port encoded in an RFC 9298 CONNECT-UDP request's
 * {@code :path}, per the URI Template (RFC 6570) registered in RFC 9298
 * section 3:
 *
 * <pre>{@code
 * /.well-known/masque/udp/{target_host}/{target_port}/
 * }</pre>
 *
 * <p>{@code target_host} is percent-encoded per RFC 3986 section 2.1
 * (needed for an IPv6 literal, whose colons are not valid in a raw path
 * segment); {@code target_port} is a decimal integer with no leading
 * zero. Both are required to have a trailing slash after the port,
 * matching the template exactly -- this class rejects anything else
 * rather than guessing at a looser match.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298#section-3">RFC 9298 section 3</a>
 */
public final class ConnectUdpTarget {

    /** The RFC 9298 section 3 URI Template's fixed prefix. */
    public static final String PATH_PREFIX = "/.well-known/masque/udp/";

    private final String host;
    private final int port;

    private ConnectUdpTarget(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Returns the (still unresolved) target host -- a hostname or an IP
     * literal, exactly as decoded from the request path.
     *
     * @return the target host
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the target port.
     *
     * @return the target port, in {@code [1, 65535]}
     */
    public int getPort() {
        return port;
    }

    /**
     * Parses a CONNECT-UDP request path against the RFC 9298 section 3
     * URI Template.
     *
     * @param path the request's {@code :path} pseudo-header value
     * @return the parsed target, or {@code null} if {@code path} does
     *         not match the template
     */
    public static ConnectUdpTarget parse(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith("/")) {
            return null;
        }
        String rest = path.substring(PATH_PREFIX.length(), path.length() - 1);
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String hostSegment = rest.substring(0, slash);
        String portSegment = rest.substring(slash + 1);
        if (hostSegment.isEmpty() || portSegment.isEmpty() || portSegment.indexOf('/') >= 0) {
            return null;
        }
        String host = percentDecode(hostSegment);
        if (host == null) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portSegment);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new ConnectUdpTarget(host, port);
    }

    /**
     * Encodes a target host/port into a CONNECT-UDP request path
     * matching the RFC 9298 section 3 URI Template.
     *
     * @param host the target host -- a hostname or an IP literal
     * @param port the target port, must be in {@code [1, 65535]}
     * @return the encoded {@code :path} value
     * @throws IllegalArgumentException if {@code port} is out of range
     */
    public static String encode(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        return PATH_PREFIX + percentEncode(host) + "/" + port + "/";
    }

    // RFC 3986 section 2.1: percent-encoding. Decodes strictly -- any
    // malformed escape (not exactly %XX with two hex digits) fails the
    // whole parse rather than silently passing through garbage bytes,
    // since this feeds directly into a DNS lookup / connection attempt.
    private static String percentDecode(String s) {
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

    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private static String percentEncode(String s) {
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
