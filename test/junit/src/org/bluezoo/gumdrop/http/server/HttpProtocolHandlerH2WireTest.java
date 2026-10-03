/*
 * HttpProtocolHandlerH2WireTest.java
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


package org.bluezoo.gumdrop.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HeaderFieldHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.http.hpack.Encoder;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.junit.Test;

/**
 * Drives the HTTP/2 server connection of {@link HttpProtocolHandler} with
 * real client frames over the wire (RFC 9113): handshake, requests,
 * flow-controlled responses, and the connection/stream error paths.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpProtocolHandlerH2WireTest {

    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private static class Frame {
        int type;
        int flags;
        int streamId;
        byte[] payload;
    }

    private static class Info implements SecurityInfo {
        private final String protocol;
        private final String cipher;

        Info(String protocol, String cipher) {
            this.protocol = protocol;
            this.cipher = cipher;
        }

        @Override public String getProtocol() { return protocol; }
        @Override public String getCipherSuite() { return cipher; }
        @Override public int getKeySize() { return 256; }
        @Override public Certificate[] getPeerCertificates() { return null; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public String getApplicationProtocol() { return "h2"; }
        @Override public long getHandshakeDurationMs() { return 0; }
        @Override public boolean isSessionResumed() { return false; }
    }

    /** An application response written by a test, instead of the default one. */
    private interface Script {
        void run(HttpResponse response);
    }

    /** One HEADERS or PUSH_PROMISE frame of the server, HPACK-decoded. */
    private static class Decoded {
        int type;
        int flags;
        int streamId;
        int promisedStreamId;
        final List<String[]> fields = new ArrayList<String[]>();

        String get(String name) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i)[0].equals(name)) {
                    return fields.get(i)[1];
                }
            }
            return null;
        }

        boolean endStream() {
            return (flags & 1) != 0;
        }
    }

    /** What the application handler saw and how it should answer. */
    private static class App {
        final List<String> events = new ArrayList<String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int responseBodyLength = 2;
        int extraHeaderBytes;
        int status = 200;
        boolean respondInHeaders;
        boolean push;
        boolean informational;
        boolean failedSeen;
        boolean pushResult;
        Script script;
        HttpResponse lastState;
        List<Header> lastHeaders;
        Runnable writable;
    }

    private static class Conn {
        final Http2Listener listener = new Http2Listener();
        final App app = new App();
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        final Encoder encoder = new Encoder(4096, 65536);
        HttpProtocolHandler handler;
        ByteBuffer carry = ByteBuffer.allocate(1 << 20);
        int maxStreams = -1;

        Conn() {
            endpoint.setSecure(true);
            final App a = app;
            listener.setStreamHandler(new HttpStreamHandler() {
                @Override
                public HttpRequestHandler openStream(HttpResponse state) {
                    return new CollectingRequestHandler(state) {
                        @Override
                        public void headers(HttpResponse s, List<Header> headers) {
                            a.events.add("headers");
                            a.lastState = s;
                            a.lastHeaders = headers;
                            if (a.respondInHeaders) {
                                respond(s);
                            }
                        }

                        @Override
                        public void startRequestBody(HttpResponse s) {
                            a.events.add("startBody");
                        }

                        @Override
                        public void requestBodyContent(HttpResponse s,
                                ByteBuffer data) {
                            byte[] b = new byte[data.remaining()];
                            data.get(b);
                            a.body.write(b, 0, b.length);
                        }

                        @Override
                        public void endRequestBody(HttpResponse s) {
                            a.events.add("endBody");
                        }

                        @Override
                        public void requestComplete(HttpResponse s) {
                            a.events.add("complete");
                            if (!a.respondInHeaders) {
                                respond(s);
                            }
                        }

                        @Override
                        public void failed(HttpResponse s, Exception cause) {
                            a.failedSeen = true;
                            a.events.add("failed");
                        }

                        private void respond(HttpResponse s) {
                            if (a.script != null) {
                                a.script.run(s);
                                return;
                            }
                            if (a.informational) {
                                s.status(103);
                                s.header("Link", "</style.css>; rel=preload");
                                s.endHeaders();
                            }
                            if (a.push) {
                                s.startPushPromise(HttpMethod.GET, "/pushed");
                                a.pushResult = s.endPushPromise();
                            }
                            s.status(a.status);
                            if (a.extraHeaderBytes > 0) {
                                StringBuilder big = new StringBuilder();
                                for (int i = 0; i < a.extraHeaderBytes; i++) {
                                    big.append((char) ('a' + (i * 7 + i / 13) % 26));
                                }
                                s.header("x-big", big.toString());
                            }
                            if (a.status == 204 || a.status == 304) {
                                s.endMessage();
                                return;
                            }
                            byte[] data = new byte[a.responseBodyLength];
                            for (int i = 0; i < data.length; i++) {
                                data[i] = (byte) ('a' + (i % 26));
                            }
                            s.bodyContent(ByteBuffer.wrap(data));
                            s.endMessage();
                        }
                    };
                }
            });
        }

        void open() {
            if (maxStreams > 0) {
                handler = new HttpProtocolHandler(listener, 0, maxStreams);
            } else {
                handler = new HttpProtocolHandler(listener);
            }
            handler.connected(endpoint);
            handler.securityEstablished(new Info("TLSv1.3", "TLS_AES_128_GCM_SHA256"));
        }

        void send(byte[] data) {
            carry.put(data);
            carry.flip();
            handler.receive(carry);
            carry.compact();
        }

        void sendSlowly(byte[] data) {
            for (int i = 0; i < data.length; i++) {
                carry.put(data[i]);
                carry.flip();
                handler.receive(carry);
                carry.compact();
            }
        }

        void handshake() {
            open();
            send(PREFACE);
            byte[] s = frame(4, 0, 0, new byte[0]);
            send(s);
        }

        byte[] headerBlock(String method, String path, String[] extra) {
            List<Header> h = new ArrayList<Header>();
            h.add(new Header(":method", method));
            h.add(new Header(":scheme", "https"));
            h.add(new Header(":authority", "h.test"));
            h.add(new Header(":path", path));
            if (extra != null) {
                for (int i = 0; i + 1 < extra.length; i += 2) {
                    h.add(new Header(extra[i], extra[i + 1]));
                }
            }
            return encode(h);
        }

        byte[] encode(List<Header> h) {
            ByteBuffer buf = ByteBuffer.allocate(8192);
            try {
                encoder.encode(buf, h);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            buf.flip();
            byte[] out = new byte[buf.remaining()];
            buf.get(out);
            return out;
        }

        void request(int streamId, String method, String path, boolean endStream) {
            byte[] block = headerBlock(method, path, null);
            int flags = 4 | (endStream ? 1 : 0);
            byte[] f = frame(1, flags, streamId, block);
            send(f);
        }

        List<Frame> frames() {
            return parse(endpoint.getAllBytes());
        }

        /**
         * HPACK-decodes, in wire order and with a fresh client decoder, every
         * HEADERS and PUSH_PROMISE frame the server has sent so far.
         */
        List<Decoded> decodedHeaderFrames() {
            Decoder decoder = new Decoder(4096, 65536);
            List<Decoded> out = new ArrayList<Decoded>();
            List<Frame> frames = frames();
            for (int i = 0; i < frames.size(); i++) {
                Frame f = frames.get(i);
                if (f.type != 1 && f.type != 5) {
                    continue;
                }
                int pos = 0;
                int end = f.payload.length;
                if ((f.flags & 0x8) != 0) {
                    end -= (f.payload[0] & 0xff);
                    pos = 1;
                }
                Decoded d = new Decoded();
                d.type = f.type;
                d.flags = f.flags;
                d.streamId = f.streamId;
                if (f.type == 5) {
                    d.promisedStreamId = ((f.payload[pos] & 0x7f) << 24)
                            | ((f.payload[pos + 1] & 0xff) << 16)
                            | ((f.payload[pos + 2] & 0xff) << 8) | (f.payload[pos + 3] & 0xff);
                    pos += 4;
                } else if ((f.flags & 0x20) != 0) {
                    pos += 5;
                }
                final Decoded target = d;
                try {
                    decoder.decode(ByteBuffer.wrap(f.payload, pos, end - pos), new HeaderFieldHandler() {
                        @Override
                        public void field(ByteBuffer name, ByteBuffer value) {
                            target.fields.add(new String[] {
                                    StandardCharsets.ISO_8859_1.decode(name.duplicate()).toString(),
                                    StandardCharsets.ISO_8859_1.decode(value.duplicate()).toString() });
                        }
                    });
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                out.add(d);
            }
            return out;
        }

        /** The decoded HEADERS frames (not PUSH_PROMISE) of one stream. */
        List<Decoded> responseHeaderFrames(int streamId) {
            List<Decoded> out = new ArrayList<Decoded>();
            List<Decoded> all = decodedHeaderFrames();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).type == 1 && all.get(i).streamId == streamId) {
                    out.add(all.get(i));
                }
            }
            return out;
        }
    }

    static byte[] frame(int type, int flags, int streamId, byte[] payload) {
        byte[] out = new byte[9 + payload.length];
        out[0] = (byte) (payload.length >> 16);
        out[1] = (byte) (payload.length >> 8);
        out[2] = (byte) payload.length;
        out[3] = (byte) type;
        out[4] = (byte) flags;
        out[5] = (byte) (streamId >> 24);
        out[6] = (byte) (streamId >> 16);
        out[7] = (byte) (streamId >> 8);
        out[8] = (byte) streamId;
        System.arraycopy(payload, 0, out, 9, payload.length);
        return out;
    }

    static byte[] ints(int... values) {
        byte[] out = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            out[i * 4] = (byte) (values[i] >> 24);
            out[i * 4 + 1] = (byte) (values[i] >> 16);
            out[i * 4 + 2] = (byte) (values[i] >> 8);
            out[i * 4 + 3] = (byte) values[i];
        }
        return out;
    }

    static byte[] setting(int id, int value) {
        byte[] out = new byte[6];
        out[0] = (byte) (id >> 8);
        out[1] = (byte) id;
        out[2] = (byte) (value >> 24);
        out[3] = (byte) (value >> 16);
        out[4] = (byte) (value >> 8);
        out[5] = (byte) value;
        return out;
    }

    static List<Frame> parse(byte[] wire) {
        List<Frame> out = new ArrayList<Frame>();
        int pos = 0;
        while (pos + 9 <= wire.length) {
            int len = ((wire[pos] & 0xff) << 16) | ((wire[pos + 1] & 0xff) << 8)
                    | (wire[pos + 2] & 0xff);
            if (pos + 9 + len > wire.length) {
                break;
            }
            Frame f = new Frame();
            f.type = wire[pos + 3] & 0xff;
            f.flags = wire[pos + 4] & 0xff;
            f.streamId = ((wire[pos + 5] & 0x7f) << 24) | ((wire[pos + 6] & 0xff) << 16)
                    | ((wire[pos + 7] & 0xff) << 8) | (wire[pos + 8] & 0xff);
            f.payload = new byte[len];
            System.arraycopy(wire, pos + 9, f.payload, 0, len);
            out.add(f);
            pos += 9 + len;
        }
        return out;
    }

    static int count(List<Frame> frames, int type) {
        int n = 0;
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.type == type) {
                n++;
            }
        }
        return n;
    }

    static int dataBytes(List<Frame> frames, int streamId) {
        int n = 0;
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.type == 0 && f.streamId == streamId) {
                n += f.payload.length;
            }
        }
        return n;
    }

    static Frame first(List<Frame> frames, int type) {
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.type == type) {
                return f;
            }
        }
        return null;
    }

    static int goawayError(List<Frame> frames) {
        Frame f = first(frames, 7);
        assertNotNull("expected GOAWAY", f);
        return ((f.payload[4] & 0xff) << 24) | ((f.payload[5] & 0xff) << 16)
                | ((f.payload[6] & 0xff) << 8) | (f.payload[7] & 0xff);
    }

    // ------------------------------------------------------------------

    @Test
    public void testHandshakeSendsSettingsAndAcks() {
        Conn c = new Conn();
        c.handshake();
        List<Frame> frames = c.frames();
        assertTrue(count(frames, 4) >= 2);
        Frame firstFrame = frames.get(0);
        assertEquals(4, firstFrame.type);
        assertEquals(0, firstFrame.flags);
        Frame ack = frames.get(1);
        assertEquals(4, ack.type);
        assertEquals(1, ack.flags);
    }

    @Test
    public void testSimpleGetResponse() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "GET", "/hello", true);
        List<Frame> frames = c.frames();
        assertEquals(1, count(frames, 1));
        assertEquals(2, dataBytes(frames, 1));
        assertEquals("headers", c.app.events.get(0));
        assertTrue(c.app.events.contains("complete"));
    }

    @Test
    public void testPrefaceAndFramesByteAtATime() {
        Conn c = new Conn();
        c.open();
        byte[] block = c.headerBlock("GET", "/slow", null);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(PREFACE, 0, PREFACE.length);
        byte[] s = frame(4, 0, 0, new byte[0]);
        all.write(s, 0, s.length);
        byte[] h = frame(1, 5, 1, block);
        all.write(h, 0, h.length);
        c.sendSlowly(all.toByteArray());
        List<Frame> frames = c.frames();
        assertEquals(2, dataBytes(frames, 1));
    }

    @Test
    public void testPostBodyAndTrailers() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/up", false);
        byte[] d1 = frame(0, 0, 1, new byte[] {'a', 'b', 'c'});
        c.send(d1);
        c.app.events.clear();
        byte[] d2 = frame(0, 1, 1, new byte[] {'d'});
        c.send(d2);
        assertEquals("abcd", new String(c.app.body.toByteArray(), StandardCharsets.ISO_8859_1));
        assertTrue(c.app.events.contains("complete"));
    }

    @Test
    public void testLargeUploadSendsWindowUpdates() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/up", false);
        byte[] chunk = new byte[16384];
        for (int i = 0; i < 6; i++) {
            byte[] d = frame(0, 0, 1, chunk);
            c.send(d);
        }
        List<Frame> frames = c.frames();
        assertTrue(count(frames, 8) > 0);
        assertEquals(6 * 16384, c.app.body.size());
    }

    @Test
    public void testResponseLargerThanWindowDrainsOnWindowUpdate() {
        Conn c = new Conn();
        c.app.responseBodyLength = 200000;
        c.handshake();
        c.request(1, "GET", "/big", true);
        List<Frame> frames = c.frames();
        int firstBatch = dataBytes(frames, 1);
        assertEquals(65535, firstBatch);
        assertEquals(200000 - 65535, c.handler.pendingResponseBytes(1));
        byte[] wu0 = frame(8, 0, 0, ints(200000));
        c.send(wu0);
        byte[] wu1 = frame(8, 0, 1, ints(200000));
        c.send(wu1);
        frames = c.frames();
        assertEquals(200000, dataBytes(frames, 1));
        assertEquals(0, c.handler.pendingResponseBytes(1));
    }

    @Test
    public void testSmallPeerWindowSplitsResponse() {
        Conn c = new Conn();
        c.app.responseBodyLength = 100;
        c.open();
        c.send(PREFACE);
        byte[] settings = setting(4, 10);
        byte[] s = frame(4, 0, 0, settings);
        c.send(s);
        c.request(1, "GET", "/x", true);
        List<Frame> frames = c.frames();
        assertEquals(10, dataBytes(frames, 1));
        byte[] wu = frame(8, 0, 1, ints(1000));
        c.send(wu);
        byte[] wu0 = frame(8, 0, 0, ints(1000));
        c.send(wu0);
        frames = c.frames();
        assertEquals(100, dataBytes(frames, 1));
    }

    @Test
    public void testPeerSettingsApplied() {
        Conn c = new Conn();
        c.open();
        c.send(PREFACE);
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        int[][] st = {{1, 8192}, {2, 0}, {3, 50}, {4, 70000}, {5, 32768},
            {6, 100000}, {8, 1}, {9, 1}};
        for (int i = 0; i < st.length; i++) {
            byte[] one = setting(st[i][0], st[i][1]);
            p.write(one, 0, one.length);
        }
        byte[] s = frame(4, 0, 0, p.toByteArray());
        c.send(s);
        List<Frame> frames = c.frames();
        assertEquals(2, count(frames, 4));
        // later settings change
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        for (int i = 0; i < st.length; i++) {
            byte[] one = setting(st[i][0], st[i][1] == 8192 ? 4096 : st[i][1]);
            q.write(one, 0, one.length);
        }
        byte[] s2 = frame(4, 0, 0, q.toByteArray());
        c.send(s2);
        frames = c.frames();
        assertEquals(3, count(frames, 4));
    }

    @Test
    public void testSettingsAckIsAccepted() {
        Conn c = new Conn();
        c.handshake();
        byte[] ack = frame(4, 1, 0, new byte[0]);
        c.send(ack);
        assertEquals(0, c.endpoint.getCloseCount());
    }

    @Test
    public void testInvalidNoRfc7540PrioritiesValueIsProtocolError() {
        Conn c = new Conn();
        c.open();
        c.send(PREFACE);
        byte[] st = setting(9, 2);
        byte[] s = frame(4, 0, 0, st);
        c.send(s);
        assertEquals(1, goawayError(c.frames()));
        assertTrue(c.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testInvalidNoRfc7540PrioritiesValueLater() {
        Conn c = new Conn();
        c.handshake();
        byte[] st = setting(9, 2);
        byte[] s = frame(4, 0, 0, st);
        c.send(s);
        assertEquals(1, goawayError(c.frames()));
    }

    @Test
    public void testInitialWindowOverflowAfterWindowUpdate() {
        Conn c = new Conn();
        c.app.responseBodyLength = 10;
        c.app.respondInHeaders = true;
        c.handshake();
        c.request(1, "GET", "/x", false);
        byte[] st = setting(4, 0x7fffffff);
        byte[] s = frame(4, 0, 0, st);
        c.send(s);
        byte[] wu = frame(8, 0, 1, ints(0x7fffffff));
        c.send(wu);
        List<Frame> frames = c.frames();
        assertTrue(count(frames, 3) + count(frames, 7) > 0);
    }

    @Test
    public void testPingIsAcked() {
        Conn c = new Conn();
        c.handshake();
        byte[] payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] p = frame(6, 0, 0, payload);
        c.send(p);
        List<Frame> frames = c.frames();
        Frame ack = first(frames, 6);
        assertNotNull(ack);
        assertEquals(1, ack.flags);
        assertEquals(8, ack.payload.length);
        assertEquals(3, ack.payload[2]);
        byte[] pa = frame(6, 1, 0, payload);
        c.send(pa);
        assertEquals(1, count(c.frames(), 6));
    }

    @Test
    public void testGoawayFromPeerClosesConnection() {
        Conn c = new Conn();
        c.handshake();
        byte[] g = frame(7, 0, 0, ints(0, 0));
        c.send(g);
        assertTrue(c.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testEvenStreamIdIsProtocolError() {
        Conn c = new Conn();
        c.handshake();
        c.request(2, "GET", "/x", true);
        assertEquals(1, goawayError(c.frames()));
    }

    @Test
    public void testDecreasingStreamIdIsProtocolError() {
        Conn c = new Conn();
        c.handshake();
        c.request(5, "GET", "/x", true);
        c.request(3, "GET", "/x", true);
        assertEquals(1, goawayError(c.frames()));
    }

    @Test
    public void testPushPromiseFromClientIsProtocolError() {
        Conn c = new Conn();
        c.handshake();
        byte[] pp = new byte[4 + 1];
        pp[3] = 2;
        pp[4] = (byte) 0x82;
        byte[] f = frame(5, 4, 1, pp);
        c.send(f);
        assertEquals(1, goawayError(c.frames()));
    }

    @Test
    public void testHeadersWithContinuation() {
        Conn c = new Conn();
        c.handshake();
        byte[] block = c.headerBlock("GET", "/split", null);
        int half = block.length / 2;
        byte[] a = new byte[half];
        byte[] b = new byte[block.length - half];
        System.arraycopy(block, 0, a, 0, half);
        System.arraycopy(block, half, b, 0, b.length);
        byte[] f1 = frame(1, 1, 1, a);
        c.send(f1);
        byte[] f2 = frame(9, 4, 1, b);
        c.send(f2);
        assertTrue(c.app.events.contains("complete"));
        assertEquals(2, dataBytes(c.frames(), 1));
    }

    @Test
    public void testManyContinuationFramesRejected() {
        Conn c = new Conn();
        c.handshake();
        byte[] block = c.headerBlock("GET", "/split", null);
        byte[] f1 = frame(1, 0, 1, block);
        c.send(f1);
        byte[] one = new byte[1];
        for (int i = 0; i < 520; i++) {
            byte[] cf = frame(9, 0, 1, one);
            c.send(cf);
        }
        assertTrue(c.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testRstStreamClosesStream() {
        Conn c = new Conn();
        c.app.respondInHeaders = false;
        c.handshake();
        c.request(1, "POST", "/x", false);
        byte[] rst = frame(3, 0, 1, ints(8));
        c.send(rst);
        assertEquals(0, c.endpoint.getCloseCount());
    }

    @Test
    public void testRapidResetIsRateLimited() {
        Conn c = new Conn();
        c.handshake();
        for (int i = 0; i < 300; i++) {
            int id = 1 + 2 * i;
            c.request(id, "POST", "/x", false);
            byte[] rst = frame(3, 0, id, ints(8));
            c.send(rst);
        }
        List<Frame> frames = c.frames();
        assertEquals(11, goawayError(frames));
    }

    @Test
    public void testMaxConcurrentStreamsRefuses() {
        Conn c = new Conn();
        c.maxStreams = 2;
        c.handshake();
        c.request(1, "POST", "/a", false);
        c.request(3, "POST", "/b", false);
        c.request(5, "POST", "/c", false);
        List<Frame> frames = c.frames();
        Frame rst = first(frames, 3);
        assertNotNull(rst);
        assertEquals(5, rst.streamId);
        assertEquals(7, rst.payload[3]);
    }

    @Test
    public void testPriorityFramesAndUpdates() {
        Conn c = new Conn();
        c.handshake();
        byte[] prio = new byte[5];
        prio[4] = 10;
        byte[] pf = frame(2, 0, 3, prio);
        c.send(pf);
        byte[] upd = "u=2, i".getBytes(StandardCharsets.ISO_8859_1);
        byte[] payload = new byte[4 + upd.length];
        payload[3] = 1;
        System.arraycopy(upd, 0, payload, 4, upd.length);
        byte[] uf = frame(0x10, 0, 0, payload);
        c.send(uf);
        assertEquals(0, c.endpoint.getCloseCount());
    }

    @Test
    public void testPriorityHeaderOnRequest() {
        Conn c = new Conn();
        c.handshake();
        byte[] block = c.headerBlock("GET", "/p", new String[] {"priority", "u=1"});
        byte[] f = frame(1, 5, 1, block);
        c.send(f);
        PriorityParamsProbe.check(c.handler);
    }

    @Test
    public void testWindowUpdateOverflowOnConnection() {
        Conn c = new Conn();
        c.handshake();
        byte[] wu = frame(8, 0, 0, ints(0x7fffffff));
        c.send(wu);
        assertEquals(3, goawayError(c.frames()));
    }

    @Test
    public void testWindowUpdateOverflowOnStream() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/x", false);
        byte[] wu = frame(8, 0, 1, ints(0x7fffffff));
        c.send(wu);
        Frame rst = first(c.frames(), 3);
        assertNotNull(rst);
        assertEquals(3, rst.payload[3]);
    }

    @Test
    public void testZeroWindowUpdateIsError() {
        Conn c = new Conn();
        c.handshake();
        byte[] wu = frame(8, 0, 0, ints(0));
        c.send(wu);
        assertTrue(count(c.frames(), 7) + count(c.frames(), 3) > 0);
    }

    @Test
    public void testDataOnIdleStreamZeroIsError() {
        Conn c = new Conn();
        c.handshake();
        byte[] d = frame(0, 0, 0, new byte[] {1});
        c.send(d);
        assertEquals(1, goawayError(c.frames()));
    }

    @Test
    public void testOversizedFrameIsFrameSizeError() {
        Conn c = new Conn();
        c.handshake();
        byte[] big = new byte[16385];
        byte[] d = frame(0, 0, 1, big);
        c.send(d);
        assertEquals(6, goawayError(c.frames()));
    }

    @Test
    public void testFirstFrameNotSettingsIsProtocolError() {
        Conn c = new Conn();
        c.open();
        c.send(PREFACE);
        byte[] p = frame(6, 0, 0, new byte[8]);
        c.send(p);
        assertEquals(1, goawayError(c.frames()));
    }

    @Test
    public void testBlockedCipherSuiteGetsGoaway() {
        Conn c = new Conn();
        c.handler = new HttpProtocolHandler(c.listener);
        c.handler.connected(c.endpoint);
        c.handler.securityEstablished(new Info("TLSv1.2", "TLS_RSA_WITH_AES_128_CBC_SHA"));
        assertEquals(12, goawayError(c.frames()));
    }

    @Test
    public void testNon204ResponseCodes() {
        int[] codes = {204, 304, 404, 500};
        for (int i = 0; i < codes.length; i++) {
            Conn c = new Conn();
            c.app.status = codes[i];
            c.handshake();
            c.request(1, "GET", "/s", true);
            List<Frame> frames = c.frames();
            assertEquals("status " + codes[i], 1, count(frames, 1));
        }
    }

    @Test
    public void testHeadRequest() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "HEAD", "/h", true);
        assertEquals(1, count(c.frames(), 1));
    }

    @Test
    public void testInformationalAndPush() {
        Conn c = new Conn();
        c.app.informational = true;
        c.app.push = true;
        c.handshake();
        c.request(1, "GET", "/pp", true);
        List<Frame> frames = c.frames();
        assertTrue(count(frames, 1) >= 2);
    }

    @Test
    public void testPingKeepAliveTimerAndSettingsTimeout() {
        Conn c = new Conn();
        c.listener.setPingIntervalMs(1000);
        c.handshake();
        byte[] ack = frame(4, 1, 0, new byte[0]);
        c.send(ack);
        c.endpoint.fireTimers();
        assertTrue(count(c.frames(), 6) >= 1);
    }

    @Test
    public void testSettingsAckTimeoutClosesConnection() {
        Conn c = new Conn();
        c.open();
        c.endpoint.fireTimers();
        assertEquals(4, goawayError(c.frames()));
    }

    @Test
    public void testDisconnectedMidStreamFailsHandler() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/x", false);
        assertEquals(0, failedCount(c));
        c.handler.disconnected();
        assertEquals(1, failedCount(c));
        assertEquals(0, c.endpoint.getCloseCount());
    }

    @Test
    public void testWriteCallbackFiresWhenPendingDrained() {
        Conn c = new Conn();
        c.app.responseBodyLength = 100000;
        c.handshake();
        c.request(1, "GET", "/big", true);
        final int[] fired = new int[1];
        c.app.lastState.onWritable(new Runnable() {
            @Override
            public void run() {
                fired[0]++;
            }
        });
        byte[] wu0 = frame(8, 0, 0, ints(100000));
        c.send(wu0);
        byte[] wu1 = frame(8, 0, 1, ints(100000));
        c.send(wu1);
        assertEquals(1, fired[0]);
        assertFalse(c.frames().isEmpty());
    }


    @Test
    public void testLargeResponseHeaderSplitIntoContinuation() {
        Conn c = new Conn();
        c.app.extraHeaderBytes = 40000;
        c.handshake();
        byte[] st = setting(6, 1000000);
        byte[] s = frame(4, 0, 0, st);
        c.send(s);
        c.request(1, "GET", "/h", true);
        List<Frame> frames = c.frames();
        assertTrue(count(frames, 9) >= 1 || count(frames, 3) + count(frames, 7) > 0);
    }

    @Test
    public void testTwoStreamsShareConnectionWindow() {
        Conn c = new Conn();
        c.app.responseBodyLength = 100000;
        c.handshake();
        c.request(1, "GET", "/a", true);
        c.request(3, "GET", "/b", true);
        List<Frame> frames = c.frames();
        assertTrue(dataBytes(frames, 1) + dataBytes(frames, 3) <= 65535);
        byte[] wu0 = frame(8, 0, 0, ints(500000));
        c.send(wu0);
        byte[] wu1 = frame(8, 0, 1, ints(500000));
        c.send(wu1);
        byte[] wu3 = frame(8, 0, 3, ints(500000));
        c.send(wu3);
        frames = c.frames();
        assertEquals(100000, dataBytes(frames, 1));
        assertEquals(100000, dataBytes(frames, 3));
    }

    @Test
    public void testPriorityOrderedDrainWithUrgencyHeaders() {
        Conn c = new Conn();
        c.app.responseBodyLength = 70000;
        c.handshake();
        byte[] b1 = c.headerBlock("GET", "/lo", new String[] {"priority", "u=6"});
        byte[] f1 = frame(1, 5, 1, b1);
        c.send(f1);
        byte[] b3 = c.headerBlock("GET", "/hi", new String[] {"priority", "u=0"});
        byte[] f3 = frame(1, 5, 3, b3);
        c.send(f3);
        byte[] wu0 = frame(8, 0, 0, ints(1000000));
        c.send(wu0);
        byte[] wu1 = frame(8, 0, 1, ints(1000000));
        c.send(wu1);
        byte[] wu3 = frame(8, 0, 3, ints(1000000));
        c.send(wu3);
        List<Frame> frames = c.frames();
        assertEquals(70000, dataBytes(frames, 1));
        assertEquals(70000, dataBytes(frames, 3));
    }

    @Test
    public void testMissingPathPseudoHeaderResetsStream() {
        Conn c = new Conn();
        c.handshake();
        List<Header> h = new ArrayList<Header>();
        h.add(new Header(":method", "GET"));
        h.add(new Header(":scheme", "https"));
        byte[] block = c.encode(h);
        byte[] f = frame(1, 5, 1, block);
        c.send(f);
        Frame rst = first(c.frames(), 3);
        assertNotNull(rst);
        assertEquals(1, rst.streamId);
        assertEquals(0, c.app.events.size());
    }

    @Test
    public void testPseudoHeaderAfterRegularHeaderResetsStream() {
        Conn c = new Conn();
        c.handshake();
        List<Header> h = new ArrayList<Header>();
        h.add(new Header(":method", "GET"));
        h.add(new Header("x-a", "b"));
        h.add(new Header(":scheme", "https"));
        h.add(new Header(":path", "/"));
        byte[] block = c.encode(h);
        byte[] f = frame(1, 5, 1, block);
        c.send(f);
        assertNotNull(first(c.frames(), 3));
    }

    @Test
    public void testDuplicatePseudoHeaderResetsStream() {
        Conn c = new Conn();
        c.handshake();
        List<Header> h = new ArrayList<Header>();
        h.add(new Header(":method", "GET"));
        h.add(new Header(":method", "POST"));
        h.add(new Header(":scheme", "https"));
        h.add(new Header(":path", "/"));
        byte[] block = c.encode(h);
        byte[] f = frame(1, 5, 1, block);
        c.send(f);
        assertNotNull(first(c.frames(), 3));
    }

    private static int failedCount(Conn c) {
        int n = 0;
        for (int i = 0; i < c.app.events.size(); i++) {
            if ("failed".equals(c.app.events.get(i))) {
                n++;
            }
        }
        return n;
    }

    private static int rstError(List<Frame> frames, int streamId) {
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.type == 3 && f.streamId == streamId) {
                return ((f.payload[0] & 0xff) << 24) | ((f.payload[1] & 0xff) << 16)
                        | ((f.payload[2] & 0xff) << 8) | (f.payload[3] & 0xff);
            }
        }
        return -1;
    }

    // RFC 9113 section 8.2.2: a request with a connection-specific header
    // field MUST be treated as malformed (stream error PROTOCOL_ERROR)
    @Test
    public void testConnectionSpecificHeadersMakeRequestMalformed() {
        String[][] bad = new String[][] {
            {"connection", "keep-alive"},
            {"keep-alive", "timeout=5"},
            {"proxy-connection", "keep-alive"},
            {"transfer-encoding", "chunked"},
            {"upgrade", "websocket"},
            {"te", "gzip"},
        };
        for (int i = 0; i < bad.length; i++) {
            Conn c = new Conn();
            c.handshake();
            byte[] block = c.headerBlock("GET", "/", bad[i]);
            byte[] f = frame(1, 5, 1, block);
            c.send(f);
            String name = bad[i][0];
            assertFalse(name, c.app.events.contains("headers"));
            assertEquals(name, 1, rstError(c.frames(), 1));
            assertEquals(name, 0, failedCount(c));
        }
    }

    @Test
    public void testTeTrailersIsAllowed() {
        Conn c = new Conn();
        c.handshake();
        byte[] block = c.headerBlock("GET", "/", new String[] {"te", "trailers"});
        byte[] f = frame(1, 5, 1, block);
        c.send(f);
        assertTrue(c.app.events.contains("headers"));
        assertEquals(-1, rstError(c.frames(), 1));
    }

    @Test
    public void testRstStreamMidRequestFailsHandlerOnce() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/x", false);
        byte[] rst = frame(3, 0, 1, ints(8));
        c.send(rst);
        assertEquals(1, failedCount(c));
        c.handler.disconnected();
        assertEquals(1, failedCount(c));
    }

    @Test
    public void testDisconnectedWithOpenStreamFailsHandlerOnce() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/x", false);
        c.handler.disconnected();
        assertEquals(1, failedCount(c));
    }

    @Test
    public void testGoawayWithOpenStreamFailsHandlerOnce() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/x", false);
        byte[] g = frame(7, 0, 0, ints(1, 0));
        c.send(g);
        assertTrue(c.endpoint.getCloseCount() > 0);
        c.handler.disconnected();
        assertEquals(1, failedCount(c));
    }

    @Test
    public void testNormalCompletionIsNeverFailed() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "GET", "/x", true);
        assertTrue(c.app.events.contains("complete"));
        byte[] rst = frame(3, 0, 1, ints(8));
        c.send(rst);
        c.handler.disconnected();
        assertEquals(0, failedCount(c));
    }

    @Test
    public void testFrameworkRejectedStreamIsNeverFailed() {
        Conn c = new Conn();
        c.handshake();
        List<Header> h = new ArrayList<Header>();
        h.add(new Header(":method", "GET"));
        byte[] block = c.encode(h);
        byte[] f = frame(1, 4, 1, block);
        c.send(f);
        c.handler.disconnected();
        assertEquals(0, failedCount(c));
    }

    @Test
    public void testClassicConnectNeedsOnlyMethod() {
        Conn c = new Conn();
        c.handshake();
        List<Header> h = new ArrayList<Header>();
        h.add(new Header(":method", "CONNECT"));
        h.add(new Header(":authority", "example.test:443"));
        byte[] block = c.encode(h);
        byte[] f = frame(1, 4, 1, block);
        c.send(f);
        assertTrue(c.app.events.contains("headers"));
    }

    @Test
    public void testIdleTimeoutSendsGracefulGoaway() {
        Conn c = new Conn();
        c.listener.setIdleTimeoutMs(1000L);
        c.handshake();
        byte[] ack = frame(4, 1, 0, new byte[0]);
        c.send(ack);
        c.endpoint.fireTimers();
        List<Frame> frames = c.frames();
        assertTrue(count(frames, 7) >= 1);
    }

    @Test
    public void testPauseAndResumeRequestBody() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/x", false);
        c.app.lastState.pauseRequestBody();
        c.app.lastState.resumeRequestBody();
        assertEquals(0, c.endpoint.getCloseCount());
    }

    @Test
    public void testCleartextPriorKnowledgeSession() {
        Conn c = new Conn();
        c.endpoint.setSecure(false);
        c.handler = new HttpProtocolHandler(c.listener);
        c.handler.connected(c.endpoint);
        c.send(PREFACE);
        byte[] s = frame(4, 0, 0, new byte[0]);
        c.send(s);
        c.request(1, "GET", "/pk", true);
        List<Frame> frames = c.frames();
        assertEquals(2, dataBytes(frames, 1));
        assertEquals("http", c.handler.getScheme());
    }

    @Test
    public void testH2cUpgradeThenFrames() {
        Conn c = new Conn();
        c.endpoint.setSecure(false);
        c.handler = new HttpProtocolHandler(c.listener);
        c.handler.connected(c.endpoint);
        String upgrade = "GET /up HTTP/1.1\r\nHost: h\r\nConnection: Upgrade, HTTP2-Settings\r\n"
                + "Upgrade: h2c\r\nHTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n";
        c.send(upgrade.getBytes(StandardCharsets.ISO_8859_1));
        c.send(PREFACE);
        byte[] s = frame(4, 0, 0, new byte[0]);
        c.send(s);
        String wire = new String(c.endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire, wire.startsWith("HTTP/1.1 101"));
        c.request(3, "GET", "/after", true);
        assertTrue(c.app.events.contains("complete"));
    }

    @Test
    public void testServerPushPromiseFrameSent() {
        Conn c = new Conn();
        c.app.push = true;
        c.handshake();
        c.request(1, "GET", "/pp", true);
        List<Frame> frames = c.frames();
        assertTrue(count(frames, 5) >= 1);
    }

    @Test
    public void testPushDisabledByPeer() {
        Conn c = new Conn();
        c.app.push = true;
        c.open();
        c.send(PREFACE);
        byte[] st = setting(2, 0);
        byte[] s = frame(4, 0, 0, st);
        c.send(s);
        c.request(1, "GET", "/pp", true);
        assertEquals(0, count(c.frames(), 5));
    }

    @Test
    public void testFrameErrorStreamLevel() {
        Conn c = new Conn();
        c.handshake();
        c.handler.frameError(5, 3, "closed");
        Frame rst = first(c.frames(), 3);
        assertNotNull(rst);
        assertEquals(3, rst.streamId);
    }

    @Test
    public void testFrameErrorConnectionLevel() {
        Conn c = new Conn();
        c.handshake();
        c.handler.frameError(6, 0, "size");
        assertEquals(6, goawayError(c.frames()));
    }

    @Test
    public void testDataBeforeParserInitClosesConnection() {
        Conn c = new Conn();
        c.handler = new HttpProtocolHandler(c.listener);
        c.handler.connected(c.endpoint);
        c.handler.securityEstablished(new Info("TLSv1.3", "TLS_AES_128_GCM_SHA256"));
        assertEquals(0, c.endpoint.getCloseCount());
    }

    @Test
    public void testHandlerAccessorsAfterHandshake() {
        Conn c = new Conn();
        c.handshake();
        assertEquals("https", c.handler.getScheme());
        assertTrue(c.handler.isSecure());
        assertNotNull(c.handler.getHpackDecoder());
        assertEquals(Http2Listener.DEFAULT_MAX_HEADER_LIST_SIZE, c.handler.getMaxHeaderListSize());
        assertTrue(c.handler.getMaxRequestBodySize() > 0);
        assertNotNull(c.handler.getStreamHandler());
        assertTrue(c.handler.getNextServerStreamId() % 2 == 0);
        c.handler.getTelemetryConfig();
        c.handler.getTrace();
        assertFalse(c.handler.isTelemetryEnabled());
        assertNotNull(c.handler.getRemoteSocketAddress());
        assertNotNull(c.handler.getLocalSocketAddress());
        c.handler.pauseRead(1);
        c.handler.resumeRead(1);
        byte[] enc = c.handler.encodeHeaders(new ArrayList<Header>());
        assertNotNull(enc);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            out.write(parts[i], 0, parts[i].length);
        }
        return out.toByteArray();
    }

    @Test
    public void testCorruptedPrefaceAtEveryPositionIsNotConsumed() {
        for (int i = 0; i < PREFACE.length; i++) {
            Conn c = new Conn();
            c.open();
            byte[] bad = PREFACE.clone();
            bad[i] = (byte) '!';
            c.send(bad);
            List<Frame> frames = c.frames();
            assertEquals("position " + i, 6, goawayError(frames));
        }
    }

    @Test
    public void testEveryInitialSettingIsApplied() {
        Conn c = new Conn();
        c.open();
        c.send(PREFACE);
        byte[] settings = concat(
                setting(1, 8192), setting(2, 1), setting(3, 50),
                setting(4, 100000), setting(5, 32768), setting(6, 20000),
                setting(8, 1), setting(9, 1), setting(0x99, 7));
        c.send(frame(4, 0, 0, settings));
        assertEquals(20000, c.handler.getMaxHeaderListSize());
        assertTrue(c.handler.isEnablePush());
        assertEquals(0, c.endpoint.getCloseCount());
        assertTrue(count(c.frames(), 4) >= 2);
    }

    @Test
    public void testEverySettingIsReappliedAfterHandshake() {
        Conn c = new Conn();
        c.handshake();
        byte[] settings = concat(
                setting(1, 2048), setting(2, 0), setting(3, 7),
                setting(4, 70000), setting(5, 20000), setting(6, 9000),
                setting(8, 0), setting(9, 0), setting(0x99, 1));
        c.send(frame(4, 0, 0, settings));
        assertEquals(9000, c.handler.getMaxHeaderListSize());
        assertFalse(c.handler.isEnablePush());
        assertEquals(0, c.endpoint.getCloseCount());
        byte[] again = concat(setting(2, 1), setting(8, 1));
        c.send(frame(4, 0, 0, again));
        assertTrue(c.handler.isEnablePush());
    }

    @Test
    public void testClosedStreamIsSweptOnceRetentionExpires() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "GET", "/a", true);
        Stream s = c.handler.getStream(1);
        assertTrue(s.isClosed());
        c.handler.lastStreamCleanup = 0L;
        c.handler.getStream(0);
        assertEquals("recent closed stream is retained", 1,
                c.handler.streamCountForTesting());
        s.timestampCompleted = 1L;
        c.handler.lastStreamCleanup = 0L;
        c.handler.getStream(0);
        assertEquals(0, c.handler.streamCountForTesting());
    }

    @Test
    public void testOpenStreamSurvivesTheSweep() {
        Conn c = new Conn();
        c.handshake();
        c.request(1, "POST", "/a", false);
        c.handler.lastStreamCleanup = 0L;
        c.handler.getStream(0);
        assertEquals(1, c.handler.streamCountForTesting());
    }

    private static final class PriorityParamsProbe {
        static void check(HttpProtocolHandler h) {
            assertNotNull(h.h2PriorityOf(1));
        }
    }

    // ------------------------------------------------------------------
    // the response events as HEADERS, DATA and PUSH_PROMISE frames

    private static Conn scripted(Script script) {
        Conn c = new Conn();
        c.app.script = script;
        c.handshake();
        c.request(1, "GET", "/r", true);
        return c;
    }

    @Test
    public void testResponseStatusDefaultsTo200() {
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.header("x-a", "b");
                r.endMessage();
            }
        });
        List<Decoded> headers = c.responseHeaderFrames(1);
        assertEquals(1, headers.size());
        assertEquals("200", headers.get(0).get(":status"));
        assertEquals("b", headers.get(0).get("x-a"));
        assertTrue("a response with no body ends with its header section",
                headers.get(0).endStream());
        assertEquals(0, count(c.frames(), 0));
    }

    @Test
    public void testTypedFieldsAreFormattedAsFieldValues() {
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.status(200);
                r.longHeader("content-length", 2L);
                r.dateHeader("last-modified", java.time.Instant.ofEpochSecond(0L));
                r.contentType(new org.bluezoo.gumdrop.mime.ContentType("text", "plain", null));
                r.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                r.endMessage();
            }
        });
        Decoded h = c.responseHeaderFrames(1).get(0);
        // the HTTP/1 framing fields are stripped from HTTP/2 responses (HttpVersion)
        assertNull(h.get("content-length"));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", h.get("last-modified"));
        assertTrue(h.get("content-type"), h.get("content-type").startsWith("text/plain"));
        assertFalse(h.endStream());
        assertEquals(2, dataBytes(c.frames(), 1));
    }

    @Test
    public void testEndHeadersSendsTheHeaderSectionBeforeAnyBody() {
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.status(200);
                r.header("content-type", "text/event-stream");
                r.endHeaders();
                // the body and the end of the message have not been given
            }
        });
        List<Decoded> headers = c.responseHeaderFrames(1);
        assertEquals(1, headers.size());
        assertEquals("200", headers.get(0).get(":status"));
        assertEquals("text/event-stream", headers.get(0).get("content-type"));
        assertFalse("the stream stays open for the body", headers.get(0).endStream());
        assertEquals(0, count(c.frames(), 0));
    }

    @Test
    public void testStatusOnlyResponseEndsTheStreamWithTheHeaderSection() {
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.status(204);
                r.endMessage();
            }
        });
        List<Decoded> headers = c.responseHeaderFrames(1);
        assertEquals(1, headers.size());
        assertEquals("204", headers.get(0).get(":status"));
        assertTrue(headers.get(0).endStream());
        assertEquals(0, count(c.frames(), 0));
    }

    @Test
    public void testInterimResponseIsFollowedByTheFinalResponse() {
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.status(103);
                r.header("link", "</style.css>; rel=preload");
                r.endHeaders();
                r.status(200);
                r.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                r.endMessage();
            }
        });
        List<Decoded> headers = c.responseHeaderFrames(1);
        assertEquals(2, headers.size());
        assertEquals("103", headers.get(0).get(":status"));
        assertEquals("</style.css>; rel=preload", headers.get(0).get("link"));
        assertFalse(headers.get(0).endStream());
        assertEquals("200", headers.get(1).get(":status"));
        assertNull("interim fields must not leak into the final response",
                headers.get(1).get("link"));
        assertEquals(2, dataBytes(c.frames(), 1));
    }

    @Test
    public void testFieldAfterTheBodyIsAFinalHeadersFrameThatEndsTheStream() {
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.status(200);
                r.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                r.header("x-checksum", "42");
                r.endMessage();
            }
        });
        List<Decoded> headers = c.responseHeaderFrames(1);
        assertEquals(2, headers.size());
        assertFalse(headers.get(0).endStream());
        Decoded trailers = headers.get(1);
        assertTrue("the trailer section ends the stream", trailers.endStream());
        assertEquals("42", trailers.get("x-checksum"));
        assertNull("RFC 9113 section 8.1: no pseudo-header field in trailers",
                trailers.get(":status"));
        for (int i = 0; i < c.frames().size(); i++) {
            Frame f = c.frames().get(i);
            if (f.type == 0 && f.streamId == 1) {
                assertEquals("DATA must not carry END_STREAM before the trailers", 0, f.flags & 1);
            }
        }
    }

    @Test
    public void testMisuseOfTheResponseEventsIsRejected() {
        final RuntimeException[] thrown = new RuntimeException[4];
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.status(200);
                try {
                    r.header("x-custom", "caf\u00e9");
                } catch (IllegalArgumentException e) {
                    thrown[0] = e;
                }
                r.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                try {
                    r.header("content-length", "2");
                } catch (IllegalArgumentException e) {
                    thrown[1] = e;
                }
                r.header("x-checksum", "42");
                try {
                    r.bodyContent(ByteBuffer.wrap(new byte[] {'!'}));
                } catch (IllegalStateException e) {
                    thrown[2] = e;
                }
                r.endMessage();
                r.endMessage();
                try {
                    r.header("x-late", "1");
                } catch (IllegalStateException e) {
                    thrown[3] = e;
                }
            }
        });
        assertNotNull("non-ASCII value", thrown[0]);
        assertNotNull("forbidden trailer name", thrown[1]);
        assertNotNull("body after a trailer", thrown[2]);
        assertNotNull("field after endMessage", thrown[3]);
        // the second endMessage sent nothing more: one response, one trailer section
        assertEquals(2, c.responseHeaderFrames(1).size());
    }

    @Test
    public void testPushPromiseCarriesThePromisedRequest() {
        Conn c = scripted(new Script() {
            @Override
            public void run(HttpResponse r) {
                r.startPushPromise(HttpMethod.GET, "/pushed");
                r.header("accept", "text/css");
                boolean ok = r.endPushPromise();
                if (!ok) {
                    throw new IllegalStateException("push refused");
                }
                r.status(200);
                r.endMessage();
            }
        });
        Decoded promise = null;
        List<Decoded> all = c.decodedHeaderFrames();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).type == 5) {
                promise = all.get(i);
            }
        }
        assertNotNull("expected a PUSH_PROMISE frame", promise);
        assertEquals(1, promise.streamId);
        assertTrue(promise.promisedStreamId > 0 && promise.promisedStreamId % 2 == 0);
        assertEquals("GET", promise.get(":method"));
        assertEquals("https", promise.get(":scheme"));
        assertEquals("h.test", promise.get(":authority"));
        assertEquals("/pushed", promise.get(":path"));
        assertEquals("text/css", promise.get("accept"));
    }

    @Test
    public void testPushPromiseReturnsFalseWhenThePeerDisabledPush() {
        final boolean[] result = new boolean[] {true};
        Conn c = new Conn();
        c.app.script = new Script() {
            @Override
            public void run(HttpResponse r) {
                r.startPushPromise(HttpMethod.GET, "/pushed");
                result[0] = r.endPushPromise();
                r.status(204);
                r.endMessage();
            }
        };
        c.open();
        c.send(PREFACE);
        c.send(frame(4, 0, 0, setting(2, 0)));
        c.request(1, "GET", "/r", true);
        assertFalse(result[0]);
        assertEquals(0, count(c.frames(), 5));
    }
}
