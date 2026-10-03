/*
 * PushPromiseHandler.java
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

package org.bluezoo.gumdrop.http.client;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * Receives the request an HTTP/2 server promises to answer by push (RFC 9113
 * section 8.4), as the events of a request: {@code method}, {@code target},
 * {@code scheme} and {@code authority}, the fields, {@code endHeaders} and
 * {@code endMessage}. They are the events a server's
 * {@link org.bluezoo.gumdrop.http.HttpMessageHandler} receives for the same
 * request, and the ones the server's
 * {@code HttpResponse.startPushPromise} sends.
 *
 * <p>Returned by {@link HttpResponseHandler#pushPromise()}. After the promised
 * request has been delivered, {@link #pushedResponse()} asks for the handler
 * of the pushed response.
 *
 * <pre>
 * public PushPromiseHandler pushPromise() {
 *     return new PushPromiseHandler() {
 *         private boolean wanted;
 *         public void target(ByteBuffer target) {
 *             wanted = endsWith(target, ".css");
 *         }
 *         public HttpResponseHandler pushedResponse() {
 *             return wanted ? new CssHandler() : null;
 *         }
 *     };
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpResponseHandler#pushPromise()
 */
public interface PushPromiseHandler extends HttpMessageHandler {

    // The events default to doing nothing, so a handler overrides the ones it needs.

    @Override default void method(HttpMethod method) { }
    @Override default void target(ByteBuffer target) { }
    @Override default void scheme(ByteBuffer scheme) { }
    @Override default void authority(ByteBuffer authority) { }
    @Override default void protocol(ByteBuffer protocol) { }
    @Override default void version(HttpVersion version) { }
    @Override default void status(int code) { }
    @Override default void reason(ByteBuffer phrase) { }
    @Override default void contentType(ContentType contentType) { }
    @Override default void contentDisposition(ContentDisposition contentDisposition) { }
    @Override default void longHeader(String name, long value) { }
    @Override default void dateHeader(String name, java.time.Instant value) { }
    @Override default void header(String name, ByteBuffer value) { }
    @Override default void endHeaders() { }
    @Override default void bodyContent(ByteBuffer data) { }
    @Override default void endMessage() { }
    @Override default void error(HttpError error, String detail) { }
    @Override default void failed(Exception cause) { }

    /**
     * Called after the promised request has been delivered. Returns the
     * handler to receive the pushed response, which gets the events of any
     * response, or {@code null} to refuse the push (the promised stream is
     * then reset with REFUSED_STREAM, RFC 9113 section 6.4).
     *
     * @return the handler for the pushed response, or null to refuse it
     */
    HttpResponseHandler pushedResponse();
}
