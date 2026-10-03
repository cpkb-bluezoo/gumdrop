/*
 * DefaultHttpResponseHandlerTest.java
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
import java.util.concurrent.atomic.AtomicBoolean;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DefaultHttpResponseHandler} defaults.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultHttpResponseHandlerTest {

    @Test
    public void pushPromiseIsRejectedByDefault() {
        final AtomicBoolean rejected = new AtomicBoolean(false);
        PushPromise promise = new PushPromise() {
            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public String getPath() {
                return "/style.css";
            }

            @Override
            public String getAuthority() {
                return "example.com:443";
            }

            @Override
            public String getScheme() {
                return "https";
            }

            @Override
            public Headers getHeaders() {
                return new Headers();
            }

            @Override
            public void accept(HttpResponseHandler handler) {
            }

            @Override
            public void reject() {
                rejected.set(true);
            }
        };

        new DefaultHttpResponseHandler().pushPromise(promise);
        assertTrue(rejected.get());
    }

    @Test
    public void failedDoesNotThrow() {
        new DefaultHttpResponseHandler().failed(new Exception("transport"));
    }

    @Test
    public void defaultOkErrorAndBodyHooksAreNoOps() {
        DefaultHttpResponseHandler handler = new DefaultHttpResponseHandler();
        handler.status(HttpStatus.OK.code);
        handler.status(HttpStatus.BAD_GATEWAY.code);
        handler.header("X-Test", MessageEvents.octets("1"));
        handler.endHeaders();
        handler.bodyContent(ByteBuffer.wrap(new byte[] { 1 }));
        handler.endMessage();
    }
}
