/*
 * DefaultHandlerEventsTest.java
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

package org.bluezoo.gumdrop.http.client;

import java.nio.ByteBuffer;
import java.time.Instant;

import org.junit.Test;

import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

import static org.junit.Assert.*;

/**
 * The event methods of {@link HttpResponseHandler} and {@link PushPromiseHandler}
 * default to doing nothing, so a handler overriding none of them accepts a
 * whole message; {@code pushPromise} refuses by default.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // uses value classes that are deprecated for removal
public class DefaultHandlerEventsTest {

    @Test
    public void responseHandlerDefaultsAcceptEveryEvent() {
        HttpResponseHandler handler = new HttpResponseHandler() {
            @Override
            public void failed(Exception cause) {
                fail("no failure expected");
            }
        };
        ByteBuffer buf = ByteBuffer.allocate(4);
        handler.method(HttpMethod.GET);
        handler.target(buf);
        handler.scheme(buf);
        handler.authority(buf);
        handler.protocol(buf);
        handler.version(HttpVersion.HTTP_1_1);
        handler.status(200);
        handler.reason(buf);
        handler.contentType(new ContentType("text", "plain", null));
        handler.contentDisposition(new ContentDisposition("inline", null));
        handler.longHeader("Content-Length", 5L);
        handler.dateHeader("Date", Instant.EPOCH);
        handler.header("X", buf);
        handler.endHeaders();
        handler.bodyContent(buf);
        handler.endMessage();
        handler.error(HttpError.values()[0], "detail");
        assertNull("a push is refused by default", handler.pushPromise());
        assertEquals(0, buf.position());
    }

    @Test
    public void pushPromiseHandlerDefaultsAcceptEveryEvent() {
        final HttpResponseHandler pushed = new HttpResponseHandler() {
            @Override
            public void failed(Exception cause) {
            }
        };
        PushPromiseHandler handler = new PushPromiseHandler() {
            @Override
            public HttpResponseHandler pushedResponse() {
                return pushed;
            }
        };
        ByteBuffer buf = ByteBuffer.allocate(4);
        handler.method(HttpMethod.GET);
        handler.target(buf);
        handler.scheme(buf);
        handler.authority(buf);
        handler.protocol(buf);
        handler.version(HttpVersion.HTTP_1_1);
        handler.status(200);
        handler.reason(buf);
        handler.contentType(new ContentType("text", "plain", null));
        handler.contentDisposition(new ContentDisposition("inline", null));
        handler.longHeader("Content-Length", 5L);
        handler.dateHeader("Date", Instant.EPOCH);
        handler.header("X", buf);
        handler.endHeaders();
        handler.bodyContent(buf);
        handler.endMessage();
        handler.error(HttpError.values()[0], "detail");
        handler.failed(new java.io.IOException("x"));
        assertSame(pushed, handler.pushedResponse());
        assertEquals(0, buf.position());
    }
}
