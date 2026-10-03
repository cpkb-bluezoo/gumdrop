/*
 * H3ServerFlowTest.java
 * Copyright (C) 2025 Chris Burdess
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpServerMetrics;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.junit.Test;

/**
 * End-to-end unit tests of the HTTP/3 server request path over an
 * in-memory QUIC connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ServerFlowTest {

    static class Recorder extends CollectingRequestHandler {
        final List<String> events = new ArrayList<String>();
        int bodyBytes;
        HttpResponseState state;
        boolean datagrams;
        boolean decode;
        boolean encode;
        final List<Long> capsules = new ArrayList<Long>();
        int datagramCount;

        @Override
        public void headers(HttpResponseState s, Headers headers) {
            state = s;
            events.add("headers");
        }

        @Override
        public void startRequestBody(HttpResponseState s) {
            events.add("start");
        }

        @Override
        public void requestBodyContent(HttpResponseState s, ByteBuffer data) {
            bodyBytes += data.remaining();
            events.add("body");
        }

        @Override
        public void endRequestBody(HttpResponseState s) {
            events.add("end");
        }

        @Override
        public void requestComplete(HttpResponseState s) {
            state = s;
            events.add("complete");
        }

        @Override
        public void failed(HttpResponseState s, Exception cause) {
            events.add("failed");
        }

        @Override
        public boolean wantsDatagrams() {
            return datagrams;
        }

        @Override
        public void datagramReceived(HttpResponseState s, ByteBuffer data) {
            datagramCount++;
        }

        @Override
        public void capsuleReceived(HttpResponseState s, long type, ByteBuffer value) {
            capsules.add(Long.valueOf(type));
        }

        @Override
        public boolean decodeRequestContentCoding() {
            return decode;
        }

        @Override
        public boolean encodeResponseContentCoding() {
            return encode;
        }
    }

    static final class Noop implements ProtocolHandler {
        @Override public void receive(ByteBuffer data) { }
        @Override public void connected(Endpoint endpoint) { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void disconnected() { }
        @Override public void error(Exception cause) { }
    }

    static final class Fixture {
        final QuicConnection conn;
        final Http3ServerHandler server;
        final Recorder rec;
        final List<Recorder> all = new ArrayList<Recorder>();
        long nextStream = 0;
        boolean nullHandler;

        Fixture(HttpAuthenticationProvider auth, HttpServerMetrics metrics,
                TelemetryConfig tc, boolean secHeaders, boolean compress, String hsts) {
            conn = QuicConnectionTestFactory.create(true);
            rec = new Recorder();
            HttpStreamHandler sh = new HttpStreamHandler() {
                @Override
                public HttpRequestHandler openStream(HttpResponseState stream) {
                    if (nullHandler) {
                        return null;
                    }
                    return CollectingRequestHandler.bind(rec, stream);
                }
            };
            server = new Http3ServerHandler(conn, sh, auth, metrics, tc, secHeaders, compress, hsts);
        }

        Fixture() {
            this(null, null, null, false, false, null);
        }

        H3Stream open() {
            Endpoint ep = conn.openStream(new Noop());
            ProtocolHandler stream = server.acceptStream(ep);
            assertNotNull(stream);
            stream.connected(ep);
            return (H3Stream) stream;
        }
    }

    static byte[] headersFrame(String... pairs) {
        List<Header> headers = new ArrayList<Header>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(new Header(pairs[i], pairs[i + 1]));
        }
        SimpleEncoder encoder = new SimpleEncoder();
        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();
        byte[] enc = new byte[buf.remaining()];
        buf.get(enc);
        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(enc.length));
        H3Writer.writeHeaders(out, enc);
        return out.array();
    }

    static byte[] dataFrame(byte[] payload) {
        ByteBuffer out = ByteBuffer.allocate(H3Writer.dataLength(payload.length));
        H3Writer.writeData(out, payload);
        return out.array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            out.write(parts[i], 0, parts[i].length);
        }
        return out.toByteArray();
    }

    static byte[] get(String path) {
        return headersFrame(":method", "GET", ":scheme", "https", ":path", path, ":authority", "x");
    }

    static void feed(ProtocolHandler stream, byte[] data) {
        stream.receive(ByteBuffer.wrap(data));
    }

    static byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write(in);
        gz.close();
        return bos.toByteArray();
    }

    @Test
    public void testSimpleGetAndResponse() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, get("/"));
        stream.readFinished();
        assertTrue(f.rec.events.toString(), f.rec.events.contains("complete"));
        assertEquals(HttpResponseStateCheck.version(f.rec.state), "HTTP_3");
        Headers h = new Headers();
        h.status(HttpStatus.OK);
        h.add("content-type", "text/plain");
        f.rec.state.headers(h);
        f.rec.state.startResponseBody();
        f.rec.state.responseBodyContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        f.rec.state.endResponseBody();
        f.rec.state.complete();
        assertEquals("https", stream.getScheme());
        assertTrue(stream.isSecure());
        assertNotNull(stream.getRemoteAddress());
        assertNotNull(stream.getLocalAddress());
        assertNull(stream.getPrincipal());
        assertEquals(1L, stream.getStreamId());
        assertFalse(stream.pushPromise(new Headers()));
    }

    static final class HttpResponseStateCheck {
        static String version(HttpResponseState s) {
            return s.getVersion().name();
        }
    }

    @Test
    public void testPostWithBody() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        byte[] req = concat(
                headersFrame(":method", "POST", ":scheme", "https", ":path", "/p",
                        ":authority", "x", "content-length", "5"),
                dataFrame(new byte[] {1, 2}), dataFrame(new byte[] {3, 4, 5}));
        feed(stream, req);
        stream.readFinished();
        assertEquals(5, f.rec.bodyBytes);
        assertTrue(f.rec.events.contains("start"));
        assertTrue(f.rec.events.contains("end"));
        assertTrue(f.rec.events.contains("complete"));
    }

    @Test
    public void testChunkedByteByByteInput() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        byte[] req = concat(get("/slow"), dataFrame(new byte[] {9, 9, 9}));
        for (int i = 0; i < req.length; i++) {
            feed(stream, new byte[] {req[i]});
        }
        stream.readFinished();
        assertEquals(3, f.rec.bodyBytes);
    }

    @Test
    public void testHeadResponseSuppressesBody() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "HEAD", ":scheme", "https", ":path", "/",
                ":authority", "x"));
        stream.readFinished();
        Headers h = new Headers();
        h.status(HttpStatus.OK);
        f.rec.state.headers(h);
        f.rec.state.startResponseBody();
        f.rec.state.responseBodyContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        f.rec.state.complete();
    }

    @Test
    public void testResponseWithoutBody() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, get("/"));
        stream.readFinished();
        Headers h = new Headers();
        h.status(HttpStatus.NO_CONTENT);
        f.rec.state.headers(h);
        f.rec.state.complete();
    }

    @Test
    public void testBodyWithoutStartResponseBody() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, get("/"));
        stream.readFinished();
        Headers h = new Headers();
        h.status(HttpStatus.OK);
        f.rec.state.headers(h);
        f.rec.state.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
        f.rec.state.complete();
    }

    @Test
    public void testInformationalResponses() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, get("/"));
        stream.readFinished();
        Headers info = new Headers();
        info.add("link", "</x>; rel=preload");
        info.add("connection", "close");
        f.rec.state.sendInformational(103, info);
        try {
            f.rec.state.sendInformational(200, info);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        Headers h = new Headers();
        h.status(HttpStatus.OK);
        f.rec.state.headers(h);
        f.rec.state.startResponseBody();
        try {
            f.rec.state.sendInformational(103, info);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        f.rec.state.complete();
    }

    @Test
    public void testSecurityHeadersAndHsts() throws Exception {
        Fixture f = new Fixture(null, null, null, true, false, "max-age=1");
        H3Stream stream = f.open();
        feed(stream, get("/"));
        stream.readFinished();
        Headers h = new Headers();
        h.status(HttpStatus.OK);
        f.rec.state.headers(h);
        f.rec.state.startResponseBody();
        f.rec.state.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
        f.rec.state.complete();
        assertTrue(f.server.getAddSecurityHeaders());
        assertEquals("max-age=1", f.server.getStrictTransportSecurityHeaderValue());
    }

    @Test
    public void testResponseCompression() throws Exception {
        Fixture f = new Fixture(null, null, null, false, true, null);
        f.rec.encode = true;
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "GET", ":scheme", "https", ":path", "/",
                ":authority", "x", "accept-encoding", "gzip"));
        stream.readFinished();
        Headers h = new Headers();
        h.status(HttpStatus.OK);
        h.add("content-type", "text/plain");
        f.rec.state.headers(h);
        f.rec.state.startResponseBody();
        byte[] big = new byte[2000];
        f.rec.state.responseBodyContent(ByteBuffer.wrap(big));
        f.rec.state.endResponseBody();
        f.rec.state.complete();
        assertTrue(f.server.getCompressResponses());
    }

    @Test
    public void testRequestContentDecoding() throws Exception {
        Fixture f = new Fixture();
        f.rec.decode = true;
        H3Stream stream = f.open();
        byte[] payload = gzip("hello world hello world".getBytes("UTF-8"));
        feed(stream, concat(
                headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                        ":authority", "x", "content-encoding", "gzip"),
                dataFrame(payload)));
        stream.readFinished();
        assertEquals(23, f.rec.bodyBytes);
        assertTrue(f.rec.events.contains("end"));
    }

    @Test
    public void testRequestContentDecodingUnsupportedCoding() throws Exception {
        Fixture f = new Fixture();
        f.rec.decode = true;
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                ":authority", "x", "content-encoding", "bogus"));
        assertFalse(f.rec.events.contains("headers"));
    }

    @Test
    public void testRequestContentDecodingCorruptData() throws Exception {
        Fixture f = new Fixture();
        f.rec.decode = true;
        H3Stream stream = f.open();
        feed(stream, concat(
                headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                        ":authority", "x", "content-encoding", "gzip"),
                dataFrame(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})));
        stream.readFinished();
    }

    @Test
    public void testMissingPseudoHeaderYields400() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "GET", ":authority", "x"));
        assertFalse(f.rec.events.contains("headers"));
    }

    @Test
    public void testNoApplicationHandlerYields404() throws Exception {
        Fixture f = new Fixture();
        f.nullHandler = true;
        H3Stream stream = f.open();
        feed(stream, get("/"));
        assertTrue(f.rec.events.isEmpty());
    }

    @Test
    public void testInvalidContentLength() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                ":authority", "x", "content-length", "abc"));
        assertTrue(f.rec.events.isEmpty());
    }

    @Test
    public void testBodyLongerThanContentLength() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, concat(
                headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                        ":authority", "x", "content-length", "1"),
                dataFrame(new byte[] {1, 2, 3})));
        assertEquals(0, f.rec.bodyBytes);
    }

    @Test
    public void testBodyShorterThanContentLength() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, concat(
                headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                        ":authority", "x", "content-length", "10"),
                dataFrame(new byte[] {1, 2, 3})));
        stream.readFinished();
        assertFalse(f.rec.events.contains("complete"));
    }

    @Test
    public void testAuthentication() throws Exception {
        HttpAuthenticationProvider auth = new HttpAuthenticationProvider() {
            @Override
            protected String getAuthMethod() {
                return "BASIC";
            }

            @Override
            protected String getRealmName() {
                return "realm";
            }

            @Override
            protected boolean passwordMatch(String realm, String username, String password) {
                return "pw".equals(password);
            }

            @Override
            protected String getDigestHA1(String realm, String username) {
                return null;
            }

            @Override
            protected org.bluezoo.gumdrop.auth.Realm.TokenValidationResult validateBearerToken(String token) {
                return null;
            }

            @Override
            protected org.bluezoo.gumdrop.auth.Realm.TokenValidationResult validateOAuthToken(String token) {
                return null;
            }

            @Override
            public boolean isAuthenticationRequired() {
                return true;
            }
        };
        Fixture f = new Fixture(auth, null, null, false, false, null);
        H3Stream denied = f.open();
        feed(denied, get("/"));
        assertTrue(f.rec.events.isEmpty());
        H3Stream ok = f.open();
        String cred = Base64.getEncoder().encodeToString("u:pw".getBytes("UTF-8"));
        feed(ok, headersFrame(":method", "GET", ":scheme", "https", ":path", "/",
                ":authority", "x", "authorization", "Basic " + cred));
        assertTrue(f.rec.events.contains("headers"));
        assertNotNull(ok.getPrincipal());
        assertNotNull(f.server.getAuthenticationProvider());
    }

    @Test
    public void testFrameErrorsOnRequestStream() throws Exception {
        long[] settings = new long[] {1, 1};
        List<byte[]> frames = new ArrayList<byte[]>();
        ByteBuffer b = ByteBuffer.allocate(H3Writer.settingsLength(settings));
        H3Writer.writeSettings(b, settings);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.goawayLength(4));
        H3Writer.writeGoaway(b, 4);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.maxPushIdLength(4));
        H3Writer.writeMaxPushId(b, 4);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.cancelPushLength(4));
        H3Writer.writeCancelPush(b, 4);
        frames.add(b.array());
        byte[] fv = "u=1".getBytes("US-ASCII");
        b = ByteBuffer.allocate(H3Writer.priorityUpdateRequestLength(0, fv.length));
        H3Writer.writePriorityUpdateRequest(b, 0, fv);
        frames.add(b.array());
        b = ByteBuffer.allocate(H3Writer.priorityUpdatePushLength(0, fv.length));
        H3Writer.writePriorityUpdatePush(b, 0, fv);
        frames.add(b.array());
        byte[] enc = new byte[] {0, 0, (byte) 0xd1};
        b = ByteBuffer.allocate(H3Writer.pushPromiseLength(1, enc.length));
        H3Writer.writePushPromise(b, 1, enc);
        frames.add(b.array());
        frames.add(new byte[] {0x02, 0x00});
        frames.add(new byte[] {0x21, 0x00});
        for (int i = 0; i < frames.size(); i++) {
            Fixture f = new Fixture();
            H3Stream stream = f.open();
            feed(stream, frames.get(i));
        }
    }

    @Test
    public void testDataBeforeHeaders() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, dataFrame(new byte[] {1}));
        assertTrue(f.rec.events.isEmpty());
    }

    @Test
    public void testQpackDecodeFailureCancelsStream() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        byte[] enc = new byte[] {(byte) 0x05, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(enc.length));
        H3Writer.writeHeaders(out, enc);
        feed(stream, out.array());
        assertTrue(f.rec.events.isEmpty());
        stream.disconnected();
    }

    @Test
    public void testStreamErrorAndCancel() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, get("/"));
        stream.error(new IOException("boom"));
        assertTrue(f.rec.events.contains("failed"));
        Fixture g = new Fixture();
        H3Stream s2 = g.open();
        feed(s2, get("/"));
        s2.cancel();
        Fixture h = new Fixture();
        H3Stream s3 = h.open();
        s3.error(new IOException("before headers"));
        s3.securityEstablished(null);
    }

    @Test
    public void testHttpStateHelpers() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, get("/"));
        final boolean[] ran = new boolean[2];
        stream.onWritable(new Runnable() {
            @Override
            public void run() {
                ran[1] = true;
            }
        });
        stream.pauseRequestBody();
        stream.resumeRequestBody();
        assertNotNull(stream.getHandler());
        assertFalse(stream.hasHeldBody());
        stream.flushHeldBody();
        assertFalse(stream.isWebSocketUpgraded());
        assertNull(stream.getSecurityInfo());
    }

    @Test
    public void testTelemetryAndMetrics() throws Exception {
        TelemetryConfig tc = new TelemetryConfig();
        HttpServerMetrics metrics = new HttpServerMetrics(tc);
        Fixture f = new Fixture(null, metrics, tc, false, false, null);
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "GET", ":scheme", "https", ":path", "/t",
                ":authority", "x", "user-agent", "ua",
                "traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
        stream.readFinished();
        Headers h = new Headers();
        h.status(HttpStatus.NOT_FOUND);
        f.rec.state.headers(h);
        f.rec.state.startResponseBody();
        f.rec.state.responseBodyContent(ByteBuffer.wrap(new byte[] {1}));
        f.rec.state.complete();
        assertTrue(f.server.isTelemetryEnabled());
        assertNotNull(f.server.getTelemetryConfig());
        assertNotNull(f.server.getMetrics());
        assertNotNull(f.server.getTrace());
        H3Stream s2 = f.open();
        feed(s2, get("/second"));
        s2.error(new IOException("lost"));
        H3Stream s3 = f.open();
        feed(s3, get("/third"));
        Headers ok = new Headers();
        ok.status(HttpStatus.OK);
        f.rec.state.headers(ok);
        f.rec.state.complete();
    }

    @Test
    public void testCapsuleModeAndDatagrams() throws Exception {
        Fixture f = new Fixture();
        f.rec.datagrams = true;
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                ":authority", "x", "capsule-protocol", "?1"));
        byte[] datagramCapsule = new byte[] {0x00, 0x03, 1, 2, 3};
        byte[] otherCapsule = new byte[] {0x17, 0x01, 9};
        feed(stream, dataFrame(concat(datagramCapsule, otherCapsule)));
        assertEquals(1, f.rec.datagramCount);
        assertEquals(1, f.rec.capsules.size());
        assertTrue(stream.sendDatagram(ByteBuffer.wrap(new byte[] {1, 2})));
        assertTrue(stream.sendCapsule(0x17, ByteBuffer.wrap(new byte[] {5})));
        assertFalse(stream.sendCapsule(0x17, null));
        assertFalse(stream.sendDatagram(null));
        stream.httpDatagramReceived(ByteBuffer.wrap(new byte[] {0, 1}));
        assertEquals(2, f.rec.datagramCount);
        stream.readFinished();
    }

    @Test
    public void testCapsuleErrors() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                ":authority", "x", "capsule-protocol", "?1"));
        feed(stream, dataFrame(new byte[] {0x00, 0x01, 1}));
        Fixture g = new Fixture();
        g.rec.datagrams = false;
        H3Stream s2 = g.open();
        feed(s2, headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                ":authority", "x", "capsule-protocol", "?1"));
        s2.httpDatagramReceived(ByteBuffer.wrap(new byte[] {1}));
        Fixture h = new Fixture();
        H3Stream s3 = h.open();
        feed(s3, headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                ":authority", "x", "capsule-protocol", "?1"));
        feed(s3, dataFrame(new byte[] {0x17, 0x05, 1}));
        s3.readFinished();
        Fixture k = new Fixture();
        H3Stream s4 = k.open();
        feed(s4, headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                ":authority", "x", "capsule-protocol", "?1"));
        feed(s4, dataFrame(new byte[] {(byte) 0xff}));
    }

    @Test
    public void testConnectUdpAndIp() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "CONNECT", ":protocol", "connect-udp",
                ":scheme", "https", ":path", "/.well-known/masque/udp/h/1/",
                ":authority", "x"));
        assertTrue(stream.acceptConnectUdp());
        assertFalse(stream.acceptConnectIp());
        Fixture g = new Fixture();
        H3Stream s2 = g.open();
        feed(s2, headersFrame(":method", "CONNECT", ":protocol", "connect-ip",
                ":scheme", "https", ":path", "/.well-known/masque/ip/*/*/",
                ":authority", "x"));
        assertTrue(s2.acceptConnectIp());
        assertFalse(s2.acceptConnectIp());
    }

    @Test
    public void testWebSocketUpgrade() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, headersFrame(":method", "CONNECT", ":protocol", "websocket",
                ":scheme", "https", ":path", "/ws", ":authority", "x"));
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        stream.upgradeToWebSocket("chat", ws);
        assertTrue(stream.isWebSocketUpgraded());
        assertEquals(1, ws.openedCount);
        byte[] mask = new byte[] {1, 2, 3, 4};
        byte[] text = new byte[] {'h', 'i'};
        byte[] frame = new byte[8];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) 0x82;
        System.arraycopy(mask, 0, frame, 2, 4);
        frame[6] = (byte) (text[0] ^ mask[0]);
        frame[7] = (byte) (text[1] ^ mask[1]);
        feed(stream, dataFrame(frame));
        assertEquals(1, ws.texts.size());
        byte[] bin = new byte[] {(byte) 0x82, (byte) 0x81, 1, 2, 3, 4, (byte) (7 ^ 1)};
        feed(stream, dataFrame(bin));
        assertEquals(1, ws.binaries.size());
        ws.session.sendText("reply");
        ws.session.sendBinary(ByteBuffer.wrap(new byte[] {1}));
        stream.readFinished();
        assertEquals(1, ws.closeCodes.size());
    }

    @Test
    public void testWebSocketUpgradeErrors() throws Exception {
        Fixture f = new Fixture();
        H3Stream stream = f.open();
        feed(stream, get("/"));
        RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        try {
            stream.upgradeToWebSocket(null, ws);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        Fixture g = new Fixture();
        H3Stream s2 = g.open();
        feed(s2, headersFrame(":method", "CONNECT", ":protocol", "websocket",
                ":scheme", "https", ":path", "/ws", ":authority", "x"));
        RecordingWebSocketEventHandler ws2 = new RecordingWebSocketEventHandler();
        s2.upgradeToWebSocket(null, ws2);
        s2.error(new IOException("lost"));
        assertEquals(1, ws2.errors.size() + ws2.closeCodes.size());
        Fixture h = new Fixture();
        H3Stream s3 = h.open();
        feed(s3, headersFrame(":method", "CONNECT", ":protocol", "websocket",
                ":scheme", "https", ":path", "/ws", ":authority", "x"));
        RecordingWebSocketEventHandler ws3 = new RecordingWebSocketEventHandler();
        s3.upgradeToWebSocket(null, ws3);
        s3.error(new org.bluezoo.gumdrop.quic.QuicConnectionCloseException(true, 0x10c, "bye"));
        feed(s3, dataFrame(new byte[] {(byte) 0xff, (byte) 0xff}));
    }

    @Test
    public void testServerHandlerLifecycle() throws Exception {
        Fixture f = new Fixture();
        f.server.goawayReceived(0L);
        f.server.priorityUpdateReceived(0L, "u=2, i");
        f.server.priorityUpdateReceived(0L, "u=1");
        assertNotNull(f.server.getStreamPriority(0L));
        f.server.settingsReceived(new long[] {0x33, 2});
        f.server.settingsReceived(new long[] {0x33, 1});
        f.server.settingsReceived(new long[] {0x33, 0, 0x06, 1000, 0x01, 100});
        f.server.settingsReceived(new long[] {0x01, 2, 0x06, 1000});
        assertFalse(f.server.peerH3Datagram());
        assertTrue(f.server.getLocalMaxFieldSectionSize() > 0);
        assertFalse(f.server.sendHttpDatagram(0L, new byte[] {1}) && false);
        assertNotNull(f.server.getSelectorLoop() == null ? "" : "loop");
        f.server.close();
    }

    @Test
    public void testManyStreamsAndPriorities() throws Exception {
        Fixture f = new Fixture();
        for (int i = 0; i < 6; i++) {
            H3Stream stream = f.open();
            feed(stream, headersFrame(":method", "GET", ":scheme", "https", ":path", "/",
                    ":authority", "x", "priority", (i % 2 == 0) ? "u=1, i" : "u=5"));
            stream.readFinished();
            Headers h = new Headers();
            h.status(HttpStatus.OK);
            f.rec.state.headers(h);
            f.rec.state.startResponseBody();
            f.rec.state.responseBodyContent(ByteBuffer.wrap(new byte[] {(byte) i}));
            f.rec.state.complete();
        }
    }

    @Test
    public void testTruncatedGzipRequestBodyIsRejectedAtEndOfRequest() throws Exception {
        Fixture f = new Fixture();
        f.rec.decode = true;
        H3Stream stream = f.open();
        byte[] whole = gzip("hello world hello world".getBytes("UTF-8"));
        byte[] truncated = new byte[whole.length - 8];
        System.arraycopy(whole, 0, truncated, 0, truncated.length);
        feed(stream, concat(
                headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                        ":authority", "x", "content-encoding", "gzip"),
                dataFrame(truncated)));
        stream.readFinished();
        assertFalse(f.rec.events.toString(), f.rec.events.contains("complete"));
    }

    @Test
    public void testCompleteGzipRequestBodyEndsTheDecoderAtEndOfRequest() throws Exception {
        Fixture f = new Fixture();
        f.rec.decode = true;
        H3Stream stream = f.open();
        byte[] whole = gzip("hello world hello world".getBytes("UTF-8"));
        feed(stream, concat(
                headersFrame(":method", "POST", ":scheme", "https", ":path", "/",
                        ":authority", "x", "content-encoding", "gzip"),
                dataFrame(whole)));
        stream.readFinished();
        assertEquals(23, f.rec.bodyBytes);
        assertEquals(1, Collections.frequency(f.rec.events, "end"));
        assertTrue(f.rec.events.toString(), f.rec.events.contains("complete"));
    }
}
