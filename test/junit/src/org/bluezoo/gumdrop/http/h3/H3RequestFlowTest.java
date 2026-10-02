/*
 * H3RequestFlowTest.java
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

package org.bluezoo.gumdrop.http.h3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.telemetry.Trace;
import org.junit.Test;

/**
 * Tests for {@link H3Request} driving a real {@link Http3ClientHandler}
 * over an in-memory QUIC connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3RequestFlowTest {

    private static H3Request newRequest(Http3ClientHandler h, String method, Trace trace) {
        return new H3Request(h, method, "/p", "example.com", "https", trace);
    }

    @Test
    public void testSendGet() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3Request r = newRequest(h, "GET", new Trace("client-op"));
        r.header("x-a", "b");
        r.priority(100);
        r.dependency(null);
        r.exclusive(true);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        r.send(rec);
        try {
            r.header("late", "x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null);
        }
        H3ClientStream s = H3ClientFlowTest.only(h);
        H3ClientFlowTest.feed(s, H3ClientFlowTest.response("200"));
        s.readFinished();
        assertTrue(rec.events.contains("ok"));
    }

    @Test
    public void testSendWithTraceparentHeaderPresent() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3Request r = newRequest(h, "GET", new Trace("client-op"));
        r.header("Traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        r.send(new H3ClientFlowTest.Rec());
        assertEquals(1, H3ClientFlowTest.streams(h).size());
    }

    @Test
    public void testPostWithBody() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3Request r = newRequest(h, "POST", null);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        r.startRequestBody(rec);
        int n = r.requestBodyContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(3, n);
        r.endRequestBody();
        h.runDeferredRequests();
        assertEquals(1, H3ClientFlowTest.streams(h).size());
    }

    @Test
    public void testDeferredUntilEstablished() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3Request r = newRequest(h, "POST", null);
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        r.startRequestBody(rec);
        r.requestBodyContent(ByteBuffer.wrap(new byte[] {1}));
        r.endRequestBody();
        assertEquals(0, H3ClientFlowTest.streams(h).size());
        h.runDeferredRequests();
        assertEquals(1, H3ClientFlowTest.streams(h).size());

        H3Request r2 = newRequest(h, "POST", null);
        H3ClientFlowTest.Rec rec2 = new H3ClientFlowTest.Rec();
        r2.send(rec2);
        h.runDeferredRequests();
    }

    @Test
    public void testCancel() throws Exception {
        Http3ClientHandler h = H3ClientFlowTest.client();
        H3Request r = newRequest(h, "GET", null);
        r.cancel();
        H3ClientFlowTest.Rec rec = new H3ClientFlowTest.Rec();
        r.send(rec);
        assertTrue(rec.events.contains("failed"));
        H3ClientFlowTest.Rec rec2 = new H3ClientFlowTest.Rec();
        r.startRequestBody(rec2);
        assertTrue(rec2.events.contains("failed"));
        assertEquals(0, r.requestBodyContent(ByteBuffer.wrap(new byte[] {1})));
        r.endRequestBody();

        H3Request r2 = newRequest(h, "GET", null);
        H3ClientFlowTest.Rec rec3 = new H3ClientFlowTest.Rec();
        r2.send(rec3);
        r2.cancel();
        assertTrue(rec3.events.contains("failed"));
    }
}
