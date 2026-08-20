/*
 * H3ClientStreamTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Tests for H3ClientStream response parsing:
 *   - :status validation (RFC 9114 section 4.3.2)
 *   - 1xx informational response handling (RFC 9114 section 4.1)
 */

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.PushPromise;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;

import org.junit.Test;
import static org.junit.Assert.*;

public class H3ClientStreamTest {

    /**
     * RFC 9114 section 4.3.2: a valid :status should dispatch ok().
     */
    @Test
    public void testValidStatusDispatches() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(":status", "200", "content-type", "text/plain"));

        assertNotNull("ok() should have been called", handler.okResponse);
        assertNull("failed() should not have been called", handler.failedException);
    }

    /**
     * RFC 9114 section 4.3.2: a 404 should dispatch error().
     */
    @Test
    public void testErrorStatusDispatches() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(":status", "404"));

        assertNotNull("error() should have been called", handler.errorResponse);
        assertNull("failed() should not have been called", handler.failedException);
    }

    /**
     * RFC 9114 section 4.3.2: missing :status means malformed response.
     */
    @Test
    public void testMissingStatusFailsStream() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode("content-type", "text/html"));

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
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(":status", "100"));

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
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(":status", "100"));
        assertNull("ok() should not be called for 100", handler.okResponse);

        stream.headersFrameReceived(encode(":status", "200", "content-type", "text/html"));
        assertNotNull("ok() should be called for final 200", handler.okResponse);
    }

    /**
     * RFC 9114 section 4.1: 103 Early Hints is also informational.
     */
    @Test
    public void testEarlyHints103IsConsumed() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(":status", "103", "link", "</style.css>; rel=preload"));

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
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(":status", "abc"));

        assertNotNull("error() should be called for non-numeric status",
                handler.errorResponse);
    }

    /**
     * Tests extractStatus via reflection.
     */
    @Test
    public void testExtractStatusReturnsNegativeForMissing() throws Exception {
        Method m = H3ClientStream.class.getDeclaredMethod("extractStatus", List.class);
        m.setAccessible(true);

        int result = (int) m.invoke(null, headerList("content-type", "text/html"));
        assertEquals(-1, result);
    }

    @Test
    public void testExtractStatusReturns200() throws Exception {
        Method m = H3ClientStream.class.getDeclaredMethod("extractStatus", List.class);
        m.setAccessible(true);

        int result = (int) m.invoke(null, headerList(":status", "200"));
        assertEquals(200, result);
    }

    /**
     * RFC 9114 section 5.2: onGoawayFailed should fail the stream.
     */
    @Test
    public void testGoawayFailedNotifiesHandler() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.onGoawayFailed(new java.io.IOException("retryable"));

        assertNotNull("failed() should have been called", handler.failedException);
        assertTrue(handler.failedException.getMessage().contains("retryable"));
    }

    /**
     * A QUIC-level error close (e.g. the peer's CONNECTION_CLOSE, or a
     * local transport error) must reach failed() with the exception
     * that carries the applicationError/errorCode/reason detail, not a
     * generic argument-free signal.
     */
    @Test
    public void testConnectionCloseErrorReachesFailed() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        org.bluezoo.gumdrop.quic.QuicConnectionCloseException cause =
                new org.bluezoo.gumdrop.quic.QuicConnectionCloseException(
                        true, 0x10c, "server going away");
        stream.error(cause);

        assertSame("the exact exception instance must reach failed()",
                cause, handler.failedException);
        org.bluezoo.gumdrop.quic.QuicConnectionCloseException delivered =
                (org.bluezoo.gumdrop.quic.QuicConnectionCloseException) handler.failedException;
        assertTrue(delivered.isApplicationError());
        assertEquals(0x10c, delivered.getErrorCode());
        assertEquals("server going away", delivered.getReason());
    }

    // ── Helpers ──

    private H3ClientStream createStream(HTTPResponseHandler handler) throws Exception {
        // connection is null: this test exercises response parsing in
        // isolation, without a real HTTP3ClientHandler/QuicConnection
        // stack -- H3ClientStream tolerates this (see its own source).
        H3ClientStream stream = new H3ClientStream(null, new Decoder(4096), handler);
        setField(stream, "streamId", 1L);
        return stream;
    }

    private static void setField(H3ClientStream stream, String name, Object value) throws Exception {
        Field f = H3ClientStream.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(stream, value);
    }

    private static List<Header> headerList(String... pairs) {
        List<Header> headers = new ArrayList<Header>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new Header(pairs[i], pairs[i + 1]));
        }
        return headers;
    }

    private static ByteBuffer encode(String... pairs) {
        SimpleEncoder encoder = new SimpleEncoder();
        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headerList(pairs));
        buf.flip();
        return buf;
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
