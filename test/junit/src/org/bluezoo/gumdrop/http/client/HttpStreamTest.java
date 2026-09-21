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
    public void sendTwiceIsIllegal() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        stream.send(new DefaultHttpResponseHandler());
        try {
            stream.send(new DefaultHttpResponseHandler());
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void bodyBeforeStartIsIllegal() {
        HttpStream stream = new HttpStream(connection, "POST", "/");
        try {
            stream.requestBodyContent(ByteBuffer.wrap(new byte[] { 1 }));
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void headerAfterSendIsIllegal() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        stream.send(new DefaultHttpResponseHandler());
        try {
            stream.header("X-After", "nope");
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void cancelStopsFurtherBodyWrites() {
        HttpStream stream = new HttpStream(connection, "POST", "/");
        stream.startRequestBody(new DefaultHttpResponseHandler());
        stream.cancel();
        assertEquals(0, stream.requestBodyContent(ByteBuffer.wrap(new byte[] { 1 })));
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
    public void sendAfterCancelThrows() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        stream.cancel();
        try {
            stream.send(new DefaultHttpResponseHandler());
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void startRequestBodyAfterSendThrows() {
        HttpStream stream = new HttpStream(connection, "GET", "/");
        stream.send(new DefaultHttpResponseHandler());
        try {
            stream.startRequestBody(new DefaultHttpResponseHandler());
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void endRequestBodyTwiceThrows() {
        HttpStream stream = new HttpStream(connection, "POST", "/");
        stream.startRequestBody(new DefaultHttpResponseHandler());
        stream.endRequestBody();
        try {
            stream.endRequestBody();
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void requestBodyContentAfterEndThrows() {
        HttpStream stream = new HttpStream(connection, "POST", "/");
        stream.startRequestBody(new DefaultHttpResponseHandler());
        stream.endRequestBody();
        try {
            stream.requestBodyContent(ByteBuffer.wrap(new byte[] { 1 }));
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void postBodyDelegatesToConnectionOps() {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/upload");
        stream.startRequestBody(new DefaultHttpResponseHandler());
        assertEquals(3, stream.requestBodyContent(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
        stream.endRequestBody();
        assertTrue(ops.events.contains("send:true"));
        assertTrue(ops.events.contains("body:3"));
        assertTrue(ops.events.contains("end"));
    }

    @Test
    public void gzipRequestBodyUsesEncodedSendPath() throws Exception {
        RecordingOps ops = new RecordingOps();
        HttpStream stream = new HttpStream(ops, "POST", "/");
        stream.header("Content-Encoding", "gzip");
        stream.startRequestBody(new DefaultHttpResponseHandler());
        byte[] plain = "payload".getBytes(StandardCharsets.UTF_8);
        assertNotNull(stream.getOrCreateRequestContentEncoder());
        int consumed = stream.requestBodyContent(ByteBuffer.wrap(plain));
        assertEquals(plain.length, consumed);
        stream.endRequestBody();
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
            public void responseBodyContent(ByteBuffer data) {
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
