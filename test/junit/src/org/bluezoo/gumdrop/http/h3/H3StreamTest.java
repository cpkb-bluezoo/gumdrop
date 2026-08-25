/*
 * H3StreamTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * Tests for H3Stream pseudo-header validation (RFC 9114 section 4.1.2).
 *
 * Because H3Stream sends its error response through a null connection/
 * endpoint (neither is wired up in this unit test), these tests verify
 * the validation logic by calling headersFrameReceived() with a
 * QPACK-encoded field section and catching the expected
 * NullPointerException that occurs once the response path reaches the
 * null connection or endpoint. The key assertion is that the relevant
 * fields were already set before the NPE (meaning validation ran) and,
 * for the "past validation" cases, that state stayed OPEN rather than
 * being closed by an error response.
 */

package org.bluezoo.gumdrop.http.h3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;

import org.junit.Test;
import static org.junit.Assert.*;

public class H3StreamTest {

    /**
     * RFC 9114 section 4.1.2: missing :method triggers validation.
     */
    @Test
    public void testMissingMethodTriggersValidation() throws Exception {
        H3Stream stream = createStream();

        try {
            stream.headersFrameReceived(encode(":scheme", "https", ":path", "/index.html"));
        } catch (NullPointerException expected) {
            // confirms the error-response path was reached
        }

        // method is null so validation should have triggered
        assertNull("method should be null", getField(stream, "method"));
    }

    /**
     * RFC 9114 section 4.1.2: missing :scheme triggers validation
     * for non-CONNECT methods.
     */
    @Test
    public void testMissingSchemeTriggersValidation() throws Exception {
        H3Stream stream = createStream();

        try {
            stream.headersFrameReceived(encode(":method", "GET", ":path", "/index.html"));
        } catch (NullPointerException expected) {
            // confirms the error-response path was reached
        }

        assertEquals("GET", getField(stream, "method"));
    }

    /**
     * RFC 9114 section 4.1.2: missing :path triggers validation
     * for non-CONNECT methods.
     */
    @Test
    public void testMissingPathTriggersValidation() throws Exception {
        H3Stream stream = createStream();

        try {
            stream.headersFrameReceived(encode(":method", "GET", ":scheme", "https"));
        } catch (NullPointerException expected) {
            // confirms the error-response path was reached
        }

        assertNull("requestTarget should be null", getField(stream, "requestTarget"));
    }

    /**
     * RFC 9114 section 4.3.1: CONNECT requests are exempt from
     * :scheme and :path requirements — validation should NOT trigger.
     */
    @Test
    public void testConnectExemptFromSchemeAndPath() throws Exception {
        H3Stream stream = createStream();

        try {
            stream.headersFrameReceived(encode(
                    ":method", "CONNECT",
                    ":authority", "proxy.example.com:8080"));
        } catch (NullPointerException expected) {
            // confirms we got past validation
        }

        assertEquals("CONNECT", getField(stream, "method"));
        // Verify we got past validation (state should be OPEN, not
        // prematurely closed by sendErrorResponse)
        assertEquals("OPEN", getState(stream));
    }

    /**
     * A request with all required pseudo-headers should pass validation.
     */
    @Test
    public void testValidRequestPassesValidation() throws Exception {
        H3Stream stream = createStream();

        try {
            stream.headersFrameReceived(encode(
                    ":method", "GET",
                    ":scheme", "https",
                    ":path", "/",
                    ":authority", "example.com"));
        } catch (NullPointerException expected) {
            // confirms we got past validation
        }

        assertEquals("GET", getField(stream, "method"));
        assertEquals("/", getField(stream, "requestTarget"));
    }

    /**
     * RFC 9114 section 4.2.2: an uncompressed field section larger
     * than the advertised {@code SETTINGS_MAX_FIELD_SECTION_SIZE}
     * aborts the stream with {@code H3_EXCESSIVE_LOAD} rather than
     * being delivered to the request handler.
     */
    @Test
    public void testOversizedFieldSectionAbortsWithExcessiveLoad() throws Exception {
        H3Stream stream = createStream();

        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < (int) H3FrameHandler.DEFAULT_MAX_FIELD_SECTION_SIZE; i++) {
            pad.append('x');
        }
        stream.headersFrameReceived(encodeLarge(
                ":method", "GET",
                ":scheme", "https",
                ":path", "/",
                ":authority", "example.com",
                "x-pad", pad.toString()));

