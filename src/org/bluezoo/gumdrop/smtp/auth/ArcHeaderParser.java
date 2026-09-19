/*
 * ArcHeaderParser.java
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

package org.bluezoo.gumdrop.smtp.auth;

/**
 * Push-parser for ARC header fields (RFC 8617 section 4).
 *
 * <p>Follows the same event-driven pattern as {@link org.bluezoo.gumdrop.http.h2.H2Parser}:
 * each captured header is forwarded to an {@link ArcHeaderHandler} via typed
 * callbacks. Structural grouping and chain state live in the handler (typically
 * {@link ArcValidator}), not in a synchronous {@code parse()} return value.
 *
 * <p>Wire into {@link DkimMessageParser#setArcHeaderParser(ArcHeaderParser)} so
 * headers are delivered as the message arrives.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcHeaderHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8617">RFC 8617 - ARC</a>
 */
public final class ArcHeaderParser {

    private final ArcHeaderHandler handler;
    private boolean headersEnded;
    private final TagScan tagScan = new TagScan();

    /**
     * Creates a parser that delivers ARC events to the handler.
     *
     * @param handler the receiver for ARC header events; must not be null
     */
    public ArcHeaderParser(ArcHeaderHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    /**
     * Processes one complete header captured by {@link DkimMessageParser}.
     *
     * <p>Non-ARC headers are ignored. Does not verify signatures.
     *
     * @param header raw header line
     */
    public void header(DkimMessageParser.RawHeader header) {
        if (headersEnded || header == null) {
            return;
        }
        String lower = header.getName().toLowerCase();
        String line = header.asString();
        String value = headerValueAfterColon(line);
        boolean arcSeal = "arc-seal".equals(lower);
        tagScan.reset();
        scanTags(value, arcSeal, tagScan);
        int instance = tagScan.instance;
        if (instance < 1) {
            return;
        }
        if ("arc-authentication-results".equals(lower)) {
            handler.arcAuthenticationResults(instance, line);
        } else if ("arc-message-signature".equals(lower)) {
            DkimSignature parsed = DkimSignature.parse(value);
            handler.arcMessageSignature(instance, line, parsed);
        } else if (arcSeal) {
            DkimSignature parsed = DkimSignature.parse(value);
            handler.arcSeal(instance, line, parsed, tagScan.cv);
        }
    }

    /**
     * Signals end of the message header block (RFC 5322 empty line).
     *
     * <p>Invokes {@link ArcHeaderHandler#arcHeadersEnd()} once per message.
     */
    public void endHeaders() {
        if (headersEnded) {
            return;
        }
        headersEnded = true;
        handler.arcHeadersEnd();
    }

    /**
     * Resets parser state for the next message on the same handler.
     */
    public void reset() {
        headersEnded = false;
    }

    static String headerValueAfterColon(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return line.trim();
        }
        return line.substring(colon + 1).trim();
    }

    /**
     * Scans DKIM-style {@code tag=value} segments separated by {@code ;} in one
     * pass (no intermediate strings).
     *
     * @param headerValue header field-body after the colon
     * @param readCv when true, also look for {@code cv=}
     * @param out receives {@code i=} and optionally {@code cv=}
     */
    private static void scanTags(String headerValue, boolean readCv, TagScan out) {
        if (headerValue == null) {
            return;
        }
        int len = headerValue.length();
        int segStart = 0;
        while (segStart <= len) {
            int semi = headerValue.indexOf(';', segStart);
            int segEnd = semi >= 0 ? semi : len;
            int a = segStart;
            int b = segEnd;
            while (a < b && isHorizWs(headerValue.charAt(a))) {
                a++;
            }
            while (b > a && isHorizWs(headerValue.charAt(b - 1))) {
                b--;
            }
            if (b > a) {
                if (out.instance < 1 && tagAt(headerValue, a, b, 'i', '=')) {
                    out.instance = parsePositiveInt(headerValue, a + 2, b);
                } else if (readCv && tagAt(headerValue, a, b, 'c', 'v', '=')) {
                    out.cv = parseCvValue(headerValue, a + 3, b);
                }
            }
            if (semi < 0) {
                break;
            }
            segStart = semi + 1;
        }
    }

    private static boolean isHorizWs(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean tagAt(String s, int a, int b, char t0, char eq) {
        return b - a >= 2 && s.charAt(a) == t0 && s.charAt(a + 1) == eq;
    }

    private static boolean tagAt(String s, int a, int b, char t0, char t1, char eq) {
        return b - a >= 3 && s.charAt(a) == t0 && s.charAt(a + 1) == t1
                && s.charAt(a + 2) == eq;
    }

    private static int parsePositiveInt(String s, int start, int end) {
        int a = start;
        while (a < end && isHorizWs(s.charAt(a))) {
            a++;
        }
        if (a >= end) {
            return -1;
        }
        int n = 0;
        for (int i = a; i < end; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }

    private static ArcCvResult parseCvValue(String s, int start, int end) {
        int a = start;
        while (a < end && isHorizWs(s.charAt(a))) {
            a++;
        }
        int b = end;
        while (b > a && isHorizWs(s.charAt(b - 1))) {
            b--;
        }
        int len = b - a;
        if (len == 4 && asciiEqualsIgnoreCase(s, a, "pass")) {
            return ArcCvResult.PASS;
        }
        if (len == 4 && asciiEqualsIgnoreCase(s, a, "fail")) {
            return ArcCvResult.FAIL;
        }
        return ArcCvResult.NONE;
    }

    private static boolean asciiEqualsIgnoreCase(String s, int offset, String literal) {
        for (int i = 0; i < literal.length(); i++) {
            char a = s.charAt(offset + i);
            char b = literal.charAt(i);
            if (a >= 'A' && a <= 'Z') {
                a = (char) (a + ('a' - 'A'));
            }
            if (b >= 'A' && b <= 'Z') {
                b = (char) (b + ('a' - 'A'));
            }
            if (a != b) {
                return false;
            }
        }
        return true;
    }

    private static final class TagScan {
        int instance = -1;
        ArcCvResult cv = ArcCvResult.NONE;

        void reset() {
            instance = -1;
            cv = ArcCvResult.NONE;
        }
    }
}
