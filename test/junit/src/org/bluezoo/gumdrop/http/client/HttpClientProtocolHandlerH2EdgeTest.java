/*
 * HttpClientProtocolHandlerH2EdgeTest.java
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

import java.io.ByteArrayInputStream;
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
import java.util.zip.GZIPInputStream;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.http.h2.H2Writer;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.http.hpack.HeaderHandler;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge and error branches of the HTTP/2 side of {@link HttpClientProtocolHandler}
 * driven by synthetic server frames over an in-memory endpoint: requests with
 * no response handler, challenge retries, header blocks split across frames,
 * malformed and interim statuses, push promise variants, queued and cancelled
 * requests, flow-controlled bodies and request content coding.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientProtocolHandlerH2EdgeTest {

    private static final int DATA = 0;
    private static final int HEADERS = 1;
    private static final int RST_STREAM = 3;
    private static final int SETTINGS = 4;
    private static final int GOAWAY = 7;
    private static final int WINDOW_UPDATE = 8;
    private static final int CONTINUATION = 9;

    private interface FrameWriter {
        void write(H2Writer writer) throws IOException;
    }

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
        boolean endBody;
        int closeCalls;
        final List<Exception> failures = new ArrayList<Exception>();
        final List<String> headers = new ArrayList<String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean acceptPush;
        Recorder pushTarget;
        int promises;

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
            promises++;
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

    private void ready() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 100,
                H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 65535,
                H2FrameHandler.SETTINGS_MAX_FRAME_SIZE, 16384);
    }

    private byte[] blockBytes(String... nameValue) throws Exception {
        Headers h = new Headers();
        for (int i = 0; i < nameValue.length; i += 2) {
            h.add(new Header(nameValue[i], nameValue[i + 1]));
        }
        ByteBuffer buf = ByteBuffer.allocate(65536);
        serverEncoder.encode(buf, h);
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private void headers(final int streamId, final boolean endStream, final String... nameValue)
            throws Exception {
        final byte[] b = blockBytes(nameValue);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeHeaders(streamId, ByteBuffer.wrap(b), endStream, true);
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

    private void pushPromise(final int stream, final int promised, final boolean endHeaders,
            String... nameValue) throws Exception {
        final byte[] b = blockBytes(nameValue);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writePushPromise(stream, promised, ByteBuffer.wrap(b), endHeaders);
            }
        });
    }

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

    /** Decodes consecutive client header blocks with one HPACK context, as a server would. */
    private static List<Map<String, String>> decodeAll(List<Frame> headerFrames) throws Exception {
        final Decoder decoder = new Decoder(4096);
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        for (Frame f : headerFrames) {
            final Map<String, String> map = new LinkedHashMap<String, String>();
            decoder.decode(ByteBuffer.wrap(f.payload), new HeaderHandler() {
                @Override
                public void header(Header header) {
                    map.put(header.getName(), header.getValue());
                }
            });
            out.add(map);
        }
        return out;
    }

    private Recorder sendGet(String path) {
        Recorder r = new Recorder();
        handler.get(path).send(r);
        return r;
    }

    private static String junk(int length) {
        StringBuilder sb = new StringBuilder();
        int x = 12345;
        for (int i = 0; i < length; i++) {
            x = (x * 1103515245 + 12345) & 0x7fffffff;
            sb.append((char) ('a' + (x >> 16) % 26));
        }
        return sb.toString();
    }

    // ── responses without a handler ──

    @Test
    public void handlerlessStreamsConsumeResponsesAndResets() throws Exception {
        ready();
        handler.get("/a").send(null);
        headers(1, false, ":status", "200", "x-a", "1");
        data(1, new byte[10], false);
        headers(1, true, "x-trailer", "t");
        handler.get("/b").send(null);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeRstStream(3, H2FrameHandler.ERROR_CANCEL);
            }
        });
        handler.get("/c").send(null);
        headers(5, true, ":status", "204");
        handler.get("/d").send(null);
        headers(7, true, ":status", "abc");
        handler.get("/e").send(null);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeGoaway(7, H2FrameHandler.ERROR_NO_ERROR);
            }
        });
        assertFalse(handler.isOpen());
        assertEquals("no stream is left to fail loudly", 0, conn.errors.size());
    }

    @Test
    public void handlerlessPushPromiseIsRefusedAndHandlerlessCloseWorks() throws Exception {
        ready();
        handler.get("/a").send(null);
        pushPromise(1, 2, true, ":method", "GET", ":path", "/pushed");
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(H2FrameHandler.ERROR_REFUSED_STREAM, rst.get(0).intAt(0));
        handler.get("/b").send(null);
        handler.close();
        assertEquals(1, framesOfType(GOAWAY).size());
    }

    @Test
    public void headersAndDataForCompletedStreamsAreReset() throws Exception {
        ready();
        Recorder r = sendGet("/");
        headers(1, true, ":status", "200");
        assertEquals(1, r.closeCalls);
        data(1, new byte[3], false);
        headers(1, true, ":status", "200");
        assertEquals(2, framesOfType(RST_STREAM).size());
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void emptyDataFrameEndsTheStreamWithoutWindowUpdate() throws Exception {
        ready();
        Recorder r = sendGet("/");
        headers(1, false, ":status", "200");
        data(1, new byte[0], true);
        assertEquals(1, r.closeCalls);
        assertTrue(framesOfType(WINDOW_UPDATE).isEmpty());
    }

    // ── status handling ──

    @Test
    public void outOfRangeStatusesAreMalformedResponses() throws Exception {
        ready();
        Recorder low = sendGet("/a");
        headers(1, true, ":status", "99");
        assertEquals(1, low.failures.size());
        Recorder high = sendGet("/b");
        headers(3, true, ":status", "600");
        assertEquals(1, high.failures.size());
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(2, rst.size());
        assertEquals(H2FrameHandler.ERROR_PROTOCOL_ERROR, rst.get(0).intAt(0));
    }

    @Test
    public void interimResponseThatEndsTheStreamIsTreatedAsFinal() throws Exception {
        ready();
        Recorder r = sendGet("/");
        headers(1, true, ":status", "102");
        assertEquals(1, r.closeCalls);
        assertNull(r.status == null ? null : (r.status.isInformational() ? null : "x"));
    }

    @Test
    public void responseWithoutStatusHeaderJustEndsTheStream() throws Exception {
        ready();
        Recorder r = sendGet("/");
        headers(1, true, "x-only", "trailer-like");
        assertEquals(0, r.okCalls + r.errorCalls);
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void gzipResponseOverHttp2IsDecodedWithoutAHandlerToo() throws Exception {
        ready();
        handler.get("/h").send(null);
        headers(1, false, ":status", "200", "content-encoding", "gzip");
        data(1, new byte[] {1, 2, 3}, true);
        Recorder after = sendGet("/next");
        headers(3, true, ":status", "200");
        assertEquals(1, after.okCalls);
    }

    @Test
    public void headersContinuedAcrossFramesGrowTheAssemblyBuffer() throws Exception {
        ready();
        Recorder r = sendGet("/");
        String big = junk(7000);
        byte[] all = blockBytes(":status", "200", "x-big", big);
        final int cut = 3000;
        final byte[] first = new byte[cut];
        final byte[] second = new byte[all.length - cut];
        System.arraycopy(all, 0, first, 0, cut);
        System.arraycopy(all, cut, second, 0, second.length);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeHeaders(1, ByteBuffer.wrap(first), true, false);
                w.writeContinuation(1, ByteBuffer.wrap(second), true);
            }
        });
        assertEquals(1, r.okCalls);
        assertTrue(r.headers.contains("x-big: " + big));
    }

    @Test
    public void continuationForAnUnknownStreamIsIgnored() throws Exception {
        ready();
        final byte[] b = blockBytes(":status", "200");
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeHeaders(9, ByteBuffer.wrap(b, 0, 1), false, false);
                w.writeContinuation(9, ByteBuffer.wrap(b, 1, b.length - 1), true);
            }
        });
        List<Frame> rst = framesOfType(RST_STREAM);
        assertEquals(1, rst.size());
        assertEquals(9, rst.get(0).streamId);
        assertTrue(handler.isOpen());
    }

    @Test
    public void endStreamOnAHeaderBlockSplitIntoContinuationIsHonoured() throws Exception {
        ready();
        Recorder r = sendGet("/");
        final byte[] all = blockBytes(":status", "200", "x-split", "abcdefghij");
        final int half = all.length / 2;
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeHeaders(1, ByteBuffer.wrap(all, 0, half), true, false);
                w.writeContinuation(1, ByteBuffer.wrap(all, half, all.length - half), true);
            }
        });
        assertEquals(1, r.okCalls);
        assertEquals("the stream ended with the header block", 1, r.closeCalls);
        assertTrue(r.endBody);
    }

    // ── authentication over HTTP/2 ──

    @Test
    public void challengeIsAnsweredOnceAndSecondChallengeIsDelivered() throws Exception {
        handler.credentials("user", "pass");
        ready();
        Recorder r = sendGet("/secret");
        headers(1, true, ":status", "401", "www-authenticate", "Basic realm=\"r\"");
        assertEquals(0, r.errorCalls);
        List<Frame> hs = framesOfType(HEADERS);
        assertEquals(2, hs.size());
        Map<String, String> retry = decodeAll(hs).get(1);
        assertEquals("Basic dXNlcjpwYXNz", retry.get("authorization"));
        headers(3, true, ":status", "401", "www-authenticate", "Basic realm=\"r\"");
        assertEquals(1, r.errorCalls);
        assertEquals(HttpStatus.UNAUTHORIZED, r.status);
        assertEquals(2, framesOfType(HEADERS).size());
    }

    @Test
    public void proxyChallengeIsAnsweredWithProxyAuthorization() throws Exception {
        handler.credentials("user", "pass");
        ready();
        Recorder r = sendGet("/via-proxy");
        headers(1, false, ":status", "407", "proxy-authenticate", "Basic realm=\"p\"");
        data(1, new byte[5], true);
        assertEquals(0, r.errorCalls);
        List<Frame> hs = framesOfType(HEADERS);
        assertEquals(2, hs.size());
        Map<String, String> retry = decodeAll(hs).get(1);
        assertEquals("Basic dXNlcjpwYXNz", retry.get("proxy-authorization"));
        headers(3, true, ":status", "200");
        assertEquals(1, r.okCalls);
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void unanswerableChallengesAreDeliveredOverHttp2() throws Exception {
        handler.credentials("user", "pass");
        ready();
        Recorder noChallenge = sendGet("/a");
        headers(1, true, ":status", "401", "x-other", "1");
        assertEquals(1, noChallenge.errorCalls);
        Recorder noProxyChallenge = sendGet("/b");
        headers(3, true, ":status", "407", "x-other", "1");
        assertEquals(1, noProxyChallenge.errorCalls);
        Recorder bearer = sendGet("/c");
        headers(5, true, ":status", "401", "www-authenticate", "Bearer realm=\"x\"");
        assertEquals(1, bearer.errorCalls);
        Recorder other = sendGet("/d");
        headers(7, true, ":status", "403");
        assertEquals(1, other.errorCalls);
        assertEquals(4, framesOfType(HEADERS).size());
    }

    @Test
    public void challengedRequestsWithBodiesAreNotRetried() throws Exception {
        handler.credentials("user", "pass");
        ready();
        Recorder r = new Recorder();
        HttpRequest post = handler.post("/up");
        post.header("Content-Length", "3");
        post.startRequestBody(r);
        post.requestBodyContent(ByteBuffer.wrap(new byte[3]));
        post.endRequestBody();
        headers(1, true, ":status", "401", "www-authenticate", "Basic realm=\"r\"");
        assertEquals(1, r.errorCalls);
        Recorder chunked = new Recorder();
        HttpRequest put = handler.put("/up2");
        put.header("Transfer-Encoding", "chunked");
        put.startRequestBody(chunked);
        put.endRequestBody();
        headers(3, true, ":status", "401", "www-authenticate", "Basic realm=\"r\"");
        assertEquals(1, chunked.errorCalls);
    }

    // ── push promise variants ──

    @Test
    public void pushPromiseWithContinuationAndMissingPseudoHeadersIsAccepted() throws Exception {
        ready();
        Recorder r = sendGet("/");
        r.acceptPush = true;
        r.pushTarget = new Recorder();
        final byte[] all = blockBytes("x-promised", "yes");
        final int half = all.length / 2;
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writePushPromise(1, 4, ByteBuffer.wrap(all, 0, half), false);
                w.writeContinuation(1, ByteBuffer.wrap(all, half, all.length - half), true);
            }
        });
        assertEquals(1, r.promises);
        headers(4, true, ":status", "200");
        assertEquals(1, r.pushTarget.okCalls);
        List<Frame> hs = framesOfType(HEADERS);
        assertEquals("the promised request itself is not re-sent", 1, hs.size());
        headers(1, true, ":status", "200");
        handler.closeWhenIdle();
        assertEquals("no phantom stream keeps the connection busy", 1, framesOfType(GOAWAY).size());
    }

    @Test
    public void lowerPromisedStreamIdDoesNotLowerTheGoawayWatermark() throws Exception {
        ready();
        Recorder r = sendGet("/");
        pushPromise(1, 6, true, ":method", "GET", ":path", "/a");
        pushPromise(1, 4, true, ":method", "GET", ":path", "/b");
        assertEquals(2, r.promises);
        handler.close();
        List<Frame> go = framesOfType(GOAWAY);
        assertEquals(1, go.size());
        assertEquals(6, go.get(0).intAt(0));
    }

    @Test
    public void pushIsEnabledWhenTheSettingSaysSo() throws Exception {
        settings(H2FrameHandler.SETTINGS_ENABLE_PUSH, 1);
        Recorder r = sendGet("/");
        pushPromise(1, 2, true, ":method", "GET", ":path", "/a");
        assertEquals(1, r.promises);
    }

    @Test
    public void pushPromiseHeaderBlockGarbageIsACompressionError() throws Exception {
        ready();
        sendGet("/");
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writePushPromise(1, 2, ByteBuffer.wrap(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff,
                    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}), true);
            }
        });
        List<Frame> go = framesOfType(GOAWAY);
        assertEquals(1, go.size());
        assertEquals(H2FrameHandler.ERROR_COMPRESSION_ERROR, go.get(0).intAt(4));
    }

    // ── requests: headers, trace, bodies ──

    @Test
    public void traceparentIsAddedUnlessTheCallerSuppliedOne() throws Exception {
        handler.setTraceContext(new Trace("client"));
        ready();
        sendGet("/a");
        HttpRequest own = handler.get("/b");
        own.header("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        own.send(new Recorder());
        List<Frame> hs = framesOfType(HEADERS);
        assertEquals(2, hs.size());
        List<Map<String, String>> decoded = decodeAll(hs);
        Map<String, String> first = decoded.get(0);
        assertTrue(first.toString(), first.get("traceparent").startsWith("00-"));
        Map<String, String> second = decoded.get(1);
        assertEquals("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01", second.get("traceparent"));
    }

    @Test
    public void hugeHeaderBlocksAreSplitIntoContinuationFrames() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE, 1000000);
        HttpRequest r = handler.get("/big");
        r.header("x-a", junk(20000));
        r.header("x-b", junk(20000));
        r.header("x-c", junk(20000));
        r.send(new Recorder());
        List<Frame> hs = framesOfType(HEADERS);
        List<Frame> cs = framesOfType(CONTINUATION);
        assertEquals(1, hs.size());
        assertTrue("several continuation frames: " + cs.size(), cs.size() >= 2);
        assertEquals("only the last continuation ends the header block", 4, cs.get(cs.size() - 1).flags & 4);
        assertEquals(0, cs.get(0).flags & 4);
    }

    @Test
    public void headerListBeyondThePeerLimitFailsTheRequest() throws Exception {
        ready();
        HttpRequest r = handler.get("/big");
        r.header("x-a", junk(9000));
        Recorder rec = new Recorder();
        r.send(rec);
        assertEquals(1, rec.failures.size());
        assertTrue(framesOfType(HEADERS).isEmpty());
        HttpRequest silent = handler.get("/big2");
        silent.header("x-a", junk(9000));
        silent.send(null);
        assertTrue(framesOfType(HEADERS).isEmpty());
        handler.closeWhenIdle();
        assertEquals(1, framesOfType(GOAWAY).size());
    }

    @Test
    public void bodyChunksAndEndForAnUnknownStreamAreDropped() throws Exception {
        ready();
        HttpStream stream = new HttpStream(handler, "POST", "/ghost");
        int written = handler.sendRequestBody(stream, ByteBuffer.wrap(new byte[4]));
        assertEquals(0, written);
        handler.endRequestBody(stream);
        assertTrue(framesOfType(DATA).isEmpty());
    }

    @Test
    public void gzipRequestBodyOverHttp2IsEncoded() throws Exception {
        handler.setEncodeRequestBodyContentCoding(true);
        ready();
        HttpRequest r = handler.post("/up");
        r.header("Content-Encoding", "gzip");
        r.startRequestBody(new Recorder());
        byte[] plain = "squash squash squash squash".getBytes(StandardCharsets.US_ASCII);
        assertEquals(plain.length, r.requestBodyContent(ByteBuffer.wrap(plain)));
        r.endRequestBody();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        List<Frame> data = framesOfType(DATA);
        for (Frame f : data) {
            all.write(f.payload, 0, f.payload.length);
        }
        assertTrue(data.get(data.size() - 1).endStream());
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(all.toByteArray()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[128];
        int n;
        while ((n = gz.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        assertEquals("squash squash squash squash", new String(out.toByteArray(), StandardCharsets.US_ASCII));
    }

    @Test
    public void streamCompletionDropsQueuedFlowControlledData() throws Exception {
        settings(H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 4);
        Recorder r = new Recorder();
        HttpRequest post = handler.post("/up");
        post.startRequestBody(r);
        post.requestBodyContent(ByteBuffer.wrap(new byte[20]));
        assertEquals(1, framesOfType(DATA).size());
        headers(1, true, ":status", "413");
        assertEquals(1, r.errorCalls);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(0, 1000);
            }
        });
        assertEquals("nothing more is sent on the finished stream", 1, framesOfType(DATA).size());
    }

    @Test
    public void endMarkerQueuesBehindPendingDataAndDrainsInFrames() throws Exception {
        settings(H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 6,
                H2FrameHandler.SETTINGS_MAX_FRAME_SIZE, 16384);
        HttpRequest post = handler.post("/up");
        post.startRequestBody(new Recorder());
        post.requestBodyContent(ByteBuffer.wrap(new byte[10]));
        post.requestBodyContent(ByteBuffer.wrap(new byte[10]));
        post.endRequestBody();
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(0, 100);
                w.writeWindowUpdate(1, 4);
            }
        });
        List<Frame> data = framesOfType(DATA);
        int total = 0;
        for (Frame f : data) {
            total += f.payload.length;
        }
        assertEquals(10, total);
        assertFalse(data.get(data.size() - 1).endStream());
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
        assertEquals(20, total);
        assertTrue(data.get(data.size() - 1).endStream());
    }

    @Test
    public void windowUpdateForAnUnknownStreamIsHarmless() throws Exception {
        ready();
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeWindowUpdate(9, 100);
            }
        });
        assertTrue(handler.isOpen());
    }

    // ── queueing, cancellation, shutdown ──

    @Test
    public void closeWhenIdleWaitsForEveryActiveStream() throws Exception {
        ready();
        Recorder a = sendGet("/a");
        Recorder b = sendGet("/b");
        handler.closeWhenIdle();
        headers(1, true, ":status", "200");
        assertEquals(1, a.closeCalls);
        assertTrue("the second stream is still running", framesOfType(GOAWAY).isEmpty());
        headers(3, true, ":status", "200");
        assertEquals(1, b.closeCalls);
        assertEquals(1, framesOfType(GOAWAY).size());
    }

    @Test
    public void closeWhenIdleWaitsForQueuedRequestsToo() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 1);
        Recorder a = sendGet("/a");
        Recorder b = sendGet("/b");
        handler.closeWhenIdle();
        headers(1, true, ":status", "200");
        assertEquals(1, a.closeCalls);
        assertTrue(framesOfType(GOAWAY).isEmpty());
        headers(3, true, ":status", "200");
        assertEquals(1, b.closeCalls);
        assertEquals(1, framesOfType(GOAWAY).size());
    }

    @Test
    public void disconnectFailsQueuedRequestsIncludingHandlerlessOnes() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 1);
        Recorder a = sendGet("/a");
        Recorder queued = sendGet("/b");
        handler.get("/c").send(null);
        handler.disconnected();
        assertEquals(1, a.failures.size());
        assertEquals(1, queued.failures.size());
        assertEquals(1, conn.disconnected);
    }

    @Test
    public void errorFailsStreamsAndTellsTheConnectionHandler() throws Exception {
        ready();
        Recorder r = sendGet("/a");
        handler.error(new IOException("reset by peer"));
        assertEquals(1, r.failures.size());
        assertEquals(1, conn.errors.size());
    }

    @Test
    public void idleTimeoutOnAnH2ConnectionWithoutEndpointTimersIsRescheduledOnTraffic() throws Exception {
        handler = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        handler.setH2WithPriorKnowledge(true);
        handler.setIdleTimeoutMs(1000);
        endpoint = new BinaryRecordingEndpoint();
        endpoint.setSelectorLoop(new InlineSelectorLoop());
        handler.connected(endpoint);
        ready();
        List<BinaryRecordingEndpoint.StubTimer> timers = endpoint.getTimers();
        assertTrue(timers.size() >= 2);
        assertTrue("earlier timers are cancelled", timers.get(0).isCancelled());
        assertFalse(timers.get(timers.size() - 1).isCancelled());
    }

    @Test
    public void settingsWithSmallFrameSizeSplitsLargeDataFrames() throws Exception {
        settings(H2FrameHandler.SETTINGS_MAX_FRAME_SIZE, 16384,
                H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 1000000,
                H2FrameHandler.SETTINGS_HEADER_TABLE_SIZE, 2048,
                H2FrameHandler.SETTINGS_MAX_HEADER_LIST_SIZE, 100000);
        HttpRequest r = handler.post("/up");
        r.startRequestBody(new Recorder());
        r.requestBodyContent(ByteBuffer.wrap(new byte[50000]));
        List<Frame> data = framesOfType(DATA);
        assertTrue(data.size() >= 4);
    }

    @Test
    public void unknownSettingsAreIgnoredAndStillAcknowledged() throws Exception {
        settings(0x77, 5);
        List<Frame> sf = framesOfType(SETTINGS);
        assertEquals(2, sf.size());
        assertEquals(1, sf.get(1).flags & 1);
    }

    @Test
    public void streamResetWithoutHandlerIsConsumed() throws Exception {
        ready();
        handler.get("/a").send(null);
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeRstStream(1, H2FrameHandler.ERROR_INTERNAL_ERROR);
            }
        });
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writeRstStream(11, H2FrameHandler.ERROR_INTERNAL_ERROR);
            }
        });
        assertTrue(handler.isOpen());
    }

    @Test
    public void connectionPingAckIsNotAnswered() throws Exception {
        ready();
        int before = clientFrames().size();
        server(new FrameWriter() {
            @Override
            public void write(H2Writer w) throws IOException {
                w.writePing(42L, true);
            }
        });
        assertEquals(before, clientFrames().size());
        assertNotNull(handler);
    }
}
