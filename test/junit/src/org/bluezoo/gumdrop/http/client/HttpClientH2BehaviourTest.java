/*
 * HttpClientH2BehaviourTest.java
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPOutputStream;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.http.hpack.HeaderHandler;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * HTTP/2 behaviour of {@link HttpClientProtocolHandler} driven by synthetic
 * server frames: request encoding, flow control, stream and connection errors,
 * push promises, settings handling and graceful shutdown. Client output is
 * parsed back into frames and asserted on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientH2BehaviourTest {

    private static final int DATA = 0;
    private static final int HEADERS = 1;
    private static final int RST_STREAM = 3;
    private static final int SETTINGS = 4;
    private static final int PING = 6;
    private static final int GOAWAY = 7;
    private static final int WINDOW_UPDATE = 8;

    private interface FrameWriter {
        void write(H2Writer writer) throws IOException;
    }

    /** One parsed client-to-server frame. */
    private static final class Frame {
        final int type;
        final int flags;
        final int streamId;
        final byte[] payload;

        Frame(int type, int flags, int streamId, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }

        boolean endStream() {
            return (flags & 0x1) != 0;
        }

        int intAt(int offset) {
            return ByteBuffer.wrap(payload).getInt(offset);
        }
    }

    private static final class Recorder extends DefaultHttpResponseHandler {
        int okCalls;
        int errorCalls;
        HttpStatus status;
        boolean startBody;
        boolean endBody;
        int closeCalls;
        final List<Exception> failures = new ArrayList<Exception>();
        final List<String> headers = new ArrayList<String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final List<PushPromise> promises = new ArrayList<PushPromise>();
        boolean acceptPush;
        Recorder pushTarget;

        @Override
        public void ok(HttpResponse response) {
            okCalls++;
            status = response.getStatus();
        }

        @Override
        public void error(HttpResponse response) {
            errorCalls++;
            status = response.getStatus();
        }

        @Override
        public void header(String name, String value) {
            headers.add(name + ": " + value);
        }

        @Override
        public void startResponseBody() {
            startBody = true;
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            body.write(b, 0, b.length);
        }

        @Override
        public void endResponseBody() {
            endBody = true;
        }

        @Override
        public void close() {
            closeCalls++;
        }

        @Override
        public void failed(Exception ex) {
            failures.add(ex);
        }

        @Override
        public void pushPromise(PushPromise promise) {
            promises.add(promise);
            if (acceptPush) {
                promise.accept(pushTarget);
            } else {
                promise.reject();
            }
        }
    }

    private static final class Conn implements HttpClientHandler {
        int disconnected;
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onError(Exception cause) {
            errors.add(cause);
        }

        @Override
        public void onDisconnected() {
            disconnected++;
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }

    private HttpClientProtocolHandler handler;
    private BinaryRecordingEndpoint endpoint;
    private Encoder serverEncoder;
    private Conn conn;

    @Before
    public void setUp() throws Exception {
        conn = new Conn();
        handler = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        handler.setH2WithPriorKnowledge(true);
        handler.setSendAcceptEncodingHeader(false);
        endpoint = new BinaryRecordingEndpoint();
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        serverEncoder = new Encoder(4096, Integer.MAX_VALUE);
    }

    // ── helpers ──

    private static byte[] frames(FrameWriter... writers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        H2Writer writer = new H2Writer(channel);
        for (FrameWriter w : writers) {
            w.write(writer);
        }
        writer.flush();
        return out.toByteArray();
    }

    private void server(FrameWriter... writers) throws IOException {
        handler.receive(ByteBuffer.wrap(frames(writers)));
    }

    private void settings(final int... idValuePairs) throws IOException {
        final Map<Integer, Integer> map = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < idValuePairs.length; i += 2) {
            map.put(idValuePairs[i], idValuePairs[i + 1]);
        }
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeSettings(map);
            }
        });
    }

    private ByteBuffer block(String... nameValue) throws Exception {
        Headers h = new Headers();
        for (int i = 0; i < nameValue.length; i += 2) {
            h.add(new Header(nameValue[i], nameValue[i + 1]));
        }
        ByteBuffer buf = ByteBuffer.allocate(4096);
        serverEncoder.encode(buf, h);
        buf.flip();
        return buf;
    }

    private void headers(final int streamId, final boolean endStream, final String... nameValue)
            throws Exception {
        final ByteBuffer b = block(nameValue);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeHeaders(streamId, b, endStream, true);
            }
        });
    }

    private void data(final int streamId, final byte[] bytes, final boolean endStream) throws IOException {
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeData(streamId, ByteBuffer.wrap(bytes), endStream);
            }
        });
    }

    private void respond200(int streamId, String body) throws Exception {
        headers(streamId, false, ":status", "200", "content-type", "text/plain");
        data(streamId, body.getBytes(StandardCharsets.US_ASCII), true);
    }

    /** Parses everything the client wrote so far into frames (skipping the preface). */
    private List<Frame> clientFrames() {
        byte[] all = endpoint.getAllBytes();
        int pos = 0;
        if (all.length >= 24 && all[0] == 'P' && all[1] == 'R' && all[2] == 'I') {
            pos = 24;
        }
        List<Frame> out = new ArrayList<Frame>();
        while (pos + 9 <= all.length) {
            int len = ((all[pos] & 0xFF) << 16) | ((all[pos + 1] & 0xFF) << 8) | (all[pos + 2] & 0xFF);
            int type = all[pos + 3] & 0xFF;
            int flags = all[pos + 4] & 0xFF;
            int sid = ByteBuffer.wrap(all, pos + 5, 4).getInt() & 0x7FFFFFFF;
            byte[] payload = new byte[len];
            System.arraycopy(all, pos + 9, payload, 0, len);
            out.add(new Frame(type, flags, sid, payload));
            pos += 9 + len;
        }
        return out;
    }

    private List<Frame> framesOfType(int type) {
        List<Frame> out = new ArrayList<Frame>();
        for (Frame f : clientFrames()) {
            if (f.type == type) {
                out.add(f);
            }
        }
        return out;
    }

    private static Map<String, String> decode(byte[] block) throws Exception {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        new Decoder(4096).decode(ByteBuffer.wrap(block), new HeaderHandler() {
            @Override
            public void header(Header header) {
                out.put(header.getName(), header.getValue());
            }
        });
        return out;
    }

    private Recorder sendGet(String path) {
        Recorder r = new Recorder();
        handler.get(path).send(r);
        return r;
    }

    private void ready() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 100,
                H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 65535,
                H2FrameHandler.SETTINGS_MAX_FRAME_SIZE, 16384);
    }

    // ── connection setup ──

    @Test
    public void prefaceSettingsAndSettingsAck() throws Exception {
        byte[] all = endpoint.getAllBytes();
        String preface = new String(all, 0, 24, StandardCharsets.US_ASCII);
        assertEquals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", preface);
        List<Frame> frames = clientFrames();
        assertEquals(SETTINGS, frames.get(0).type);
        assertEquals(HttpVersion.HTTP_2_0, handler.getVersion());
        ready();
        List<Frame> settingsFrames = framesOfType(SETTINGS);
        assertEquals(2, settingsFrames.size());
        assertEquals("the second is the ACK", 1, settingsFrames.get(1).flags & 1);
    }

    @Test
    public void requestHeadersAreEncodedWithoutHttp1FramingHeaders() throws Exception {
        ready();
        HttpRequest r = handler.get("/res?q=1");
        r.header("X-Custom", "v");
        r.header("Connection", "keep-alive");
        r.header("Host", "ignored.example");
        r.send(new Recorder());
        Frame f = framesOfType(HEADERS).get(0);
        assertEquals(1, f.streamId);
        assertTrue(f.endStream());
        assertEquals(4, f.flags & 4);
        Map<String, String> h = decode(f.payload);
        assertEquals("GET", h.get(":method"));
        assertEquals("http", h.get(":scheme"));
        assertEquals("example.com:80", h.get(":authority"));
        assertEquals("/res?q=1", h.get(":path"));
        assertEquals("v", h.get("x-custom"));
        assertFalse(h.toString(), h.containsKey("connection"));
        assertFalse(h.toString(), h.containsKey("host"));
    }

    @Test
    public void secureRequestUsesHttpsSchemeAndSequentialOddStreamIds() throws Exception {
        handler = new HttpClientProtocolHandler(conn, "::1", 8443, true);
        handler.setH2WithPriorKnowledge(true);
        endpoint = new BinaryRecordingEndpoint();
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        handler.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256", "h2"));
        ready();
        sendGet("/a");
        sendGet("/b");
        List<Frame> hs = framesOfType(HEADERS);
        assertEquals(1, hs.get(0).streamId);
        assertEquals(3, hs.get(1).streamId);
        Map<String, String> h = decode(hs.get(0).payload);
        assertEquals("https", h.get(":scheme"));
        assertEquals("[::1]:8443", h.get(":authority"));
    }

    @Test
    public void priorityHeaderIsSentForPriorityRequests() throws Exception {
        ready();
        HttpRequest r = handler.get("/p");
        r.priority(256);
        r.dependency(null);
        r.exclusive(true);
        r.send(new Recorder());
        Map<String, String> h = decode(framesOfType(HEADERS).get(0).payload);
        assertNotNull(h.toString(), h.get("priority"));
    }

    // ── responses ──

    @Test
    public void responseWithTrailersCompletes() throws Exception {
        ready();
        Recorder r = sendGet("/");
        headers(1, false, ":status", "200", "x-a", "1");
        data(1, "abc".getBytes(StandardCharsets.US_ASCII), false);
        headers(1, true, "x-trailer", "t");
        assertEquals(1, r.okCalls);
        assertEquals("abc", new String(r.body.toByteArray(), StandardCharsets.US_ASCII));
        assertTrue(r.endBody);
        assertEquals(1, r.closeCalls);
        assertTrue(r.headers.toString(), r.headers.contains("x-a: 1"));
    }

    @Test
    public void errorStatusAndBodilessResponse() throws Exception {
        ready();
        Recorder r = sendGet("/");
        headers(1, true, ":status", "404");
        assertEquals(1, r.errorCalls);
        assertEquals(HttpStatus.NOT_FOUND, r.status);
        assertFalse(r.startBody);
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void windowUpdatesReplenishTheServerSendWindow() throws Exception {
        ready();
        sendGet("/");
        headers(1, false, ":status", "200");
        for (int i = 0; i < 4; i++) {
            data(1, new byte[16000], false);
        }
        data(1, new byte[1000], true);
        List<Frame> wu = framesOfType(WINDOW_UPDATE);
        assertFalse("flow control credit must be returned", wu.isEmpty());
        boolean connection = false;
        for (Frame f : wu) {
            if (f.streamId == 0) {
                connection = true;
            }
        }
        assertTrue(connection);
    }

    @Test
    public void interimResponsesAreNotReportedAsTheFinalOne() throws Exception {
        ready();
        Recorder r = sendGet("/");
        headers(1, false, ":status", "103", "link", "</style.css>; rel=preload");
        headers(1, false, ":status", "200");
        data(1, "x".getBytes(StandardCharsets.US_ASCII), true);
        assertEquals("only the final response is reported: " + r.status, 1, r.okCalls + r.errorCalls);
        assertEquals(HttpStatus.OK, r.status);
        assertFalse(r.headers.toString(), r.headers.toString().contains("link"));
    }

    @Test
    public void malformedStatusDoesNotEscapeIntoTheTransport() throws Exception {
        ready();
        Recorder r = sendGet("/");
        try {
            headers(1, true, ":status", "abc");
        } catch (RuntimeException e) {
            fail("a malformed :status must be handled, not thrown: " + e);
        }
        assertEquals(1, r.failures.size());
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(1, rst.get(0).streamId);
        assertEquals(H2FrameHandler.ERROR_PROTOCOL_ERROR, rst.get(0).intAt(0));
    }

    @Test
    public void gzipResponseIsDecoded() throws Exception {
        ready();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write("zipped body".getBytes(StandardCharsets.US_ASCII));
        gz.close();
        Recorder r = sendGet("/");
        headers(1, false, ":status", "200", "content-encoding", "gzip");
        data(1, bos.toByteArray(), true);
        assertEquals("zipped body", new String(r.body.toByteArray(), StandardCharsets.US_ASCII));
        assertFalse(r.headers.toString(), r.headers.toString().contains("content-encoding"));
    }

    @Test
    public void continuationFramesAssembleTheHeaderBlock() throws Exception {
        ready();
        Recorder r = sendGet("/");
        ByteBuffer b = block(":status", "200", "x-long", "abcdefghijklmnopqrstuvwxyz");
        final byte[] all = new byte[b.remaining()];
        b.get(all);
        final int half = all.length / 2;
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeHeaders(1, ByteBuffer.wrap(all, 0, half), true, false);
                w.writeContinuation(1, ByteBuffer.wrap(all, half, all.length - half), true);
            }
        });
        assertEquals(1, r.okCalls);
        assertTrue(r.headers.toString(), r.headers.contains("x-long: abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    public void altSvcHeaderIsReportedOnce() throws Exception {
        final List<String> seen = new ArrayList<String>();
        handler.setAltSvcListener(new AltSvcListener() {
            @Override
            public void altSvcReceived(String value) {
                seen.add(value);
            }
        });
        ready();
        sendGet("/a");
        headers(1, true, ":status", "200", "alt-svc", "h3=\":443\"");
        sendGet("/b");
        headers(3, true, ":status", "200", "alt-svc", "h3=\":444\"");
        assertEquals(1, seen.size());
    }

    // ── errors on unknown streams and frames ──

    @Test
    public void dataAndHeadersForUnknownStreamsAreReset() throws Exception {
        ready();
        data(5, new byte[] {1}, false);
        headers(7, true, ":status", "200");
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(2, rst.size());
        assertEquals(H2FrameHandler.ERROR_STREAM_CLOSED, rst.get(0).intAt(0));
        assertEquals(5, rst.get(0).streamId);
        assertEquals(7, rst.get(1).streamId);
    }

    @Test
    public void rstStreamFromServerFailsTheRequest() throws Exception {
        ready();
        Recorder r = sendGet("/");
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeRstStream(1, H2FrameHandler.ERROR_REFUSED_STREAM);
            }
        });
        assertEquals(1, r.failures.size());
        assertTrue(r.failures.get(0).getMessage(), r.failures.get(0).getMessage().contains("REFUSED_STREAM"));
    }

    @Test
    public void goawayFailsStreamsBeyondTheLastProcessedAndClosesTheConnection() throws Exception {
        ready();
        Recorder first = sendGet("/a");
        Recorder second = sendGet("/b");
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeGoaway(1, H2FrameHandler.ERROR_NO_ERROR);
            }
        });
        assertEquals(1, second.failures.size());
        assertEquals("Connection closed by server", second.failures.get(0).getMessage());
        assertEquals(1, first.failures.size());
        assertFalse(handler.isOpen());
        assertTrue(endpoint.getCloseCount() > 0);
    }

    @Test
    public void priorityUpdateFromServerIsAProtocolError() throws Exception {
        ready();
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writePriorityUpdate(1, "u=2");
            }
        });
        List<Frame> go = framesOfType(GOAWAY);
        assertEquals(1, go.size());
        assertEquals(H2FrameHandler.ERROR_PROTOCOL_ERROR, go.get(0).intAt(4));
        assertFalse(handler.isOpen());
    }

    @Test
    public void pingIsAcknowledgedWithTheSamePayload() throws Exception {
        ready();
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writePing(0x1122334455667788L, false);
                w.writePing(0x99L, true);
            }
        });
        List<Frame> pings = framesOfType(PING);
        assertEquals(1, pings.size());
        assertEquals(1, pings.get(0).flags & 1);
        assertEquals(0x11223344, pings.get(0).intAt(0));
    }

    @Test
    public void hpackGarbageIsACompressionError() throws Exception {
        ready();
        sendGet("/");
        final ByteBuffer garbage = ByteBuffer.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeHeaders(1, garbage, false, true);
            }
        });
        List<Frame> go = framesOfType(GOAWAY);
        assertEquals(1, go.size());
        assertEquals(H2FrameHandler.ERROR_COMPRESSION_ERROR, go.get(0).intAt(4));
    }

    @Test
    public void frameErrorsBecomeGoawayOrReset() throws Exception {
        ready();
        handler.frameError(H2FrameHandler.ERROR_FRAME_SIZE_ERROR, 0, "too big");
        handler.frameError(H2FrameHandler.ERROR_PROTOCOL_ERROR, 3, "bad");
        assertEquals(1, framesOfType(GOAWAY).size());
        assertEquals(1, framesOfType(RST_STREAM).size());
    }

    // ── settings ──

    @Test
    public void connectProtocolSettingIsTrackedAndCallbacksRun() throws Exception {
        final int[] ran = new int[2];
        handler.whenConnectProtocolKnown(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertEquals(0, ran[0]);
        assertFalse(handler.isConnectProtocolEnabled());
        settings(H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1,
                H2FrameHandler.SETTINGS_ENABLE_PUSH, 0,
                H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE, 2048,
                H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE, 4000);
        assertEquals(1, ran[0]);
        assertTrue(handler.isConnectProtocolEnabled());
        handler.whenConnectProtocolKnown(new Runnable() {
            @Override
            public void run() {
                ran[1]++;
            }
        });
        assertEquals("known already: runs at once", 1, ran[1]);
    }

    @Test
    public void pushIsRefusedWhenTheServerSettingDisablesIt() throws Exception {
        settings(H2FrameHandler.SETTINGS_ENABLE_PUSH, 0);
        Recorder r = sendGet("/");
        pushPromise(1, 2, ":method", "GET", ":path", "/pushed", ":scheme", "http", ":authority", "example.com");
        assertTrue(r.promises.isEmpty());
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(2, rst.get(0).streamId);
        assertEquals(H2FrameHandler.ERROR_REFUSED_STREAM, rst.get(0).intAt(0));
    }

    @Test
    public void initialWindowOverflowIsAFlowControlError() throws Exception {
        ready();
        sendGet("/");
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(1, 1000);
            }
        });
        settings(H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, Integer.MAX_VALUE);
        List<Frame> go = framesOfType(GOAWAY);
        assertEquals(1, go.size());
        assertEquals(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR, go.get(0).intAt(4));
        assertFalse(handler.isOpen());
    }

    @Test
    public void windowUpdateOverflowOnTheConnectionAndOnAStream() throws Exception {
        ready();
        sendGet("/");
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(1, Integer.MAX_VALUE);
            }
        });
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR, rst.get(0).intAt(0));
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(0, Integer.MAX_VALUE);
            }
        });
        assertEquals(1, framesOfType(GOAWAY).size());
        assertFalse(handler.isOpen());
    }

    // ── push promise ──

    private void pushPromise(final int stream, final int promised, String... nameValue) throws Exception {
        final ByteBuffer b = block(nameValue);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writePushPromise(stream, promised, b, true);
            }
        });
    }

    @Test
    public void rejectedPushPromiseIsReset() throws Exception {
        ready();
        Recorder r = sendGet("/");
        pushPromise(1, 2, ":method", "GET", ":path", "/pushed", ":scheme", "http", ":authority", "h");
        assertEquals(1, r.promises.size());
        PushPromise p = r.promises.get(0);
        assertEquals("GET", p.getMethod());
        assertEquals("/pushed", p.getPath());
        assertEquals("h", p.getAuthority());
        assertEquals("http", p.getScheme());
        assertNotNull(p.getHeaders());
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(2, rst.get(0).streamId);
    }

    @Test
    public void acceptedPushPromiseDeliversThePushedResponse() throws Exception {
        ready();
        Recorder r = sendGet("/");
        r.acceptPush = true;
        r.pushTarget = new Recorder();
        pushPromise(1, 2, ":method", "GET", ":path", "/pushed", ":scheme", "http", ":authority", "h");
        assertEquals(1, r.promises.size());
        headers(2, false, ":status", "200");
        data(2, "pushed".getBytes(StandardCharsets.US_ASCII), true);
        assertEquals("pushed", new String(r.pushTarget.body.toByteArray(), StandardCharsets.US_ASCII));
        assertTrue(framesOfType(RST_STREAM).isEmpty());
    }

    @Test
    public void pushPromiseOnAStreamWithoutHandlerIsRefused() throws Exception {
        ready();
        pushPromise(9, 2, ":method", "GET", ":path", "/x");
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(H2FrameHandler.ERROR_REFUSED_STREAM, rst.get(0).intAt(0));
    }

    // ── request bodies and flow control ──

    @Test
    public void requestBodyIsSentAsDataFramesAndEnded() throws Exception {
        ready();
        HttpRequest r = handler.post("/up");
        r.startRequestBody(new Recorder());
        assertFalse("the headers do not end the stream", framesOfType(HEADERS).get(0).endStream());
        assertEquals(3, r.requestBodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(0, r.requestBodyContent(ByteBuffer.allocate(0)));
        r.endRequestBody();
        List<Frame> data = framesOfType(DATA);
        assertEquals(2, data.size());
        assertEquals("abc", new String(data.get(0).payload, StandardCharsets.US_ASCII));
        assertFalse(data.get(0).endStream());
        assertTrue(data.get(1).endStream());
        assertEquals(0, data.get(1).payload.length);
    }

    @Test
    public void largeRequestBodyIsSplitAtTheMaximumFrameSize() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_FRAME_SIZE, 16384,
                H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 1000000);
        HttpRequest r = handler.post("/up");
        r.startRequestBody(new Recorder());
        r.requestBodyContent(ByteBuffer.wrap(new byte[40000]));
        r.endRequestBody();
        List<Frame> data = framesOfType(DATA);
        int total = 0;
        for (Frame f : data) {
            assertTrue(f.payload.length <= 16384);
            total += f.payload.length;
        }
        assertEquals(40000, total);
        assertTrue(data.get(data.size() - 1).endStream());
    }

    @Test
    public void flowControlQueuesDataAndEndOfStreamUntilTheWindowOpens() throws Exception {
        settings(H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 10);
        HttpRequest r = handler.post("/up");
        r.startRequestBody(new Recorder());
        r.requestBodyContent(ByteBuffer.wrap("0123456789abcdefghij0123456789".getBytes(StandardCharsets.US_ASCII)));
        r.endRequestBody();
        List<Frame> data = framesOfType(DATA);
        assertEquals(1, data.size());
        assertEquals(10, data.get(0).payload.length);
        for (Frame f : data) {
            assertFalse("END_STREAM must wait for the queued data: ", f.endStream());
        }
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(1, 15);
                w.writeWindowUpdate(0, 100);
            }
        });
        data = framesOfType(DATA);
        int total = 0;
        for (int i = 0; i < data.size(); i++) {
            total += data.get(i).payload.length;
            if (data.get(i).endStream()) {
                assertEquals("END_STREAM only on the last frame", data.size() - 1, i);
            }
        }
        assertEquals(25, total);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(1, 100);
            }
        });
        data = framesOfType(DATA);
        total = 0;
        for (Frame f : data) {
            total += f.payload.length;
        }
        assertEquals(30, total);
        assertTrue(data.get(data.size() - 1).endStream());
    }

    // ── concurrency, cancellation, shutdown ──

    @Test
    public void requestsBeyondMaxConcurrentStreamsAreQueued() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 1);
        Recorder a = sendGet("/a");
        Recorder b = sendGet("/b");
        assertEquals("the second waits", 1, framesOfType(HEADERS).size());
        respond200(1, "A");
        assertEquals(1, a.closeCalls);
        assertEquals("the queued request is released", 2, framesOfType(HEADERS).size());
        assertEquals(3, framesOfType(HEADERS).get(1).streamId);
        respond200(3, "B");
        assertEquals(1, b.closeCalls);
    }

    @Test
    public void cancelSendsRstStreamAndPromotesQueuedRequests() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 1);
        HttpRequest first = handler.get("/a");
        Recorder a = new Recorder();
        first.send(a);
        Recorder b = sendGet("/b");
        first.cancel();
        assertEquals(1, a.failures.size());
        assertTrue(a.failures.get(0) instanceof CancellationException);
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(H2FrameHandler.ERROR_CANCEL, rst.get(0).intAt(0));
        assertEquals("the queued request now goes out", 2, framesOfType(HEADERS).size());
        assertTrue(b.failures.isEmpty());
    }

    @Test
    public void cancellingAQueuedRequestRemovesItFromTheQueue() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 1);
        sendGet("/a");
        HttpRequest queued = handler.get("/b");
        Recorder q = new Recorder();
        queued.send(q);
        queued.cancel();
        respond200(1, "A");
        assertEquals("the cancelled request is never sent", 1, framesOfType(HEADERS).size());
    }

    @Test
    public void closeSendsGoawayAndFailsOutstandingRequests() throws Exception {
        ready();
        Recorder r = sendGet("/");
        handler.close();
        assertEquals(1, r.failures.size());
        List<Frame> go = framesOfType(GOAWAY);
        assertEquals(1, go.size());
        assertEquals(H2FrameHandler.ERROR_NO_ERROR, go.get(0).intAt(4));
        assertTrue(endpoint.getCloseCount() > 0);
        handler.close();
        assertEquals("GOAWAY only once", 1, framesOfType(GOAWAY).size());
    }

    @Test
    public void closeWhenIdleWaitsForTheStreamsThenSendsGoaway() throws Exception {
        ready();
        Recorder r = sendGet("/");
        handler.closeWhenIdle();
        assertTrue(framesOfType(GOAWAY).isEmpty());
        respond200(1, "done");
        assertEquals(1, r.closeCalls);
        assertTrue(r.failures.isEmpty());
        assertEquals(1, framesOfType(GOAWAY).size());
    }

    @Test
    public void disconnectFailsOutstandingStreams() throws Exception {
        ready();
        Recorder r = sendGet("/");
        handler.disconnected();
        assertEquals(1, r.failures.size());
        assertEquals(1, conn.disconnected);
    }

    @Test
    public void idleTimeoutSendsGoaway() throws Exception {
        handler = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        handler.setH2WithPriorKnowledge(true);
        handler.setIdleTimeoutMs(1000);
        endpoint = new BinaryRecordingEndpoint();
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        ready();
        endpoint.fireTimers();
        List<Frame> go = framesOfType(GOAWAY);
        assertFalse(go.isEmpty());
    }

    // ── TLS ALPN ──

    @Test
    public void alpnH2StartsHttp2AndHttp11FallsBack() throws Exception {
        handler = new HttpClientProtocolHandler(conn, "example.com", 443, true);
        endpoint = new BinaryRecordingEndpoint();
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        handler.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256", "h2"));
        assertEquals(HttpVersion.HTTP_2_0, handler.getVersion());
        assertTrue(handler.isOpen());

        handler = new HttpClientProtocolHandler(conn, "example.com", 443, true);
        endpoint = new BinaryRecordingEndpoint();
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        handler.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256", "http/1.1"));
        assertEquals(HttpVersion.HTTP_1_1, handler.getVersion());
    }

    @Test
    public void h2OverWeakCipherSuiteIsRefused() throws Exception {
        handler = new HttpClientProtocolHandler(conn, "example.com", 443, true);
        endpoint = new BinaryRecordingEndpoint();
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        handler.securityEstablished(info("TLSv1.2", "TLS_RSA_WITH_AES_128_CBC_SHA", "h2"));
        assertFalse(handler.isOpen());
        assertTrue(endpoint.getCloseCount() > 0);
        assertEquals(1, conn.errors.size());
        assertTrue(conn.errors.get(0).getMessage(), conn.errors.get(0).getMessage().contains("CBC"));
    }

    private static SecurityInfo info(final String protocol, final String cipher, final String alpn) {
        return new SecurityInfo() {
            @Override
            public String getProtocol() {
                return protocol;
            }

            @Override
            public String getCipherSuite() {
                return cipher;
            }

            @Override
            public int getKeySize() {
                return 128;
            }

            @Override
            public java.security.cert.Certificate[] getPeerCertificates() {
                return null;
            }

            @Override
            public java.security.cert.Certificate[] getLocalCertificates() {
                return null;
            }

            @Override
            public String getApplicationProtocol() {
                return alpn;
            }

            @Override
            public long getHandshakeDurationMs() {
                return 0;
            }

            @Override
            public boolean isSessionResumed() {
                return false;
            }
        };
    }
}
