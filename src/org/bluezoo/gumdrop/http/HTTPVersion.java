/*
 * HTTPVersion.java
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

package org.bluezoo.gumdrop.http;

import java.util.Iterator;
import java.util.List;

/**
 * Enum of possible HTTP versions.
 *
 * <p>RFC 9112 section 2.3 defines: HTTP-version = HTTP-name "/" DIGIT "." DIGIT
 * where HTTP-name = %s"HTTP".
 * 
 * <p>This enum handles both traditional HTTP version strings (e.g., "HTTP/1.1")
 * and ALPN negotiation identifiers (e.g., "h2", "h2c") used during protocol
 * negotiation.
 *
 * @author Chris Burdess
 */
public enum HTTPVersion {

    UNKNOWN(null, null),
    HTTP_1_0("HTTP/1.0", "http/1.0"),
    HTTP_1_1("HTTP/1.1", "http/1.1"),
    HTTP_2_0("HTTP/2.0", "h2"),
    HTTP_3("HTTP/3", "h3");

    private final String versionString;
    private final String alpnIdentifier;

    /**
     * Creates an HTTP version with the specified version string and ALPN identifier.
     * 
     * @param versionString the HTTP version string (e.g., "HTTP/1.1")
     * @param alpnIdentifier the ALPN negotiation identifier (e.g., "h2")
     */
    private HTTPVersion(String versionString, String alpnIdentifier) {
        this.versionString = versionString;
        this.alpnIdentifier = alpnIdentifier;
    }

    /**
     * Returns the HTTP version string (e.g., "HTTP/1.1").
     * 
     * @return the version string, or "(unknown)" for UNKNOWN
     */
    @Override
    public String toString() {
        return versionString == null ? "(unknown)" : versionString;
    }

    /**
     * Returns the ALPN identifier for this HTTP version.
     * 
     * @return the ALPN identifier (e.g., "h2"), or null if not applicable
     */
    public String getAlpnIdentifier() {
        return alpnIdentifier;
    }

    /**
     * Parses an HTTP version from a version string (e.g., "HTTP/1.1", "HTTP/2.0").
     * 
     * @param versionString the HTTP version string to parse
     * @return the corresponding HTTPVersion, or UNKNOWN if not recognized
     */
    public static HTTPVersion fromVersionString(String versionString) {
        if (versionString == null) {
            return UNKNOWN;
        }
        
        for (HTTPVersion v : values()) {
            if (versionString.equals(v.versionString)) {
                return v;
            }
        }
        return UNKNOWN;
    }

    /**
     * Parses an HTTP version from an ALPN identifier (e.g., "h2", "h2c", "http/1.1").
     *
     * <p>RFC 9113 section 3.2: "h2" identifies HTTP/2 over TLS via ALPN.
     * RFC 9113 section 3.1: "h2c" identifies cleartext HTTP/2 (deprecated
     * by RFC 9113 but intentionally supported for backwards compatibility).
     * 
     * @param alpnIdentifier the ALPN identifier to parse
     * @return the corresponding HTTPVersion, or UNKNOWN if not recognized
     */
    public static HTTPVersion fromAlpnIdentifier(String alpnIdentifier) {
        if (alpnIdentifier == null) {
            return UNKNOWN;
        }
        
        // RFC 9113 section 3.1: h2c is deprecated but intentionally supported
        if ("h2c".equals(alpnIdentifier)) {
            return HTTP_2_0;
        }
        
        for (HTTPVersion v : values()) {
            if (alpnIdentifier.equals(v.alpnIdentifier)) {
                return v;
            }
        }
        return UNKNOWN;
    }

    /**
     * Parses an HTTP version from either a version string or ALPN identifier.
     * 
     * <p>This method first tries to parse as an ALPN identifier, then falls back
     * to parsing as a version string. This handles cases where the input could
     * be either format.
     * 
     * @param s the string to parse (version string or ALPN identifier)
     * @return the corresponding HTTPVersion, or UNKNOWN if not recognized
     */
    public static HTTPVersion fromString(String s) {
        // Try ALPN identifier first (more common in modern usage)
        HTTPVersion alpnResult = fromAlpnIdentifier(s);
        if (alpnResult != UNKNOWN) {
            return alpnResult;
        }
        
        // Fall back to version string
        return fromVersionString(s);
    }

    /**
     * Checks if this HTTP version supports multiplexing (multiple concurrent streams).
     * 
     * @return true for HTTP/2, false for HTTP/1.x
     */
    public boolean supportsMultiplexing() {
        return this == HTTP_2_0 || this == HTTP_3;
    }

    /**
     * Checks if this HTTP version requires a Host header.
     * 
     * @return true for HTTP/1.1 and HTTP/2, false for HTTP/1.0
     */
    public boolean requiresHostHeader() {
        return this == HTTP_1_1 || this == HTTP_2_0 || this == HTTP_3;
    }

    /**
     * HTTP/1 connection-specific and message-framing header fields that have
     * no role on HTTP/2 or HTTP/3 (body length is defined by DATA frames).
     * Peers may send these incorrectly; strip on ingest rather than rejecting
     * the stream. Outbound multiplexed messages must not include them.
     *
     * @param name header field name
     * @param value header field value
     * @return true if the header should be stripped from a multiplexed message
     */
    public static boolean isHttp1FramingHeader(String name, String value) {
        String lower = name.toLowerCase();
        if ("connection".equals(lower) || "keep-alive".equals(lower)
                || "proxy-connection".equals(lower)
                || "upgrade".equals(lower)
                || "transfer-encoding".equals(lower)
                || "content-length".equals(lower)) {
            return true;
        }
        return "te".equals(lower) && !"trailers".equals(value);
    }

    /**
     * Strips {@link #isHttp1FramingHeader HTTP/1 framing headers} from a
     * multiplexed-protocol header block before dispatch to handlers.
     */
    public static void stripHttp1FramingHeaders(Headers headers) {
        if (headers == null) {
            return;
        }
        Iterator<Header> it = headers.iterator();
        while (it.hasNext()) {
            Header header = it.next();
            if (header.getName().startsWith(":")) {
                continue;
            }
            if (isHttp1FramingHeader(header.getName(), header.getValue())) {
                it.remove();
            }
        }
    }

    /**
     * Strips {@link #isHttp1FramingHeader HTTP/1 framing headers} from a
     * multiplexed-protocol header list before sending.
     */
    public static void stripHttp1FramingHeaders(List<Header> headers) {
        if (headers == null) {
            return;
        }
        Iterator<Header> it = headers.iterator();
        while (it.hasNext()) {
            Header header = it.next();
            if (header.getName().startsWith(":")) {
                continue;
            }
            if (isHttp1FramingHeader(header.getName(), header.getValue())) {
                it.remove();
            }
        }
    }
}
