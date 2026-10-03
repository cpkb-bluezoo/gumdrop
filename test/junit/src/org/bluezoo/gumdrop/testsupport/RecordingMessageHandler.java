/*
 * RecordingMessageHandler.java
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


package org.bluezoo.gumdrop.testsupport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * An {@link HttpMessageHandler} that records each event as a line of text, for
 * tests of anything that produces those events (the HTTP/1.x parser, the
 * HTTP/2 and HTTP/3 field-section adapter).
 *
 * <p>Adjacent body events are merged into one, since where a body is cut into
 * pieces depends on how the input was split. It also notes whether every view it
 * was given was read-only, and whether the views were direct or heap buffers,
 * which shows whether the octets were copied.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RecordingMessageHandler implements HttpMessageHandler {

    /** The events, in order, as text such as {@code "method GET"} or {@code "header host example.test"}. */
    public final List<String> events = new ArrayList<String>();

    /** False if any view passed to the handler was writable. */
    public boolean viewsReadOnly = true;

    /** Whether any view was a direct buffer. */
    public boolean sawDirectView;

    /** Whether any view was a heap buffer. */
    public boolean sawHeapView;

    private final StringBuilder body = new StringBuilder();

    /** The octets of a buffer as ISO-8859-1 text, without consuming it. */
    public static String text(ByteBuffer b) {
        byte[] a = new byte[b.remaining()];
        b.duplicate().get(a);
        return new String(a, StandardCharsets.ISO_8859_1);
    }

    private void flushBody() {
        if (body.length() > 0) {
            events.add("body " + body);
            body.setLength(0);
        }
    }

    private void view(ByteBuffer b) {
        if (!b.isReadOnly()) {
            viewsReadOnly = false;
        }
        if (b.isDirect()) {
            sawDirectView = true;
        } else {
            sawHeapView = true;
        }
    }

    @Override public void method(HttpMethod m) { flushBody(); events.add("method " + m); }
    @Override public void target(ByteBuffer t) { flushBody(); view(t); events.add("target " + text(t)); }
    @Override public void scheme(ByteBuffer v) { flushBody(); view(v); events.add("scheme " + text(v)); }
    @Override public void authority(ByteBuffer v) { flushBody(); view(v); events.add("authority " + text(v)); }
    @Override public void protocol(ByteBuffer v) { flushBody(); view(v); events.add("protocol " + text(v)); }
    @Override public void version(HttpVersion v) { flushBody(); events.add("version " + v); }
    @Override public void status(int code) { flushBody(); events.add("status " + code); }
    @Override public void reason(ByteBuffer p) { flushBody(); view(p); events.add("reason " + text(p)); }
    @Override public void contentType(ContentType c) {
        flushBody();
        events.add("contentType " + c.getPrimaryType() + "/" + c.getSubType()
                + " charset=" + c.getParameter("charset"));
    }
    @Override public void contentDisposition(ContentDisposition d) {
        flushBody();
        events.add("contentDisposition " + d.getDispositionType()
                + " filename=" + d.getParameter("filename"));
    }
    @Override public void longHeader(String name, long value) { flushBody(); events.add("long " + name + " " + value); }
    @Override public void header(String name, ByteBuffer value) { flushBody(); view(value); events.add("header " + name + " " + text(value)); }
    @Override public void endHeaders() { flushBody(); events.add("endHeaders"); }
    @Override public void bodyContent(ByteBuffer data) { view(data); body.append(text(data)); }
    @Override public void endMessage() { flushBody(); events.add("endMessage"); }
    @Override public void error(HttpError e, String detail) { flushBody(); events.add("error " + e); }
    @Override public void failed(Exception cause) { flushBody(); events.add("failed " + cause.getMessage()); }

}
