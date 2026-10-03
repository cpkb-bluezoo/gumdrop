/*
 * FieldDispatcher.java
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
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentDispositionParser;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.ContentTypeParser;

/**
 * Turns the ordinary fields of a header section into {@link
 * HttpMessageHandler} events: the typed ones ({@code Content-Type},
 * {@code Content-Disposition}, {@code Content-Length}) as typed events, the
 * rest, and any typed field whose value does not parse, as {@code header}.
 * Both the HTTP/1.x parser and the HTTP/2 and HTTP/3 field-section adapter use
 * it, so a given field produces the same events whichever protocol carried
 * it.
 *
 * <p>It also keeps the small amount of state the message framing depends on
 * (the {@code Content-Length} seen, and the shape of the
 * {@code Transfer-Encoding}), because that is decided by several field lines
 * taken together, and rejects framing that contradicts itself, which is how
 * request smuggling is attempted (RFC 9112 section 6.3). It does not decide
 * how the body is framed; the protocol does.
 *
 * <p>Pseudo-headers and protocol-specific rules are not its business. Call
 * {@link #reset()} at the start of each message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class FieldDispatcher {

    private final HttpMessageHandler handler;
    private CharsetDecoder latin1;
    private HttpDateFormat dates;

    private boolean haveContentLength;
    private long contentLength;
    private boolean haveTransferEncoding;
    private boolean chunkedSeen;
    private boolean lastCodingChunked;

    /**
     * Creates a dispatcher that sends its events, and any error, to
     * {@code handler}.
     *
     * @param handler the receiver
     */
    public FieldDispatcher(HttpMessageHandler handler) {
        this.handler = handler;
    }

    /** Forgets the framing state of the previous message. */
    public void reset() {
        haveContentLength = false;
        contentLength = 0;
        haveTransferEncoding = false;
        chunkedSeen = false;
        lastCodingChunked = false;
    }

    /**
     * Returns whether a {@code Content-Length} field has been seen.
     *
     * @return true if one has
     */
    public boolean hasContentLength() {
        return haveContentLength;
    }

    /**
     * Returns the value of the {@code Content-Length} field seen.
     *
     * @return the length, meaningful only if {@link #hasContentLength()}
     */
    public long getContentLength() {
        return contentLength;
    }

    /**
     * Returns whether a {@code Transfer-Encoding} field has been seen.
     *
     * @return true if one has
     */
    public boolean hasTransferEncoding() {
        return haveTransferEncoding;
    }

    /**
     * Returns whether the last transfer coding named so far is
     * {@code chunked}, which is what makes a body chunked (RFC 9112
     * section 6.1).
     *
     * @return true if chunked is the final coding
     */
    public boolean isLastCodingChunked() {
        return lastCodingChunked;
    }

    /**
     * Dispatches one field of the header section.
     *
     * @param name the lower-case field name
     * @param value the value octets
     * @return true if the field was reported; false if it contradicted the
     *     framing (or was malformed) and the handler has been given an
     *     {@code error}, after which nothing more must be sent to it
     */
    public boolean field(String name, ByteBuffer value) {
        if (name.equals("content-length")) {
            return contentLengthField(name, value);
        }
        if (name.equals("transfer-encoding")) {
            return transferEncodingField(name, value);
        }
        if (name.equals("content-type")) {
            ContentType type = ContentTypeParser.parse(value.duplicate(), decoder());
            if (type != null) {
                handler.contentType(type);
                return true;
            }
        } else if (isDateField(name)) {
            java.time.Instant when = date(value);
            if (when != null) {
                handler.dateHeader(name, when);
                return true;
            }
        } else if (name.equals("retry-after")) {
            // delay-seconds, or an HTTP-date (RFC 9110 section 10.2.3)
            long seconds = digits(value);
            if (seconds >= 0) {
                handler.longHeader(name, seconds);
                return true;
            }
            java.time.Instant when = date(value);
            if (when != null) {
                handler.dateHeader(name, when);
                return true;
            }
        } else if (name.equals("content-disposition")) {
            ContentDisposition disposition = ContentDispositionParser.parse(value.duplicate(), decoder());
            if (disposition != null) {
                handler.contentDisposition(disposition);
                return true;
            }
        }
        handler.header(name, value);
        return true;
    }

    /**
     * Dispatches one field of a trailer section. Trailers carry no framing
     * state, and a field that must be known before the content (framing,
     * routing, request modifiers, authentication; RFC 9110 section 6.5.1) is
     * dropped, since a recipient may ignore trailer fields. Date-valued fields
     * and {@code retry-after} are typed; the rest are plain {@code header}
     * events. Content coding does not apply: the content has already been read.
     *
     * @param name the lower-case field name
     * @param value the value octets
     */
    public void trailerField(String name, ByteBuffer value) {
        if (isForbiddenInTrailers(name)) {
            return;
        }
        if (name.equals("retry-after")) {
            long seconds = digits(value);
            if (seconds >= 0) {
                handler.longHeader(name, seconds);
                return;
            }
        }
        if (name.equals("date") || name.equals("expires") || name.equals("last-modified")
                || name.equals("retry-after")) {
            java.time.Instant when = date(value);
            if (when != null) {
                handler.dateHeader(name, when);
                return;
            }
        }
        handler.header(name, value);
    }

    private static boolean isForbiddenInTrailers(String name) {
        return name.equals("content-length") || name.equals("transfer-encoding")
                || name.equals("host") || name.equals("trailer") || name.equals("te")
                || name.equals("max-forwards") || name.equals("cache-control")
                || name.equals("authorization") || name.equals("proxy-authorization")
                || name.equals("www-authenticate") || name.equals("proxy-authenticate")
                || name.equals("cookie") || name.equals("set-cookie")
                || name.startsWith("if-") || name.equals("expect")
                || name.equals("range") || name.equals("connection");
    }

    /** The fields whose whole value is an HTTP-date (RFC 9110 section 5.6.7). */
    private static boolean isDateField(String name) {
        return name.equals("date") || name.equals("expires") || name.equals("last-modified")
                || name.equals("if-modified-since") || name.equals("if-unmodified-since")
                || name.equals("if-range");
    }

    /** The instant an HTTP-date value names, or null if it is not one. */
    private java.time.Instant date(ByteBuffer value) {
        int n = value.remaining();
        if (n < 20 || n > 40) {
            return null;
        }
        byte[] octets = new byte[n];
        value.duplicate().get(octets);
        for (int i = 0; i < n; i++) {
            if (octets[i] < 0x20 || octets[i] > 0x7E) {
                return null;
            }
        }
        if (dates == null) {
            dates = new HttpDateFormat();
        }
        try {
            java.util.Date parsed = dates.parse(new String(octets, java.nio.charset.StandardCharsets.US_ASCII),
                    new java.text.ParsePosition(0));
            if (parsed == null) {
                return null;
            }
            java.time.Instant when = parsed.toInstant();
            // a two-digit year in the wrong form is read as year 94, not 1994
            return when.atOffset(java.time.ZoneOffset.UTC).getYear() < 1900 ? null : when;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The number a value consists wholly of digits for, or -1. */
    private static long digits(ByteBuffer value) {
        int from = value.position();
        int to = value.limit();
        if (from == to || to - from > 18) {
            return -1;
        }
        long n = 0;
        for (int i = from; i < to; i++) {
            byte b = value.get(i);
            if (b < '0' || b > '9') {
                return -1;
            }
            n = n * 10 + (b - '0');
        }
        return n;
    }

    private boolean fail(HttpError error, String detail) {
        handler.error(error, detail);
        return false;
    }

    /** Content-Length = 1*DIGIT (RFC 9110 section 8.6). */
    private boolean contentLengthField(String name, ByteBuffer value) {
        int from = value.position();
        int to = value.limit();
        if (from == to) {
            return fail(HttpError.MALFORMED, "empty Content-Length");
        }
        long n = 0;
        for (int i = from; i < to; i++) {
            byte b = value.get(i);
            if (b < '0' || b > '9') {
                return fail(HttpError.MALFORMED, "Content-Length is not a number");
            }
            int digit = b - '0';
            if (n > (Long.MAX_VALUE - digit) / 10) {
                return fail(HttpError.MALFORMED, "Content-Length is too large");
            }
            n = n * 10 + digit;
        }
        if (haveTransferEncoding) {
            return fail(HttpError.FRAMING_CONFLICT, "Content-Length with Transfer-Encoding");
        }
        if (haveContentLength && n != contentLength) {
            return fail(HttpError.FRAMING_CONFLICT, "conflicting Content-Length values");
        }
        haveContentLength = true;
        contentLength = n;
        handler.longHeader(name, n);
        return true;
    }

    /** Transfer-Encoding = #transfer-coding; chunked must be last and used once (RFC 9112 section 6.1). */
    private boolean transferEncodingField(String name, ByteBuffer value) {
        if (haveContentLength) {
            return fail(HttpError.FRAMING_CONFLICT, "Transfer-Encoding with Content-Length");
        }
        int i = value.position();
        int to = value.limit();
        int tokens = 0;
        while (i < to) {
            while (i < to && (isOws(value.get(i)) || value.get(i) == ',')) {
                i++;
            }
            int s = i;
            while (i < to && HttpUtils.isTokenChar(value.get(i))) {
                i++;
            }
            if (i == s) {
                if (i < to) {
                    return fail(HttpError.MALFORMED, "malformed Transfer-Encoding");
                }
                break;
            }
            tokens++;
            if (isChunked(value, s, i)) {
                if (chunkedSeen) {
                    return fail(HttpError.MALFORMED, "chunked applied more than once");
                }
                chunkedSeen = true;
                lastCodingChunked = true;
            } else {
                lastCodingChunked = false;
            }
            // what follows a coding is white space, a comma or the end
            while (i < to && isOws(value.get(i))) {
                i++;
            }
            if (i < to && value.get(i) != ',') {
                return fail(HttpError.MALFORMED, "malformed Transfer-Encoding");
            }
        }
        if (tokens == 0) {
            return fail(HttpError.MALFORMED, "empty Transfer-Encoding");
        }
        haveTransferEncoding = true;
        handler.header(name, value);
        return true;
    }

    private static boolean isChunked(ByteBuffer b, int from, int to) {
        if (to - from != 7) {
            return false;
        }
        String chunked = "chunked";
        for (int i = 0; i < 7; i++) {
            byte c = b.get(from + i);
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            if (c != chunked.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOws(byte b) {
        return b == ' ' || b == '\t';
    }

    private CharsetDecoder decoder() {
        if (latin1 == null) {
            latin1 = StandardCharsets.ISO_8859_1.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
        }
        return latin1;
    }

}
