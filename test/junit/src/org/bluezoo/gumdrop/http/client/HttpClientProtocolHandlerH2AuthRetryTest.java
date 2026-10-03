/*
 * HttpClientProtocolHandlerH2AuthRetryTest.java
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
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import org.bluezoo.gumdrop.testsupport.CollectingResponseHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9110 section 11.6.1: a client configured with credentials answers a
 * 401 challenge by retrying the request with an Authorization header. The
 * retry must work over HTTP/2 as it does over HTTP/1.1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientProtocolHandlerH2AuthRetryTest {

    private interface H2FrameWriter {
        void write(H2Writer writer) throws IOException;
    }

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

    private static byte[] serverSettings() throws IOException {
        final Map<Integer, Integer> settings = new LinkedHashMap<Integer, Integer>();
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

    private static ByteBuffer encodeHeaders(Encoder encoder, String status, String challenge)
            throws Exception {
        Headers headers = new Headers();
        headers.add(new Header(":status", status));
        if (challenge != null) {
            headers.add(new Header("www-authenticate", challenge));
        }
        ByteBuffer buf = ByteBuffer.allocate(256);
        encoder.encode(buf, headers);
        buf.flip();
        return buf;
    }

    private static HttpClientProtocolHandler newClient(BinaryRecordingEndpoint endpoint)
            throws IOException {
        HttpClientProtocolHandler handler =
                new HttpClientProtocolHandler(null, "example.com", 80, false);
        handler.setH2WithPriorKnowledge(true);
        handler.setSendAcceptEncodingHeader(false);
        handler.credentials("alice", "secret");
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        handler.receive(ByteBuffer.wrap(serverSettings()));
        return handler;
    }

    private void challengeThenSucceed(boolean challengeHasBody) throws Exception {
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        HttpClientProtocolHandler handler = newClient(endpoint);
        RecordingHandler rh = new RecordingHandler();
        handler.get("/protected").send(rh);

        Encoder encoder = new Encoder(4096, Integer.MAX_VALUE);
        final ByteBuffer challenge = encodeHeaders(encoder, "401", "Basic realm=\"r\"");
        final boolean withBody = challengeHasBody;
        endpoint.clearWrites();
        handler.receive(ByteBuffer.wrap(writeFrames(
                new H2FrameWriter() {
                    @Override
                    public void write(H2Writer writer) throws IOException {
                        writer.writeHeaders(1, challenge, !withBody, true);
                    }
                },
                new H2FrameWriter() {
                    @Override
                    public void write(H2Writer writer) throws IOException {
                        if (withBody) {
                            writer.writeData(1, ByteBuffer.wrap(
                                    "denied".getBytes(StandardCharsets.UTF_8)), true);
                        }
                    }
                })));

        assertFalse("401 must not reach the handler", rh.errored);
        assertFalse(rh.closed);
        assertTrue("retry request must be written", endpoint.getAllBytes().length > 0);

        final ByteBuffer success = encodeHeaders(encoder, "200", null);
        handler.receive(ByteBuffer.wrap(writeFrames(new H2FrameWriter() {
            @Override
            public void write(H2Writer writer) throws IOException {
                writer.writeHeaders(3, success, true, true);
            }
        })));

        assertTrue(rh.ok);
        assertFalse(rh.errored);
        assertEquals(HttpStatus.OK, rh.status);
        assertTrue(rh.closed);
    }

    @Test
    public void bodylessChallengeIsRetriedWithCredentials() throws Exception {
        challengeThenSucceed(false);
    }

    @Test
    public void challengeWithBodyIsRetriedWithCredentials() throws Exception {
        challengeThenSucceed(true);
    }

    private static final class RecordingHandler extends CollectingResponseHandler {
        boolean ok;
        boolean errored;
        boolean closed;
        HttpStatus status;

        @Override
        public void ok(HttpStatus response) {
            ok = true;
            status = response;
        }

        @Override
        public void error(HttpStatus response) {
            errored = true;
            status = response;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
