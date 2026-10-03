/*
 * CollectingRequestHandler.java
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

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * A request handler for tests that want the request as a {@link Headers}
 * and a few coarse callbacks rather than field-by-field events. It gathers
 * the events into a {@code Headers} (with {@code :method}, {@code :path},
 * {@code :scheme} and {@code :authority}) and calls {@link #headers} when the
 * header section ends, {@link #requestBodyContent} for each piece of the body,
 * and {@link #requestComplete} at the end of the message. Fields after the
 * body (trailers) are given to {@link #headers} again.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CollectingRequestHandler extends DefaultHttpRequestHandler {

    /** The response of the stream carrying the request. */
    public HttpResponse response;

    private Headers collected = new Headers();
    private boolean headersEnded;
    private boolean bodyStarted;
    private boolean bodyEnded;

    public CollectingRequestHandler(HttpResponse response) {
        this.response = response;
    }

    /** For a handler created before its stream is known; see {@link #bind}. */
    public CollectingRequestHandler() {
    }

    /** Gives a handler its stream's response, and returns it. */
    public static <T extends CollectingRequestHandler> T bind(T handler, HttpResponse response) {
        handler.response = response;
        return handler;
    }

    /** The header section, and then any trailers. */
    public void headers(HttpResponse state, Headers headers) {
    }

    /** The first piece of the body is about to arrive. */
    public void startRequestBody(HttpResponse state) {
    }

    /** A piece of the request body. */
    public void requestBodyContent(HttpResponse state, ByteBuffer data) {
    }

    /** The body has ended. */
    public void endRequestBody(HttpResponse state) {
    }

    /** The end of the request. */
    public void requestComplete(HttpResponse state) {
    }

    private static String text(ByteBuffer b) {
        byte[] octets = new byte[b.remaining()];
        b.duplicate().get(octets);
        return new String(octets, StandardCharsets.ISO_8859_1);
    }

    @Override
    public void method(HttpMethod method) {
        collected.add(new Header(":method", method.name()));
    }

    @Override
    public void target(ByteBuffer target) {
        collected.add(new Header(":path", text(target)));
    }

    @Override
    public void scheme(ByteBuffer scheme) {
        collected.add(new Header(":scheme", text(scheme)));
    }

    @Override
    public void authority(ByteBuffer authority) {
        collected.add(new Header(":authority", text(authority)));
    }

    @Override
    public void protocol(ByteBuffer protocol) {
        collected.add(new Header(":protocol", text(protocol)));
    }

    @Override
    public void contentType(ContentType contentType) {
        collected.add(new Header("content-type", contentType.toHeaderValue()));
    }

    @Override
    public void contentDisposition(ContentDisposition contentDisposition) {
        collected.add(new Header("content-disposition", contentDisposition.toHeaderValue()));
    }

    @Override
    public void longHeader(String name, long value) {
        collected.add(new Header(name, Long.toString(value)));
    }

    @Override
    public void dateHeader(String name, java.time.Instant value) {
        collected.add(new Header(name, new org.bluezoo.gumdrop.http.HttpDateFormat().format(value.toEpochMilli())));
    }

    @Override
    public void header(String name, ByteBuffer value) {
        if (headersEnded) {
            // a trailer field: the body is over
            endBody();
        }
        collected.add(new Header(name, text(value)));
    }

    @Override
    public void endHeaders() {
        headersEnded = true;
        Headers section = collected;
        collected = new Headers();
        headers(response, section);
    }

    @Override
    public void bodyContent(ByteBuffer data) {
        if (!bodyStarted) {
            bodyStarted = true;
            startRequestBody(response);
        }
        requestBodyContent(response, data);
    }

    private void endBody() {
        if (bodyStarted && !bodyEnded) {
            bodyEnded = true;
            endRequestBody(response);
        }
    }

    @Override
    public void endMessage() {
        endBody();
        if (headersEnded && collected.size() > 0) {
            headers(response, collected);
            collected = new Headers();
        }
        requestComplete(response);
    }

    @Override
    public void failed(Exception cause) {
        failed(response, cause);
    }

    /** The exchange failed. */
    public void failed(HttpResponse state, Exception cause) {
    }
}
