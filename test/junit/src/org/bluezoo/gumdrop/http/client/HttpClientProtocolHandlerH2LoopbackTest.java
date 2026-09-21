/*
 * HttpClientProtocolHandlerH2LoopbackTest.java
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HTTP/2 response handling on {@link HttpClientProtocolHandler} using
 * synthetic frames on a {@link BinaryRecordingEndpoint}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientProtocolHandlerH2LoopbackTest {

    private static byte[] writeFrames(H2FrameWriter... writers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        H2Writer writer = new H2Writer(channel);
        for (H2FrameWriter w : writers) {
            w.write(writer);
        }
        writer.flush();
        return out.toByteArray();
    }

    private interface H2FrameWriter {
        void write(H2Writer writer) throws IOException;
    }

    private static byte[] serverSettings() throws IOException {
        Map<Integer, Integer> settings = new LinkedHashMap<Integer, Integer>();
        settings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 100);
        settings.put(H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 65535);
        settings.put(H2FrameHandler.SETTINGS_MAX_FRAME_SIZE, 16384);
        return writeFrames(new H2FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeSettings(settings);
            }
        });
    }

    private static ByteBuffer encodeStatus200Headers() throws Exception {
        Encoder encoder = new Encoder(4096, Integer.MAX_VALUE);
        Headers headers = new Headers();
        headers.add(new Header(":status", "200"));
        headers.add(new Header("content-type", "text/plain"));
        ByteBuffer buf = ByteBuffer.allocate(128);
        encoder.encode(buf, headers);
        buf.flip();
        return buf;
    }

    private static void completeServerSettings(HttpClientProtocolHandler handler) throws IOException {
        handler.receive(ByteBuffer.wrap(serverSettings()));
    }

    private static HttpClientProtocolHandler newH2PriorKnowledgeClient(BinaryRecordingEndpoint endpoint) {
        HttpClientProtocolHandler handler =
                new HttpClientProtocolHandler(null, "example.com", 80, false);
        handler.setH2WithPriorKnowledge(true);
        handler.setSendAcceptEncodingHeader(false);
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        return handler;
    }

    private static HttpClientProtocolHandler newH2cUpgradeClient(BinaryRecordingEndpoint endpoint) {
        HttpClientProtocolHandler handler =
                new HttpClientProtocolHandler(null, "example.com", 80, false);
        handler.setH2cUpgradeEnabled(true);
        handler.setH2WithPriorKnowledge(false);
        handler.setSendAcceptEncodingHeader(false);
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        return handler;
    }

    @Test
    public void h2PriorKnowledgeGetReceivesHeadersAndBody() throws Exception {
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        HttpClientProtocolHandler handler = newH2PriorKnowledgeClient(endpoint);
        completeServerSettings(handler);

        RecordingHandler rh = new RecordingHandler();
        handler.get("/resource").send(rh);

        final ByteBuffer responseHeaders = encodeStatus200Headers();
        byte[] response = writeFrames(
                new H2FrameWriter() {
                    @Override
                    public void write(H2Writer writer) throws IOException {
                        writer.writeHeaders(1, responseHeaders, false, true);
                    }
                },
                new H2FrameWriter() {
                    @Override
                    public void write(H2Writer writer) throws IOException {
                        writer.writeData(1, ByteBuffer.wrap("body".getBytes(StandardCharsets.UTF_8)), true);
                    }
                });
        handler.receive(ByteBuffer.wrap(response));

        assertEquals(HttpVersion.HTTP_2_0, handler.getVersion());
        assertTrue(rh.ok);
        assertEquals(HttpStatus.OK, rh.response.getStatus());
        assertTrue(rh.startBody);
        assertTrue(rh.endBody);
        assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), rh.body.toByteArray());
        assertTrue(rh.closed);
    }

    @Test
    public void h2cUpgrade101CompletesOriginalRequestOnStreamOne() throws Exception {
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        HttpClientProtocolHandler handler = newH2cUpgradeClient(endpoint);

        RecordingHandler rh = new RecordingHandler();
        handler.get("/upgraded").send(rh);

        String firstRequest = new String(endpoint.getAllBytes(), StandardCharsets.US_ASCII);
        assertTrue(firstRequest.contains("Upgrade: h2c"));
        assertTrue(firstRequest.contains("HTTP2-Settings:"));

        handler.receive(ByteBuffer.wrap((
                "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Upgrade: h2c\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII)));

        completeServerSettings(handler);

        final ByteBuffer upgradedHeaders = encodeStatus200Headers();
        byte[] h2Response = writeFrames(new H2FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeHeaders(1, upgradedHeaders, true, true);
            }
        });
        handler.receive(ByteBuffer.wrap(h2Response));

        assertEquals(HttpVersion.HTTP_2_0, handler.getVersion());
        assertTrue(rh.ok);
        assertEquals(HttpStatus.OK, rh.response.getStatus());
        assertTrue(rh.closed);
    }

    @Test
    public void h2PingFromServerReceivesAckOnWire() throws Exception {
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        HttpClientProtocolHandler handler = newH2PriorKnowledgeClient(endpoint);
        completeServerSettings(handler);

        endpoint.clearWrites();
        byte[] ping = writeFrames(new H2FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writePing(0x0123456789ABCDEFL, false);
            }
        });
        handler.receive(ByteBuffer.wrap(ping));

        byte[] outbound = endpoint.getAllBytes();
        assertTrue(outbound.length > 0);
    }

    private static final class RecordingHandler extends DefaultHttpResponseHandler {
        HttpResponse response;
        boolean ok;
        boolean startBody;
        boolean endBody;
        boolean closed;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public void ok(HttpResponse response) {
            ok = true;
            this.response = response;
        }

        @Override
        public void startResponseBody() {
            startBody = true;
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            if (data.hasRemaining()) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                body.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void endResponseBody() {
            endBody = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void pushPromise(PushPromise promise) {
            promise.reject();
        }
    }
}