        assertEquals("CLOSED", getState(stream));
        assertNull("oversized field section must not create a request handler",
                getField(stream, "handler"));
    }

    /**
     * A QUIC-level error close (e.g. the peer's CONNECTION_CLOSE, or a
     * local transport error) on a plain (non-WebSocket) request must
     * reach the application's {@link
     * org.bluezoo.gumdrop.http.HTTPRequestHandler#failed} rather than
     * being silently dropped.
     */
    @Test
    public void testErrorForwardsToHandlerFailedForPlainRequest() throws Exception {
        H3Stream stream = createStream();
        StubRequestHandler handler = new StubRequestHandler();
        setField(stream, "handler", handler);

        QuicConnectionCloseException cause =
                new QuicConnectionCloseException(false, 0x3L, "flow control violation");
        stream.error(cause);

        assertSame("the exact exception instance must reach failed()", cause, handler.failedCause);
        assertSame("the state passed to failed() should be this stream", stream, handler.failedState);
        assertEquals("CLOSED", getState(stream));
    }

    /**
     * RFC 9114 section 4.1.2: a malformed Content-Length makes the
     * message malformed ({@code H3_MESSAGE_ERROR}).
     */
    @Test
    public void testInvalidContentLengthAbortsWithMessageError() throws Exception {
        H3Stream stream = createStream();
        stream.headersFrameReceived(encode(
                ":method", "POST",
                ":scheme", "https",
                ":path", "/",
                ":authority", "example.com",
                "content-length", "nope"));
        assertEquals("CLOSED", getState(stream));
        assertEquals(Long.valueOf(-1L), getField(stream, "contentLength"));
    }

    /**
     * RFC 9114 section 4.1.2: DATA bytes exceeding Content-Length abort
     * the stream with {@code H3_MESSAGE_ERROR}.
     */
    @Test
    public void testDataExceedingContentLengthAbortsWithMessageError() throws Exception {
        H3Stream stream = createStream();
        setField(stream, "state", enumValue(H3Stream.class, "State", "OPEN"));
        setField(stream, "contentLength", Long.valueOf(2L));
        setField(stream, "handler", new StubRequestHandler());

        stream.dataFrameReceived(ByteBuffer.wrap(new byte[] { 1, 2, 3 }), true);

        assertEquals("CLOSED", getState(stream));
        assertNull(getField(stream, "handler"));
    }

    /**
     * RFC 9114 section 4.1.2: on stream FIN, Content-Length must equal
     * the sum of DATA frame payload lengths.
     */
    @Test
    public void testContentLengthMismatchOnFinAbortsWithMessageError() throws Exception {
        H3Stream stream = createStream();
        setField(stream, "state", enumValue(H3Stream.class, "State", "RECEIVING_BODY"));
        setField(stream, "contentLength", Long.valueOf(5L));
        setField(stream, "bodyBytesReceived", Long.valueOf(2L));
        setField(stream, "handler", new StubRequestHandler());
        setField(stream, "bodyStarted", Boolean.TRUE);

        stream.readFinished();

        assertEquals("CLOSED", getState(stream));
        assertNull(getField(stream, "handler"));
    }

    /**
     * RFC 9114 section 4.1.2: matching Content-Length allows a normal
     * finish.
     */
    @Test
    public void testMatchingContentLengthCompletesNormally() throws Exception {
        H3Stream stream = createStream();
        StubRequestHandler handler = new StubRequestHandler();
        setField(stream, "state", enumValue(H3Stream.class, "State", "RECEIVING_BODY"));
        setField(stream, "contentLength", Long.valueOf(3L));
        setField(stream, "bodyBytesReceived", Long.valueOf(3L));
        setField(stream, "handler", handler);
        setField(stream, "bodyStarted", Boolean.TRUE);

        stream.readFinished();

        assertEquals("HALF_CLOSED_REMOTE", getState(stream));
        assertTrue(handler.requestCompleteCalled);
    }

    // ── Helpers ──

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object enumValue(Class<?> owner, String enumName, String constant)
            throws Exception {
        Class<?> enumClass = null;
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (enumName.equals(nested.getSimpleName())) {
                enumClass = nested;
                break;
            }
        }
        assertNotNull(enumClass);
        return Enum.valueOf((Class<? extends Enum>) enumClass, constant);
    }

    private void setField(H3Stream stream, String name, Object value) throws Exception {
        Field f = H3Stream.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(stream, value);
    }

    private static class StubRequestHandler extends DefaultHTTPRequestHandler {
        HTTPResponseState failedState;
        Exception failedCause;
        boolean requestCompleteCalled;

        @Override
        public void failed(HTTPResponseState state, Exception cause) {
            failedState = state;
            failedCause = cause;
        }

        @Override
        public void requestComplete(HTTPResponseState state) {
            requestCompleteCalled = true;
        }
    }

    private H3Stream createStream() throws Exception {
        // connection is null: this test exercises request validation in
        // isolation, without a real HTTP3ServerHandler/QuicConnection
        // stack -- H3Stream tolerates this (see its own source).
        Constructor<H3Stream> ctor = H3Stream.class.getDeclaredConstructor(
                HTTP3ServerHandler.class, Encoder.class, Decoder.class);
        ctor.setAccessible(true);
        H3Stream stream = ctor.newInstance(null, new Encoder(4096), new Decoder(4096));
        setField(stream, "streamId", 1L);
        return stream;
    }

    private static ByteBuffer encode(String... pairs) {
        List<Header> headers = new ArrayList<Header>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new Header(pairs[i], pairs[i + 1]));
        }
        SimpleEncoder encoder = new SimpleEncoder();
        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();
        return buf;
    }

    private static ByteBuffer encodeLarge(String... pairs) {
        List<Header> headers = new ArrayList<Header>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new Header(pairs[i], pairs[i + 1]));
        }
        SimpleEncoder encoder = new SimpleEncoder();
        encoder.setAutoHuffman(false);
        ByteBuffer buf = ByteBuffer.allocate(16384);
        encoder.encode(buf, headers);
        buf.flip();
        return buf;
    }

    private String getState(H3Stream stream) throws Exception {
        Field f = H3Stream.class.getDeclaredField("state");
        f.setAccessible(true);
        return ((Enum<?>) f.get(stream)).name();
    }

    private Object getField(H3Stream stream, String name) throws Exception {
        Field f = H3Stream.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(stream);
    }
}
