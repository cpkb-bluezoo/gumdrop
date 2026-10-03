/*
 * HttpMessageRecorder.java
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
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * Keeps the events of a message so they can be delivered later, to a handler
 * that did not yet exist when they happened.
 *
 * <p>A server cannot hand a request to the application as its fields arrive.
 * What happens to a request (authentication, size limits, {@code Expect},
 * upgrade, which application handler to bind, or a plain 404) is decided
 * from the whole header section, and only then does the application handler
 * exist. So the server records the header section's events here and replays
 * them once it has decided. What is kept is bounded by the limits on the size of a
 * header section, and is not a message object: it is the events themselves,
 * in order.
 *
 * <p>Octets are copied on the way in, because the buffers a parser hands out
 * are valid only for the duration of the call, and each replay gives the
 * receiver fresh read-only buffers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HttpMessageRecorder implements HttpMessageHandler {

    private enum Kind {
        METHOD, TARGET, SCHEME, AUTHORITY, PROTOCOL, VERSION, STATUS, REASON,
        CONTENT_TYPE, CONTENT_DISPOSITION, LONG, HEADER, END_HEADERS, BODY,
        TRAILER, END_MESSAGE, ERROR
    }

    private static final class Event {
        final Kind kind;
        final String name;
        final byte[] octets;
        final Object object;
        final long number;

        Event(Kind kind, String name, byte[] octets, Object object, long number) {
            this.kind = kind;
            this.name = name;
            this.octets = octets;
            this.object = object;
            this.number = number;
        }
    }

    private final List<Event> events = new ArrayList<Event>();

    /** Forgets everything recorded. */
    public void clear() {
        events.clear();
    }

    /**
     * Returns whether anything has been recorded.
     *
     * @return true if nothing has
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Delivers the recorded events, in order, to {@code target}. May be called
     * more than once.
     *
     * @param target the receiver
     */
    public void replay(HttpMessageHandler target) {
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            switch (e.kind) {
                case METHOD: target.method((HttpMethod) e.object); break;
                case TARGET: target.target(view(e)); break;
                case SCHEME: target.scheme(view(e)); break;
                case AUTHORITY: target.authority(view(e)); break;
                case PROTOCOL: target.protocol(view(e)); break;
                case VERSION: target.version((HttpVersion) e.object); break;
                case STATUS: target.status((int) e.number); break;
                case REASON: target.reason(view(e)); break;
                case CONTENT_TYPE: target.contentType((ContentType) e.object); break;
                case CONTENT_DISPOSITION: target.contentDisposition((ContentDisposition) e.object); break;
                case LONG: target.longHeader(e.name, e.number); break;
                case HEADER: target.header(e.name, view(e)); break;
                case END_HEADERS: target.endHeaders(); break;
                case BODY: target.bodyContent(view(e)); break;
                case TRAILER: target.trailer(e.name, view(e)); break;
                case END_MESSAGE: target.endMessage(); break;
                case ERROR: target.error((HttpError) e.object, e.name); break;
                default: break;
            }
        }
    }

    private static ByteBuffer view(Event e) {
        return ByteBuffer.wrap(e.octets).asReadOnlyBuffer();
    }

    private static byte[] copy(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    private void add(Kind kind, String name, byte[] octets, Object object, long number) {
        events.add(new Event(kind, name, octets, object, number));
    }

    @Override public void method(HttpMethod method) { add(Kind.METHOD, null, null, method, 0); }
    @Override public void target(ByteBuffer target) { add(Kind.TARGET, null, copy(target), null, 0); }
    @Override public void scheme(ByteBuffer scheme) { add(Kind.SCHEME, null, copy(scheme), null, 0); }
    @Override public void authority(ByteBuffer authority) { add(Kind.AUTHORITY, null, copy(authority), null, 0); }
    @Override public void protocol(ByteBuffer protocol) { add(Kind.PROTOCOL, null, copy(protocol), null, 0); }
    @Override public void version(HttpVersion version) { add(Kind.VERSION, null, null, version, 0); }
    @Override public void status(int code) { add(Kind.STATUS, null, null, null, code); }
    @Override public void reason(ByteBuffer phrase) { add(Kind.REASON, null, copy(phrase), null, 0); }
    @Override public void contentType(ContentType c) { add(Kind.CONTENT_TYPE, null, null, c, 0); }
    @Override public void contentDisposition(ContentDisposition d) { add(Kind.CONTENT_DISPOSITION, null, null, d, 0); }
    @Override public void longHeader(String name, long value) { add(Kind.LONG, name, null, null, value); }
    @Override public void header(String name, ByteBuffer value) { add(Kind.HEADER, name, copy(value), null, 0); }
    @Override public void endHeaders() { add(Kind.END_HEADERS, null, null, null, 0); }
    @Override public void bodyContent(ByteBuffer data) { add(Kind.BODY, null, copy(data), null, 0); }
    @Override public void trailer(String name, ByteBuffer value) { add(Kind.TRAILER, name, copy(value), null, 0); }
    @Override public void endMessage() { add(Kind.END_MESSAGE, null, null, null, 0); }
    @Override public void error(HttpError error, String detail) { add(Kind.ERROR, detail, null, error, 0); }

}
