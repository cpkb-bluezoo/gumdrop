/*
 * H3ClientStreamTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Tests for H3ClientStream response parsing:
 *   - :status validation (RFC 9114 section 4.3.2)
 *   - 1xx informational response handling (RFC 9114 section 4.1)
 */

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.PushPromise;

import org.junit.Test;
import static org.junit.Assert.*;

public class H3ClientStreamTest {

    /**
     * RFC 9114 section 4.3.2: a valid :status should dispatch ok().
     */
    @Test
    public void testValidStatusDispatches() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(0, handler);

        stream.onHeaders(new String[] { ":status", "200", "content-type", "text/plain" });

        assertNotNull("ok() should have been called", handler.okResponse);
        assertNull("failed() should not have been called", handler.failedException);
    }

    /**
     * RFC 9114 section 4.3.2: a 404 should dispatch error().
     */
    @Test
    public void testErrorStatusDispatches() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(0, handler);

        stream.onHeaders(new String[] { ":status", "404" });

        assertNotNull("error() should have been called", handler.errorResponse);
        assertNull("failed() should not have been called", handler.failedException);
    }

    /**
     * RFC 9114 section 4.3.2: missing :status means malformed response.
     */
    @Test
    public void testMissingStatusFailsStream() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(0, handler);

        stream.onHeaders(new String[] { "content-type", "text/html" });

        assertNotNull("failed() should have been called", handler.failedException);
        assertTrue(handler.failedException.getMessage().contains("missing :status"));
    }

    /**
     * RFC 9114 section 4.1: 1xx informational responses should not
     * dispatch ok()/error() — the stream stays OPEN for the final
     * response.
     */
    @Test
    public void testInformational100IsConsumed() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(0, handler);

        stream.onHeaders(new String[] { ":status", "100" });

        assertNull("ok() should not be called for 1xx", handler.okResponse);
        assertNull("error() should not be called for 1xx", handler.errorResponse);
        assertEquals("OPEN", getState(stream));
    }

    /**
     * RFC 9114 section 4.1: after a 1xx, a subsequent 200 should
     * dispatch normally.
     */
    @Test
    public void testFinalResponseAfter1xx() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(0, handler);

        stream.onHeaders(new String[] { ":status", "100" });
        assertNull("ok() should not be called for 100", handler.okResponse);

        stream.onHeaders(new String[] { ":status", "200", "content-type", "text/html" });
        assertNotNull("ok() should be called for final 200", handler.okResponse);
    }

    /**
     * RFC 9114 section 4.1: 103 Early Hints is also informational.
     */
    @Test
    public void testEarlyHints103IsConsumed() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(0, handler);

        stream.onHeaders(new String[] { ":status", "103", "link", "</style.css>; rel=preload" });

        assertNull("ok() should not be called for 103", handler.okResponse);
        assertEquals("link", handler.lastHeaderName);
        assertEquals("OPEN", getState(stream));
    }

    /**
     * RFC 9114 section 4.3.2: non-numeric :status should be treated
     * as 500 (server error).
     */
    @Test
    public void testNonNumericStatusTreatedAsError() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(0, handler);

        stream.onHeaders(new String[] { ":status", "abc" });

        assertNotNull("error() should be called for non-numeric status",
                handler.errorResponse);
    }

    /**
     * Tests extractStatus via reflection.
     */
    @Test
    public void testExtractStatusReturnsNegativeForMissing() throws Exception {
        H3ClientStream stream = createStream(0, new StubResponseHandler());
        Method m = H3ClientStream.class.getDeclaredMethod(
                "extractStatus", String[].class);
        m.setAccessible(true);

        int result = (int) m.invoke(stream, (Object) new String[] { "content-type", "text/html" });
        assertEquals(-1, result);
    }

    @Test
    public void testExtractStatusReturns200() throws Exception {
        H3ClientStream stream = createStream(0, new StubResponseHandler());
        Method m = H3ClientStream.class.getDeclaredMethod(
                "extractStatus", String[].class);
        m.setAccessible(true);

        int result = (int) m.invoke(stream, (Object) new String[] { ":status", "200" });
        assertEquals(200, result);
    }

    /**
     * RFC 9114 section 5.2: onGoawayFailed should fail the stream.
     */
    @Test
    public void testGoawayFailedNotifiesHandler() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(4, handler);

        stream.onGoawayFailed(new java.io.IOException("retryable"));

        assertNotNull("failed() should have been called", handler.failedException);
        assertTrue(handler.failedException.getMessage().contains("retryable"));
    }

    // ── Helpers ──

    private H3ClientStream createStream(long streamId,
            HTTPResponseHandler handler) throws Exception {
        Constructor<H3ClientStream> ctor = H3ClientStream.class
                .getDeclaredConstructor(HTTP3ClientHandler.class, long.class,
                        HTTPResponseHandler.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, streamId, handler);
    }

    private String getState(H3ClientStream stream) throws Exception {
        Field f = H3ClientStream.class.getDeclaredField("state");
        f.setAccessible(true);
        return ((Enum<?>) f.get(stream)).name();
    }

    private static class StubResponseHandler implements HTTPResponseHandler {
        HTTPResponse okResponse;
        HTTPResponse errorResponse;
        Exception failedException;
        String lastHeaderName;
        String lastHeaderValue;

        @Override public void ok(HTTPResponse response) { okResponse = response; }
        @Override public void error(HTTPResponse response) { errorResponse = response; }
        @Override public void header(String name, String value) {
            lastHeaderName = name;
            lastHeaderValue = value;
        }
        @Override public void startResponseBody() {}
        @Override public void responseBodyContent(ByteBuffer data) {}
        @Override public void endResponseBody() {}
        @Override public void pushPromise(PushPromise promise) {}
        @Override public void close() {}
        @Override public void failed(Exception ex) { failedException = ex; }
    }
}
