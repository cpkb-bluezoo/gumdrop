/*
 * DKIMSignature.java
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

package org.bluezoo.gumdrop.smtp.auth;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed DKIM-Signature header as defined in RFC 6376.
 *
 * <p>A DKIM signature contains tags that specify the signing domain,
 * selector, algorithm, signed headers, and the signature itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6376">RFC 6376 - DKIM</a>
 */
public class DKIMSignature {

    /** Version (v=) - always "1" for DKIM */
    private String version;

    /** Algorithm (a=) - e.g., "rsa-sha256", "ed25519-sha256" */
    private String algorithm;

    /** Signing domain (d=) */
    private String domain;

    /** Selector (s=) */
    private String selector;

    /** Canonicalization (c=) - header/body, e.g., "relaxed/simple" */
    private String canonicalization;

    /** Query method (q=) - usually "dns/txt" */
    private String queryMethod;

    /** Signed headers (h=) - colon-separated list */
    private List<String> signedHeaders;

    /** Body hash (bh=) - base64-encoded */
    private String bodyHash;

    /** Signature (b=) - base64-encoded */
    private String signature;

    /** Body length (l=) - optional */
    private long bodyLength;

    /** Signature timestamp (t=) - optional, Unix timestamp */
    private long timestamp;

    /** Signature expiration (x=) - optional, Unix timestamp */
    private long expiration;

    /** Agent or User Identifier (i=) - optional */
    private String identity;

    /** The raw header value for signature verification */
    private String rawHeader;

    /**
     * Creates an empty DKIM signature for parsing.
     */
    public DKIMSignature() {
        this.signedHeaders = new ArrayList<>();
        this.bodyLength = -1;
        this.timestamp = -1;
        this.expiration = -1;
    }

    /**
     * Parses a DKIM-Signature header value.
     *
     * @param headerValue the header value (after "DKIM-Signature:")
     * @return the parsed signature, or null if invalid
     */
    public static DKIMSignature parse(String headerValue) {
        if (headerValue == null) {
            return null;
        }

        DKIMSignature sig = new DKIMSignature();
        sig.rawHeader = headerValue;

        // Parse tag=value pairs
        String[] parts = splitOnSemicolons(headerValue);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }

            int eqPos = part.indexOf('=');
            if (eqPos <= 0) {
                continue;
            }

            String tag = part.substring(0, eqPos).trim();
            String value = part.substring(eqPos + 1).trim();

            // Remove folding whitespace from value
            value = unfold(value);

            if ("v".equals(tag)) {
                sig.version = value;
            } else if ("a".equals(tag)) {
                sig.algorithm = value;
            } else if ("d".equals(tag)) {
                sig.domain = value;
            } else if ("s".equals(tag)) {
                sig.selector = value;
            } else if ("c".equals(tag)) {
                sig.canonicalization = value;
            } else if ("q".equals(tag)) {
                sig.queryMethod = value;
            } else if ("h".equals(tag)) {
                sig.signedHeaders = parseHeaders(value);
            } else if ("bh".equals(tag)) {
                sig.bodyHash = removeWhitespace(value);
            } else if ("b".equals(tag)) {
                sig.signature = removeWhitespace(value);
            } else if ("l".equals(tag)) {
                sig.bodyLength = parseLong(value);
            } else if ("t".equals(tag)) {
                sig.timestamp = parseLong(value);
            } else if ("x".equals(tag)) {
                sig.expiration = parseLong(value);
            } else if ("i".equals(tag)) {
                sig.identity = value;
            }
        }

        // Validate required fields
        if (sig.version == null || sig.algorithm == null ||
            sig.domain == null || sig.selector == null ||
            sig.signedHeaders.isEmpty() || sig.bodyHash == null ||
            sig.signature == null) {
            return null;
        }

        return sig;
    }

    // -- Getters --

    public String getVersion() {
        return version;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getDomain() {
        return domain;
    }

    public String getSelector() {
        return selector;
    }

    public String getCanonicalization() {
        return canonicalization;
    }

    public String getQueryMethod() {
        return queryMethod;
    }

    public List<String> getSignedHeaders() {
        return signedHeaders;
    }

    public String getBodyHash() {
        return bodyHash;
    }

    public String getSignature() {
        return signature;
    }

    public long getBodyLength() {
        return bodyLength;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getExpiration() {
        return expiration;
    }

    public String getIdentity() {
        return identity;
    }

    public String getRawHeader() {
        return rawHeader;
    }

    /**
     * Returns the DNS query name for the public key.
     *
     * @return the DNS name: selector._domainkey.domain
     */
    public String getKeyQueryName() {
        return selector + "._domainkey." + domain;
    }

    /**
     * Returns the header canonicalization method.
     *
     * @return "simple" or "relaxed"
     */
    public String getHeaderCanonicalization() {
        if (canonicalization == null) {
            return "simple";
        }
        int slashPos = canonicalization.indexOf('/');
        if (slashPos > 0) {
            return canonicalization.substring(0, slashPos);
        }
        return canonicalization;
    }

    /**
     * Returns the body canonicalization method.
     *
     * @return "simple" or "relaxed"
     */
    public String getBodyCanonicalization() {
        if (canonicalization == null) {
            return "simple";
        }
        int slashPos = canonicalization.indexOf('/');
        if (slashPos > 0) {
            return canonicalization.substring(slashPos + 1);
        }
        return "simple";
    }

    // -- Helper Methods --

    private static String[] splitOnSemicolons(String s) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ';') {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            parts.add(s.substring(start));
        }
        return parts.toArray(new String[0]);
    }

    private static List<String> parseHeaders(String value) {
        List<String> headers = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                String h = value.substring(start, i).trim();
                if (!h.isEmpty()) {
                    headers.add(h.toLowerCase());
                }
                start = i + 1;
            }
        }
        String last = value.substring(start).trim();
        if (!last.isEmpty()) {
            headers.add(last.toLowerCase());
        }
        return headers;
    }

    private static String unfold(String s) {
        StringBuilder sb = new StringBuilder();
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString();
    }

    private static String removeWhitespace(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

}


