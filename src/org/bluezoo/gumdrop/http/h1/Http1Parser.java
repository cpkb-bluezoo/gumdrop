/*
 * Http1Parser.java
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

package org.bluezoo.gumdrop.http.h1;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.http.FieldDispatcher;
import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpUtils;
import org.bluezoo.gumdrop.http.HttpVersion;

/**
 * Push parser for HTTP/1.x messages (RFC 9112), reporting each message to an
 * {@link HttpMessageHandler} as events.
 *
 * <h4>Feeding it</h4>
 * <p>Call {@link #receive(ByteBuffer)} with whatever bytes have arrived. The
 * parser consumes every unit that is complete and leaves the buffer's position
 * at the start of the first that is not (a start line, a field line together
 * with any folds that continue it, or a chunk header), setting {@link
 * #isUnderflow()}. The caller then {@code compact()}s the buffer and reads
 * more bytes in after what is left, so the unfinished unit is seen again in
 * full. Body bytes are never held back: they are passed on as they arrive.
 *
 * <h4>What the events are</h4>
 * <p>A request parser reports {@code method}, {@code target} and
 * {@code version}; a response parser {@code version}, {@code status} and
 * {@code reason}. Each start line is reported only once all of it is valid.
 * Fields follow, one event per field line, with a few typed (see {@link
 * HttpMessageHandler}); then {@code endHeaders}, the body, any trailers, and
 * {@code endMessage}. Several messages may follow one another in the input, as
 * with pipelined requests or a run of responses on one connection.
 *
 * <p>Field values, targets and body data are read-only views of the bytes in
 * the buffer passed to {@code receive}, not copies, and are valid only for the
 * duration of the event. The one exception is a field folded over several
 * lines (RFC 9112 section 5.2, obsolete but still seen from old servers): its
 * value is not contiguous on the wire, so it is rebuilt into a new buffer with
 * each fold replaced by a single space.
 *
 * <h4>Strictness</h4>
 * <p>This is a strict parser, because leniency between HTTP implementations is
 * how request smuggling works. Line ends must be CRLF, field names must be
 * tokens with nothing between the name and the colon, control characters are
 * refused in values, and framing that is ambiguous ({@code Content-Length}
 * values that disagree, or {@code Content-Length} together with
 * {@code Transfer-Encoding}) is an error. Octets above 0x7F in a value are not
 * an error: they are opaque (RFC 9110 section 5.5).
 *
 * <h4>Errors</h4>
 * <p>On any error the handler receives {@code error} and nothing after it;
 * further input is discarded, since framing is lost and the connection must
 * be closed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Http1Parser {

    /** Default limit on a start line, in octets (RFC 9112 section 3 asks for at least 8000). */
    public static final int DEFAULT_MAX_START_LINE_LENGTH = 8192;

    /** Default limit on a field line, folds included, in octets. */
    public static final int DEFAULT_MAX_FIELD_LINE_LENGTH = 8192;

    /** Default limit on the number of fields in a header section or trailer section. */
    public static final int DEFAULT_MAX_FIELD_COUNT = 100;

    private enum State {
        START_LINE,
        FIELDS,
        BODY_LENGTH,
        CHUNK_SIZE,
        CHUNK_DATA,
        CHUNK_DATA_END,
        TRAILERS,
        BODY_UNTIL_CLOSE,
        TUNNEL,
        FAILED
    }

    private final HttpMessageHandler handler;
    private final FieldDispatcher fields;
    private final boolean request;

    private State state = State.START_LINE;
    private boolean underflow;
    private ByteBuffer view;

    private int maxStartLineLength = DEFAULT_MAX_START_LINE_LENGTH;
    private int maxFieldLineLength = DEFAULT_MAX_FIELD_LINE_LENGTH;
    private int maxFieldCount = DEFAULT_MAX_FIELD_COUNT;

    /** The method of the request a response answers, set by the caller. */
    private HttpMethod responseTo;

    // per message
    private HttpVersion version = HttpVersion.UNKNOWN;
    private int status;
    private int fieldCount;
    private long remaining;

    private Http1Parser(HttpMessageHandler handler, boolean request) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.fields = new FieldDispatcher(handler);
        this.request = request;
    }

    /**
     * Creates a parser for requests, as read by a server.
     *
     * @param handler receives the events
     * @return the parser
     */
    public static Http1Parser forRequests(HttpMessageHandler handler) {
        return new Http1Parser(handler, true);
    }

    /**
     * Creates a parser for responses, as read by a client.
     *
     * @param handler receives the events
     * @return the parser
     */
    public static Http1Parser forResponses(HttpMessageHandler handler) {
        return new Http1Parser(handler, false);
    }

    /**
     * Sets the longest start line accepted, in octets. A request-target longer
     * than the limit is reported as {@link HttpError#URI_TOO_LONG}.
     *
     * @param max the limit
     */
    public void setMaxStartLineLength(int max) {
        this.maxStartLineLength = max;
    }

    /**
     * Sets the longest field line accepted, in octets, counting any folds.
     *
     * @param max the limit
     */
    public void setMaxFieldLineLength(int max) {
        this.maxFieldLineLength = max;
    }

    /**
     * Sets the most fields accepted in a header section, and in a trailer
     * section.
     *
     * @param max the limit
     */
    public void setMaxFieldCount(int max) {
        this.maxFieldCount = max;
    }

    /**
     * Tells a response parser which method the next final response answers.
     * A response to {@code HEAD} has no body whatever its fields say, and a
     * successful response to {@code CONNECT} turns the connection into a
     * tunnel (RFC 9112 section 6.3). Call it before the response arrives; it
     * applies once.
     *
     * @param method the request method
     */
    public void expectResponseTo(HttpMethod method) {
        this.responseTo = method;
    }

    /**
     * Returns whether the last {@link #receive} left an incomplete unit at
     * the buffer's position.
     *
     * @return true if more bytes are needed before parsing can go further
     */
    public boolean isUnderflow() {
        return underflow;
    }

    /**
     * Returns whether the connection has stopped being HTTP/1.x, after a
     * {@code 101} response or a successful response to {@code CONNECT}. The
     * parser leaves the bytes that follow untouched in the buffer; they belong
     * to the other protocol.
     *
     * @return true after a protocol switch or tunnel
     */
    public boolean isTunnel() {
        return state == State.TUNNEL;
    }

    /**
     * Parses what is available. See the class description for how the buffer
     * is used.
     *
     * @param data the bytes received so far, from the position to the limit
     */
    public void receive(ByteBuffer data) {
        underflow = false;
        view = data.asReadOnlyBuffer();
        while (data.hasRemaining()) {
            boolean progressed;
            switch (state) {
                case START_LINE:
                    progressed = startLine(data);
                    break;
                case FIELDS:
                    progressed = fieldLine(data, false);
                    break;
                case TRAILERS:
                    progressed = fieldLine(data, true);
                    break;
                case BODY_LENGTH:
                    progressed = bodyOfKnownLength(data, false);
                    break;
                case CHUNK_DATA:
                    progressed = bodyOfKnownLength(data, true);
                    break;
                case CHUNK_SIZE:
                    progressed = chunkSize(data);
                    break;
                case CHUNK_DATA_END:
                    progressed = chunkDataEnd(data);
                    break;
                case BODY_UNTIL_CLOSE:
                    emitBody(data, data.position(), data.limit());
                    data.position(data.limit());
                    progressed = true;
                    break;
                case TUNNEL:
                    return;
                case FAILED:
                default:
                    // framing is lost; take the input so a caller does not loop
                    data.position(data.limit());
                    return;
            }
            if (!progressed) {
                underflow = true;
                return;
            }
        }
    }

    /**
     * Signals that the peer closed its end of the connection. A response that
     * runs until close is complete; a message cut off anywhere else is an
     * error; a close between messages is nothing.
     */
    public void close() {
        switch (state) {
            case BODY_UNTIL_CLOSE:
                state = State.START_LINE;
                handler.endMessage();
                break;
            case START_LINE:
                if (underflow) {
                    fail(HttpError.MALFORMED, "connection closed in the middle of a start line");
                }
                break;
            case TUNNEL:
            case FAILED:
                break;
            default:
                fail(HttpError.MALFORMED, "connection closed in the middle of a message");
                break;
        }
    }

    // ---- start line ----

    private boolean startLine(ByteBuffer d) {
        if (request) {
            // RFC 9112 section 2.2: ignore at least one empty line before the request line
            while (d.remaining() >= 1 && d.get(d.position()) == '\r') {
                if (d.remaining() < 2) {
                    return false;
                }
                if (d.get(d.position() + 1) != '\n') {
                    return fail(HttpError.MALFORMED, "bare CR before the request line");
                }
                d.position(d.position() + 2);
            }
            if (!d.hasRemaining()) {
                return true;
            }
        }
        int start = d.position();
        int limit = d.limit();
        int cr = -1;
        for (int i = start; i < limit; i++) {
            byte b = d.get(i);
            if (b == '\n') {
                return fail(HttpError.MALFORMED, "bare LF in the start line");
            }
            if (b == '\r') {
                cr = i;
                break;
            }
            if (i - start >= maxStartLineLength) {
                return tooLongStartLine();
            }
        }
        if (cr < 0) {
            return false;
        }
        if (cr - start > maxStartLineLength) {
            return tooLongStartLine();
        }
        if (cr + 1 >= limit) {
            return false;
        }
        if (d.get(cr + 1) != '\n') {
            return fail(HttpError.MALFORMED, "bare CR in the start line");
        }
        boolean ok = request ? requestLine(d, start, cr) : statusLine(d, start, cr);
        if (!ok) {
            return true;    // fail() already reported it
        }
        d.position(cr + 2);
        return true;
    }

    private boolean tooLongStartLine() {
        return fail(request ? HttpError.URI_TOO_LONG : HttpError.MALFORMED, "start line too long");
    }

    private static int indexOf(ByteBuffer d, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (d.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /** request-line = method SP request-target SP HTTP-version (RFC 9112 section 3). */
    private boolean requestLine(ByteBuffer d, int start, int end) {
        int sp1 = indexOf(d, start, end, (byte) ' ');
        if (sp1 <= start) {
            fail(HttpError.MALFORMED, "malformed request line");
            return false;
        }
        for (int i = start; i < sp1; i++) {
            if (!isTokenChar(d.get(i))) {
                fail(HttpError.MALFORMED, "invalid method");
                return false;
            }
        }
        int sp2 = indexOf(d, sp1 + 1, end, (byte) ' ');
        if (sp2 < 0 || sp2 == sp1 + 1) {
            fail(HttpError.MALFORMED, "malformed request line");
            return false;
        }
        for (int i = sp1 + 1; i < sp2; i++) {
            byte b = d.get(i);
            if (b < 0x21 || b > 0x7E) {
                fail(HttpError.MALFORMED, "invalid character in the request target");
                return false;
            }
        }
        HttpVersion v = parseVersion(d, sp2 + 1, end);
        if (v == null) {
            return false;
        }
        this.version = v;
        handler.method(HttpMethod.of(slice(start, sp1)));
        handler.target(slice(sp1 + 1, sp2));
        handler.version(v);
        beginFields();
        return true;
    }

    /** status-line = HTTP-version SP status-code SP [ reason-phrase ] (RFC 9112 section 4). */
    private boolean statusLine(ByteBuffer d, int start, int end) {
        int sp1 = indexOf(d, start, end, (byte) ' ');
        if (sp1 < 0) {
            fail(HttpError.MALFORMED, "malformed status line");
            return false;
        }
        HttpVersion v = parseVersion(d, start, sp1);
        if (v == null) {
            return false;
        }
        if (end - (sp1 + 1) < 3) {
            fail(HttpError.MALFORMED, "malformed status code");
            return false;
        }
        int code = 0;
        for (int i = sp1 + 1; i < sp1 + 4; i++) {
            byte b = d.get(i);
            if (b < '0' || b > '9') {
                fail(HttpError.MALFORMED, "malformed status code");
                return false;
            }
            code = code * 10 + (b - '0');
        }
        if (code < 100 || code > 599) {
            fail(HttpError.MALFORMED, "status code out of range");
            return false;
        }
        int reasonStart = end;
        if (end > sp1 + 4) {
            if (d.get(sp1 + 4) != ' ') {
                fail(HttpError.MALFORMED, "malformed status line");
                return false;
            }
            reasonStart = sp1 + 5;
        }
        for (int i = reasonStart; i < end; i++) {
            byte b = d.get(i);
            if ((b >= 0 && b < 0x20 && b != '\t') || b == 0x7F) {
                fail(HttpError.MALFORMED, "control character in the reason phrase");
                return false;
            }
        }
        this.version = v;
        this.status = code;
        handler.version(v);
        handler.status(code);
        handler.reason(slice(reasonStart, end));
        beginFields();
        return true;
    }

    /** HTTP-version = "HTTP/" DIGIT "." DIGIT; reports and returns null if unacceptable. */
    private HttpVersion parseVersion(ByteBuffer d, int from, int to) {
        if (to - from != 8 || d.get(from) != 'H' || d.get(from + 1) != 'T' || d.get(from + 2) != 'T'
                || d.get(from + 3) != 'P' || d.get(from + 4) != '/' || d.get(from + 6) != '.') {
            fail(HttpError.MALFORMED, "malformed HTTP version");
            return null;
        }
        byte major = d.get(from + 5);
        byte minor = d.get(from + 7);
        if (major < '0' || major > '9' || minor < '0' || minor > '9') {
            fail(HttpError.MALFORMED, "malformed HTTP version");
            return null;
        }
        if (major == '1' && minor == '1') {
            return HttpVersion.HTTP_1_1;
        }
        if (major == '1' && minor == '0') {
            return HttpVersion.HTTP_1_0;
        }
        fail(HttpError.UNSUPPORTED_VERSION, "unsupported HTTP version");
        return null;
    }

    private void beginFields() {
        state = State.FIELDS;
        fieldCount = 0;
        fields.reset();
    }

    // ---- field lines ----

    /**
     * Parses one field line, or the empty line that ends the section.
     * {@code trailers} selects the trailer section after a chunked body.
     */
    private boolean fieldLine(ByteBuffer d, boolean trailers) {
        int start = d.position();
        int limit = d.limit();
        byte first = d.get(start);
        if (first == '\r') {
            if (limit - start < 2) {
                return false;
            }
            if (d.get(start + 1) != '\n') {
                return fail(HttpError.MALFORMED, "bare CR");
            }
            d.position(start + 2);
            if (trailers) {
                finishMessage();
                return true;
            }
            endOfHeaders();
            return true;
        }
        if (first == '\n') {
            return fail(HttpError.MALFORMED, "bare LF");
        }

        // Find the end of the field line: a CRLF not followed by SP or HTAB.
        boolean folded = false;
        int end = -1;
        int i = start;
        while (true) {
            if (i >= limit) {
                if (i - start > maxFieldLineLength) {
                    return fail(HttpError.FIELD_SECTION_TOO_LARGE, "field line too long");
                }
                return false;
            }
            byte c = d.get(i);
            if (c == '\n') {
                return fail(HttpError.MALFORMED, "bare LF in a field line");
            }
            if (c == '\r') {
                if (i + 1 >= limit) {
                    if (i - start > maxFieldLineLength) {
                        return fail(HttpError.FIELD_SECTION_TOO_LARGE, "field line too long");
                    }
                    return false;
                }
                if (d.get(i + 1) != '\n') {
                    return fail(HttpError.MALFORMED, "bare CR in a field line");
                }
                if (i + 2 >= limit) {
                    // the byte after the CRLF says whether the line continues
                    if (i - start > maxFieldLineLength) {
                        return fail(HttpError.FIELD_SECTION_TOO_LARGE, "field line too long");
                    }
                    return false;
                }
                byte next = d.get(i + 2);
                if (next == ' ' || next == '\t') {
                    folded = true;
                    i += 2;
                    continue;
                }
                end = i;
                break;
            }
            if (i - start >= maxFieldLineLength) {
                return fail(HttpError.FIELD_SECTION_TOO_LARGE, "field line too long");
            }
            i++;
        }
        if (end - start > maxFieldLineLength) {
            return fail(HttpError.FIELD_SECTION_TOO_LARGE, "field line too long");
        }
        if (++fieldCount > maxFieldCount) {
            return fail(HttpError.FIELD_SECTION_TOO_LARGE, "too many fields");
        }
        field(d, start, end, folded, trailers);
        if (state == State.FAILED) {
            return true;
        }
        d.position(end + 2);
        return true;
    }

    /** field-line = field-name ":" OWS field-value OWS (RFC 9112 section 5). */
    private void field(ByteBuffer d, int start, int end, boolean folded, boolean trailer) {
        int colon = -1;
        for (int i = start; i < end; i++) {
            byte b = d.get(i);
            if (b == ':') {
                colon = i;
                break;
            }
            if (!isTokenChar(b)) {
                fail(HttpError.MALFORMED, "invalid character in a field name");
                return;
            }
        }
        if (colon <= start) {
            fail(HttpError.MALFORMED, colon < 0 ? "no colon in a field line" : "empty field name");
            return;
        }
        String name = lowerCase(d, start, colon);
        ByteBuffer value;
        if (folded) {
            value = unfold(d, colon + 1, end);
        } else {
            int vs = colon + 1;
            int ve = end;
            while (vs < ve && isOws(d.get(vs))) {
                vs++;
            }
            while (ve > vs && isOws(d.get(ve - 1))) {
                ve--;
            }
            value = slice(vs, ve);
        }
        for (int i = value.position(); i < value.limit(); i++) {
            if (!HttpUtils.isFieldValueOctet(value.get(i))) {
                fail(HttpError.MALFORMED, "control character in the value of " + name);
                return;
            }
        }
        if (trailer) {
            handler.trailer(name, value);
        } else if (request && name.equals("host")) {
            // The authority, as :authority is in HTTP/2 and HTTP/3. Whether the
            // request has exactly one is for the protocol layer (RFC 9112 section 3.2).
            if (!isValidHost(value)) {
                fail(HttpError.MALFORMED, "invalid Host");
                return;
            }
            handler.authority(value);
        } else if (!fields.field(name, value)) {
            state = State.FAILED;
        }
    }

    /** Host = uri-host [ ":" port ]: no white space and no userinfo or path (RFC 9110 section 7.2). */
    private static boolean isValidHost(ByteBuffer value) {
        for (int i = value.position(); i < value.limit(); i++) {
            byte b = value.get(i);
            if (b == ' ' || b == '\t' || b == '@' || b == '/' || b == '?' || b == '#' || b == '\\') {
                return false;
            }
        }
        return true;
    }

    /**
     * Rebuilds a folded value into a new buffer: each fold (optional white
     * space, CRLF, white space) becomes one space (RFC 9112 section 5.2).
     */
    private ByteBuffer unfold(ByteBuffer d, int from, int to) {
        byte[] out = new byte[to - from];
        int n = 0;
        int i = from;
        while (i < to) {
            byte b = d.get(i);
            if (b == '\r') {
                while (n > 0 && isOws(out[n - 1])) {
                    n--;
                }
                i += 2;
                while (i < to && isOws(d.get(i))) {
                    i++;
                }
                out[n++] = ' ';
            } else {
                out[n++] = b;
                i++;
            }
        }
        int s = 0;
        while (s < n && isOws(out[s])) {
            s++;
        }
        while (n > s && isOws(out[n - 1])) {
            n--;
        }
        return ByteBuffer.wrap(out, s, n - s).asReadOnlyBuffer();
    }

    // ---- end of the header section and body framing ----

    private void endOfHeaders() {
        boolean chunked = false;
        boolean none = false;
        boolean untilClose = false;
        long length = 0;
        if (request) {
            if (fields.hasTransferEncoding()) {
                if (version != HttpVersion.HTTP_1_1) {
                    fail(HttpError.MALFORMED, "Transfer-Encoding in an HTTP/1.0 request");
                    return;
                }
                if (!fields.isLastCodingChunked()) {
                    // RFC 9112 section 6.3: the length cannot be determined
                    fail(HttpError.MALFORMED, "Transfer-Encoding does not end in chunked");
                    return;
                }
                chunked = true;
            } else if (fields.hasContentLength()) {
                length = fields.getContentLength();
                none = length == 0;
            } else {
                none = true;
            }
        } else {
            boolean interim = status >= 100 && status < 200;
            boolean noBody = interim || status == 204 || status == 304
                    || responseTo == HttpMethod.HEAD
                    || (responseTo == HttpMethod.CONNECT && status >= 200 && status < 300);
            if (noBody) {
                none = true;
            } else if (fields.hasTransferEncoding()) {
                if (fields.isLastCodingChunked()) {
                    chunked = true;
                } else {
                    untilClose = true;
                }
            } else if (fields.hasContentLength()) {
                length = fields.getContentLength();
                none = length == 0;
            } else {
                untilClose = true;
            }
        }
        boolean tunnel = !request && (status == 101
                || (responseTo == HttpMethod.CONNECT && status >= 200 && status < 300));
        if (!request && status >= 200) {
            responseTo = null;
        }
        handler.endHeaders();
        if (none) {
            finishMessage();
            if (tunnel) {
                state = State.TUNNEL;
            }
        } else if (chunked) {
            state = State.CHUNK_SIZE;
        } else if (untilClose) {
            state = State.BODY_UNTIL_CLOSE;
        } else {
            remaining = length;
            state = State.BODY_LENGTH;
        }
    }

    private void finishMessage() {
        state = State.START_LINE;
        handler.endMessage();
    }

    /** Body bytes of a known length: the whole body, or the data of one chunk. */
    private boolean bodyOfKnownLength(ByteBuffer d, boolean chunk) {
        int p = d.position();
        int n = (int) Math.min(remaining, (long) d.remaining());
        emitBody(d, p, p + n);
        d.position(p + n);
        remaining -= n;
        if (remaining == 0) {
            if (chunk) {
                state = State.CHUNK_DATA_END;
            } else {
                finishMessage();
            }
        }
        return true;
    }

    /** chunk = chunk-size [ chunk-ext ] CRLF (RFC 9112 section 7.1). */
    private boolean chunkSize(ByteBuffer d) {
        int start = d.position();
        int limit = d.limit();
        long size = 0;
        int i = start;
        int digits = 0;
        while (i < limit) {
            byte b = d.get(i);
            int v;
            if (b >= '0' && b <= '9') {
                v = b - '0';
            } else if (b >= 'a' && b <= 'f') {
                v = b - 'a' + 10;
            } else if (b >= 'A' && b <= 'F') {
                v = b - 'A' + 10;
            } else {
                break;
            }
            if (size > (Long.MAX_VALUE >> 4)) {
                return fail(HttpError.MALFORMED, "chunk size too large");
            }
            size = (size << 4) | v;
            digits++;
            i++;
        }
        if (i >= limit) {
            if (i - start > maxFieldLineLength) {
                return fail(HttpError.MALFORMED, "chunk size line too long");
            }
            return false;
        }
        if (digits == 0) {
            return fail(HttpError.MALFORMED, "missing chunk size");
        }
        // an optional chunk extension runs to the CRLF
        while (true) {
            if (i >= limit) {
                if (i - start > maxFieldLineLength) {
                    return fail(HttpError.MALFORMED, "chunk size line too long");
                }
                return false;
            }
            byte b = d.get(i);
            if (b == '\r') {
                break;
            }
            if (b == '\n' || (b >= 0 && b < 0x20 && b != '\t') || b == 0x7F
                    || (i == start + digits && b != ';')) {
                return fail(HttpError.MALFORMED, "malformed chunk header");
            }
            i++;
        }
        if (i + 1 >= limit) {
            return false;
        }
        if (d.get(i + 1) != '\n') {
            return fail(HttpError.MALFORMED, "bare CR in a chunk header");
        }
        d.position(i + 2);
        if (size == 0) {
            state = State.TRAILERS;
            fieldCount = 0;
        } else {
            remaining = size;
            state = State.CHUNK_DATA;
        }
        return true;
    }

    private boolean chunkDataEnd(ByteBuffer d) {
        if (d.remaining() < 2) {
            return false;
        }
        int p = d.position();
        if (d.get(p) != '\r' || d.get(p + 1) != '\n') {
            return fail(HttpError.MALFORMED, "chunk data is not followed by CRLF");
        }
        d.position(p + 2);
        state = State.CHUNK_SIZE;
        return true;
    }

    // ---- helpers ----

    private void emitBody(ByteBuffer d, int from, int to) {
        if (to > from) {
            handler.bodyContent(slice(from, to));
        }
    }

    /** The reusable read-only view, set to {@code from} (inclusive) to {@code to} (exclusive). */
    private ByteBuffer slice(int from, int to) {
        view.clear();
        view.position(from);
        view.limit(to);
        return view;
    }

    private boolean fail(HttpError error, String detail) {
        state = State.FAILED;
        handler.error(error, detail);
        return true;
    }

    private static String lowerCase(ByteBuffer d, int from, int to) {
        char[] chars = new char[to - from];
        for (int i = 0; i < chars.length; i++) {
            int b = d.get(from + i) & 0xFF;
            if (b >= 'A' && b <= 'Z') {
                b += 32;
            }
            chars[i] = (char) b;
        }
        return new String(chars);
    }

    private static boolean isOws(byte b) {
        return b == ' ' || b == '\t';
    }

    private static boolean isTokenChar(byte b) {
        return HttpUtils.isTokenChar(b);
    }

}
