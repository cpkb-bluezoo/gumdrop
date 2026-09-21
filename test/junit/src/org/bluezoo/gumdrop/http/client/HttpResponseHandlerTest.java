/*
 * HttpResponseHandlerTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertFalse;

/**
 * Exercises {@link HttpResponseHandler} interface default methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpResponseHandlerTest {

    @Test
    public void defaultDatagramAndCapsuleMethodsAreNoOps() {
        HttpResponseHandler handler = new HttpResponseHandler() {
            @Override public void ok(HttpResponse response) { }
            @Override public void error(HttpResponse response) { }
            @Override public void header(String name, String value) { }
            @Override public void startResponseBody() { }
            @Override public void responseBodyContent(ByteBuffer data) { }
            @Override public void endResponseBody() { }
            @Override public void pushPromise(PushPromise promise) { }
            @Override public void close() { }
            @Override public void failed(Exception ex) { }
        };

        assertFalse(handler.wantsDatagrams());
        handler.datagramReceived(ByteBuffer.wrap(new byte[] { 1 }));
        handler.capsuleReceived(42L, ByteBuffer.allocate(0));
    }
}
