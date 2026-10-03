/*
 * H3ClientStreamTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;

import org.bluezoo.gumdrop.testsupport.CollectingResponseHandler;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
        assertNull("a malformed response is never delivered", handler.okResponse);
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
        assertNull("interim fields are not delivered", handler.lastHeaderName);
        assertEquals("OPEN", getState(stream));
    }

    /**
     * RFC 9114 section 4.3.2 / RFC 9110 section 15: a :status that is not
     * a three-digit code makes the response malformed.
     */
    @Test
    public void testNonNumericStatusIsMalformed() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(":status", "abc"));

        assertNull("a malformed response is never delivered", handler.errorResponse);
        assertNull(handler.okResponse);
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

    /**
     * RFC 9114 section 4.2: connection-specific header fields are
     * stripped on response ingest (matching H3Stream), not delivered
     * to the application.
     */
    @Test
    public void testHttp1FramingHeadersMakeTheResponseMalformed() throws Exception {
        // RFC 9114 section 4.2: connection-specific fields are not used in
        // HTTP/3; a response carrying one is malformed, not tidied up
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(
                ":status", "200",
                "content-type", "text/plain",
                "connection", "keep-alive",
                "x-custom", "ok"));

        assertNull("a malformed response is never delivered", handler.okResponse);
    }

    /**
     * Content-Length is captured for body validation, and is also delivered
     * to the handler like any other field.
     */
    @Test
    public void testContentLengthCapturedBeforeFramingStrip() throws Exception {
        StubResponseHandler handler = new StubResponseHandler();
        H3ClientStream stream = createStream(handler);

        stream.headersFrameReceived(encode(
                ":status", "200",
                "content-length", "3",
                "content-type", "text/plain"));

        assertEquals(Long.valueOf(3L), getField(stream, "contentLength"));
        assertTrue(handler.headerNames.contains("content-length"));
        assertTrue(handler.headerNames.contains("content-type"));
    }

    // ── Helpers ──

    private static Object getField(H3ClientStream stream, String name) throws Exception {
        Field f = H3ClientStream.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(stream);
    }
    private H3ClientStream createStream(HttpResponseHandler handler) throws Exception {
        // connection is null: this test exercises response parsing in
        // isolation, without a real Http3ClientHandler/QuicConnection
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

    private static class StubResponseHandler extends CollectingResponseHandler {
        HttpStatus okResponse;
        HttpStatus errorResponse;
        Exception failedException;
        String lastHeaderName;
        String lastHeaderValue;
        final List<String> headerNames = new ArrayList<String>();

        @Override public void ok(HttpStatus response) { okResponse = response; }
        @Override public void error(HttpStatus response) { errorResponse = response; }
        @Override public void header(String name, String value) {
            lastHeaderName = name;
            lastHeaderValue = value;
            headerNames.add(name);
        }
        @Override public void failed(Exception ex) { failedException = ex; }
    }
}
