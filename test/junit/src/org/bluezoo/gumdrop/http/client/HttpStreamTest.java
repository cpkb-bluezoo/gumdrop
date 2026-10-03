/*
 * HttpStreamTest.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.bluezoo.gumdrop.http.ContentEncoding;
import org.bluezoo.gumdrop.http.PriorityParams;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * State and header behaviour for {@link HttpStream}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpStreamTest {

    private HttpClientProtocolHandler connection;
    private BinaryRecordingEndpoint endpoint;

    @Before
    public void setUp() {
        connection = new HttpClientProtocolHandler(null, "localhost", 80, false);
        endpoint = new BinaryRecordingEndpoint();
        connection.connected(endpoint);
    }

    @Test
    public void priorityAddsPriorityHeader() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        stream.priority(32);
        assertEquals("u=" + PriorityParams.urgencyFromWeight(32),
                stream.getHeaders().getValue(PriorityParams.PRIORITY_HEADER));
    }

    @Test
    public void endMessageTwiceIsIllegal() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        stream.endMessage();
        try {
            stream.endMessage();
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void nothingIsSentUntilTheMessageEnds() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "GET", "/");
        stream.header("X-A", "b");
        assertTrue(ops.events.isEmpty());
        stream.endMessage();
        assertEquals(1, ops.events.size());
        assertEquals("send:false", ops.events.get(0));
    }

    @Test
    public void headerAfterEndMessageIsIllegal() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        stream.endMessage();
        try {
            stream.header("X-After", "nope");
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void headerAfterEndHeadersIsIllegal() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        stream.endHeaders();
        assertEquals("send:true", ops.events.get(0));
        try {
            stream.header("X-After", "nope");
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
        try {
            stream.endHeaders();
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void headerAfterSecondBodyPieceIsIllegal() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        stream.bodyContent(ByteBuffer.wrap(new byte[] { 1 }));
        stream.header("X-Still", "ok");
        stream.bodyContent(ByteBuffer.wrap(new byte[] { 2 }));
        try {
            stream.header("X-After", "nope");
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void emptyBodyBufferSendsNothing() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        assertEquals(0, stream.bodyContent(ByteBuffer.allocate(0)));
        assertTrue(ops.events.isEmpty());
    }

    @Test
    public void cancelStopsFurtherBodyWrites() {
        HttpStream stream = new HttpStream(connection, "POST", "/");
        stream.cancel();
        assertEquals(0, stream.bodyContent(ByteBuffer.wrap(new byte[] { 1 })));
    }

    @Test
    public void endMessageAfterCancelIsANoOp() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "GET", "/");
        stream.cancel();
        stream.endMessage();
        assertEquals(1, ops.events.size());
        assertEquals("cancel", ops.events.get(0));
    }

    @Test
    public void firstBodyPieceIsHeldThenFlushedWhenASecondArrives() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        assertEquals(3, stream.bodyContent(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
        assertTrue("the first piece is held back", ops.events.isEmpty());
        assertEquals(2, stream.bodyContent(ByteBuffer.wrap(new byte[] { 4, 5 })));
        assertEquals(3, ops.events.size());
        assertEquals("send:true", ops.events.get(0));
        assertEquals("body:3", ops.events.get(1));
        assertEquals("body:2", ops.events.get(2));
        stream.endMessage();
        assertEquals("end", ops.events.get(3));
    }

    @Test
    public void singleBodyPieceIsSentWithTheEndOfTheMessage() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        assertEquals(3, stream.bodyContent(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
        stream.endMessage();
        assertEquals(2, ops.events.size());
        assertEquals("send:true", ops.events.get(0));
        assertEquals("last:3", ops.events.get(1));
        assertEquals("3", stream.getHeaders().getValue("Content-Length"));
    }

    @Test
    public void singleBodyPieceKeepsAnExplicitLength() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        stream.header("Transfer-Encoding", "chunked");
        stream.bodyContent(ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
        stream.endMessage();
        assertNull(stream.getHeaders().getValue("Content-Length"));
    }

    @Test
    public void singleBodyPieceOnAWireConnectionIsSentWithContentLength() {
        HttpStream stream = (HttpStream) connection.post("/up", null);
        stream.bodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
        stream.endMessage();
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire, wire.contains("Content-Length: 3\r\n"));
        assertTrue(wire, !wire.contains("chunked"));
        assertTrue(wire, wire.endsWith("\r\n\r\nabc"));
    }

    @Test
    public void twoBodyPiecesOnAWireConnectionAreChunked() {
        HttpStream stream = (HttpStream) connection.post("/up", null);
        stream.bodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
        stream.bodyContent(ByteBuffer.wrap("de".getBytes(StandardCharsets.US_ASCII)));
        stream.endMessage();
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire, wire.contains("Transfer-Encoding: chunked\r\n"));
        assertTrue(wire, wire.endsWith("\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"));
    }

    @Test
    public void dependencyAndExclusiveAreStored() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        HttpStream parent = new HttpStream(connection, "GET", "/warmup");
        stream.dependency(parent);
        stream.exclusive(true);
        assertSame(parent, stream.getDependency());
        assertTrue(stream.isExclusive());
    }

    @Test
    public void toStringReturnsMethodAndPath() {
        HttpStream stream = new HttpStream(connection, "PATCH", "/items/1");
        assertEquals("PATCH /items/1", stream.toString());
    }

    @Test
    public void bodyAfterEndMessageIsIllegal() {
        HttpStream stream = new HttpStream(connection, "POST", "/");
        stream.endMessage();
        try {
            stream.bodyContent(ByteBuffer.wrap(new byte[] { 1 }));
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void postBodyDelegatesToConnectionOps() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/upload");
        assertEquals(3, stream.bodyContent(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
        assertEquals(1, stream.bodyContent(ByteBuffer.wrap(new byte[] { 4 })));
        stream.endMessage();
        assertTrue(ops.events.contains("send:true"));
        assertTrue(ops.events.contains("body:3"));
        assertTrue(ops.events.contains("body:1"));
        assertTrue(ops.events.contains("end"));
    }

    @Test
    public void gzipRequestBodyUsesEncodedSendPath() throws Exception {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        stream.header("Content-Encoding", "gzip");
        byte[] plain = "payload".getBytes(StandardCharsets.UTF_8);
        assertNotNull(stream.getOrCreateRequestContentEncoder());
        int consumed = stream.bodyContent(ByteBuffer.wrap(plain));
        assertEquals(plain.length, consumed);
        stream.bodyContent(ByteBuffer.wrap(plain));
        stream.endMessage();
        assertTrue(eventsContainPrefix(ops.events, "encoded:"));
        stream.closeRequestContentEncoder();
    }

    @Test
    public void unsupportedRequestContentEncodingThrows() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        stream.header("Content-Encoding", "compress");
        try {
            stream.getOrCreateRequestContentEncoder();
            assertTrue("expected ContentEncodingException", false);
        } catch (ContentEncoding.ContentEncodingException expected) {
        }
    }

    @Test
    public void encodingDisabledIgnoresContentEncodingHeader() throws Exception {
        RecordingOps ops = new RecordingOps();
        ops.encodeRequestBody = false;
        HttpStream stream = new HttpStream(ops, "POST", "/");
        stream.header("Content-Encoding", "gzip");
        assertNull(stream.getOrCreateRequestContentEncoder());
    }

    @Test
    public void inboundGzipResponseIsDrainedToHandler() throws Exception {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "GET", "/");
        stream.setInboundResponseDecoder(ContentEncoding.Coding.GZIP);
        stream.setMessageEvents();

        byte[] plain = "decoded-body".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gzipOut = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(gzipOut);
        gz.write(plain);
        gz.close();
        ContentEncoding.Decoder decoder = stream.getInboundResponseDecoder();
        decoder.write(ByteBuffer.wrap(gzipOut.toByteArray()), true);

        final ByteArrayOutputStream seen = new ByteArrayOutputStream();
        stream.drainInboundResponseDecoded(new DefaultHttpResponseHandler() {
            @Override
            public void bodyContent(ByteBuffer data) {
                if (data.hasRemaining()) {
                    byte[] chunk = new byte[data.remaining()];
                    data.get(chunk);
                    seen.write(chunk, 0, chunk.length);
                }
            }

            @Override
            public void pushPromise(PushPromise promise) {
                promise.reject();
            }
        });
        assertArrayEquals(plain, seen.toByteArray());

        stream.finishInboundResponseDecoded(new DefaultHttpResponseHandler() {
            @Override
            public void pushPromise(PushPromise promise) {
                promise.reject();
            }
        });
        assertNull(stream.getInboundResponseDecoder());
    }

    @Test
    public void clearingInboundDecoderClosesPrevious() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "GET", "/");
        stream.setInboundResponseDecoder(ContentEncoding.Coding.GZIP);
        assertNotNull(stream.getInboundResponseDecoder());
        stream.setInboundResponseDecoder(null);
        assertNull(stream.getInboundResponseDecoder());
    }

    private static boolean eventsContainPrefix(List<String> events, String prefix) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final class RecordingOps implements HttpClientConnectionOps {
        boolean encodeRequestBody = true;
        final List<String> events = new ArrayList<String>();

        @Override
        public void sendRequest(HttpStream request, boolean hasBody) {
            events.add("send:" + hasBody);
        }

        @Override
        public int sendRequestBody(HttpStream request, ByteBuffer data) {
            events.add("body:" + data.remaining());
            return data.remaining();
        }

        @Override
        public int sendRequestBodyEncoded(HttpStream request, ByteBuffer data, boolean end) {
            events.add("encoded:" + data.remaining() + ":" + end);
            return data.remaining();
        }

        @Override
        public void sendLastRequestBody(HttpStream request, ByteBuffer data) {
            events.add("last:" + data.remaining());
        }

        @Override
        public void endRequestBody(HttpStream request) {
            events.add("end");
        }

        @Override
        public void cancelRequest(HttpStream request) {
            events.add("cancel");
        }

        @Override
        public boolean isEncodeRequestBodyContentCoding() {
            return encodeRequestBody;
        }
    }
}
