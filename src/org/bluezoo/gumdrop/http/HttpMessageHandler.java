/*
 * HttpMessageHandler.java
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

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * Receives one HTTP message as a stream of events, whichever of HTTP/1.x,
 * HTTP/2 or HTTP/3 carried it. The parser pushes events as soon as each one
 * can be recognised; nothing is collected into a message object.
 *
 * <p>The order is: the start events ({@link #method}, {@link #target},
 * {@link #version}, and where the protocol has them {@link #scheme},
 * {@link #authority} and {@link #protocol}, for a request; {@link #version},
 * {@link #status} and {@link #reason} for a response), then any number of
 * field events,
 * then {@link #endHeaders()}, then {@link #bodyContent} events, then
 * {@link #endMessage()}. Trailer fields, if any, come between the last body
 * content and {@code endMessage}. A parser that cannot continue sends
 * {@link #error} instead, and nothing after it.
 *
 * <p>Field events work as in {@link org.bluezoo.gumdrop.mime.MimeHandler}:
 * a few fields have typed events, and every other field, and any of the typed
 * ones whose value cannot be parsed, arrives as {@link #header}. Each field
 * line is its own event. Fields that repeat are not combined here, because
 * how repeated lines combine depends on the particular field (a list for most,
 * but not for {@code Set-Cookie}); that is for the consumer, which knows which
 * field it is reading.
 *
 * <p>Field events before {@code endHeaders()} are provisional: a protocol
 * layer may still reject the message, for example for conflicting framing,
 * before the application is told a body is coming.
 *
 * <p>Every buffer passed to these methods is a read-only view that is valid
 * only until the method returns. Where the octets came off the wire the view
 * is of those same octets, not a copy, since the receiver may well discard
 * them; a receiver that wants them later copies them. Field names are
 * lower case, as they already are in HTTP/2 and HTTP/3, so a handler sees the
 * same name whichever protocol carried the message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface HttpMessageHandler {

    // ---- start of a request ----

    /**
     * The request method.
     *
     * @param method the method
     */
    void method(HttpMethod method);

    /**
     * The request target, as the octets of the request-target (RFC 9112
     * section 3.2) or, for HTTP/2 and HTTP/3, of the {@code :path} (or the
     * authority for {@code CONNECT}).
     *
     * @param target the target octets
     */
    void target(ByteBuffer target);

    /**
     * The scheme of the request ({@code :scheme} in HTTP/2 and HTTP/3). An
     * HTTP/1.x request does not carry one; it is a property of the connection.
     *
     * @param scheme the scheme octets, such as {@code https}
     */
    void scheme(ByteBuffer scheme);

    /**
     * The authority the request is for: {@code :authority} in HTTP/2 and
     * HTTP/3, the {@code Host} field in HTTP/1.x. The same event reports it
     * whichever protocol carried the request. A request made through
     * HTTP/2 or HTTP/3 may also carry a {@code Host} field, which then arrives
     * as an ordinary {@link #header}.
     *
     * @param authority the host, and port if any, in octets
     */
    void authority(ByteBuffer authority);

    /**
     * The protocol of an extended {@code CONNECT} request (RFC 8441 section 4,
     * RFC 9220), such as {@code websocket}.
     *
     * @param protocol the {@code :protocol} octets
     */
    void protocol(ByteBuffer protocol);

    // ---- start of a message ----

    /**
     * The protocol version of the message.
     *
     * @param version the version
     */
    void version(HttpVersion version);

    // ---- start of a response ----

    /**
     * The response status code.
     *
     * @param code the three-digit status code
     */
    void status(int code);

    /**
     * The reason phrase of an HTTP/1.x status line, which may be empty. It
     * carries no meaning (RFC 9112 section 4) and HTTP/2 and HTTP/3 have none.
     *
     * @param phrase the reason phrase octets
     */
    void reason(ByteBuffer phrase);

    // ---- fields ----

    /**
     * A {@code Content-Type} field with a value that parsed.
     *
     * @param contentType the media type and its parameters
     */
    void contentType(ContentType contentType);

    /**
     * A {@code Content-Disposition} field with a value that parsed.
     *
     * @param contentDisposition the disposition and its parameters
     */
    void contentDisposition(ContentDisposition contentDisposition);

    /**
     * A field whose value is a non-negative integer, such as
     * {@code Content-Length}.
     *
     * @param name the lower-case field name
     * @param value the value
     */
    void longHeader(String name, long value);

    /**
     * Any other field, and any typed field whose value did not parse.
     *
     * @param name the lower-case field name
     * @param value the value octets, without the leading or trailing
     *     whitespace that surrounds a value on the wire
     */
    void header(String name, ByteBuffer value);

    /**
     * The end of the header section. Everything before it was provisional;
     * the message is now known to be acceptable, and any body follows.
     */
    void endHeaders();

    // ---- body ----

    /**
     * Part of the message body, after any transfer coding (such as chunking)
     * has been removed. Content codings such as gzip are not removed.
     *
     * <p>May be called any number of times, and not at all for a message
     * with no body. The body ends at the first field event that follows it
     * (a trailer field, sent as {@code header}) or at {@code endMessage}.
     *
     * @param data the body octets
     */
    void bodyContent(ByteBuffer data);

    /**
     * The end of the message. For an interim ({@code 1xx}) response this ends
     * only that response; the final one follows.
     */
    void endMessage();

    // ---- failure ----

    /**
     * The parser cannot continue. Nothing follows this event; the connection
     * can no longer be trusted to be at a message boundary.
     *
     * @param error the kind of failure
     * @param detail a description for diagnostics
     */
    void error(HttpError error, String detail);

}
