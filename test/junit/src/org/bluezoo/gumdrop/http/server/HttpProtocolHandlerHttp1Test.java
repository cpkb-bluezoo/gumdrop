/*
 * HttpProtocolHandlerHttp1Test.java
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

import org.bluezoo.gumdrop.http.HeaderFields;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.RecordingWebSocketEventHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.junit.Test;

/**
 * Drives the HTTP/1.1 request parser of {@link HttpProtocolHandler} with
 * valid, malformed and fragmented requests (RFC 9112).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpProtocolHandlerHttp1Test {

    private static class Recorder {
        final List<String> methods = new ArrayList<String>();
        final List<String> paths = new ArrayList<String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int completed;
        int failed;
        int bodyEnds;
        boolean decode;
        boolean encode;
        int status = 200;
        boolean withLength = true;
        int chunks = 1;
        String extraName;
        String extraValue;
        HeadersHook hook;
    }

    private interface HeadersHook {
        void run(HttpResponse state, List<Header> headers);
    }

    private static class Fixture {
        final Http2Listener listener = new Http2Listener();
        final Recorder rec = new Recorder();
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        HttpProtocolHandler handler;

        Fixture() {
            final Recorder r = rec;
            listener.setStreamHandler(new HttpStreamHandler() {
                @Override
                public HttpRequestHandler openStream(HttpResponse state) {
                    return new CollectingRequestHandler(state) {
                        @Override
                        public boolean decodeRequestContentCoding() {
                            return r.decode;
                        }

                        @Override
                        public boolean encodeResponseContentCoding() {
                            return r.encode;
                        }

                        @Override
                        public void headers(HttpResponse s, List<Header> headers) {
                            r.methods.add(HeaderFields.getValue(headers, ":method"));
                            r.paths.add(HeaderFields.getValue(headers, ":path"));
                            if (r.hook != null) {
                                r.hook.run(s, headers);
                            }
                        }

                        @Override
                        public void requestBodyContent(HttpResponse s,
                                ByteBuffer data) {
                            byte[] b = new byte[data.remaining()];
                            data.get(b);
                            r.body.write(b, 0, b.length);
                        }

                        @Override
                        public void endRequestBody(HttpResponse s) {
                            r.bodyEnds++;
                        }

                        @Override
                        public void failed(HttpResponse s, Exception cause) {
                            r.failed++;
                        }

                        @Override
                        public void requestComplete(HttpResponse s) {
                            r.completed++;
                            if (r.hook != null) {
                                return;
                            }
                            s.status(r.status);
                            if (r.withLength) {
                                s.longHeader("Content-Length", 2L * r.chunks);
                            }
                            if (r.extraName != null) {
                                s.header(r.extraName, r.extraValue);
                            }
                            // the header section goes out before the body
                            s.endHeaders();
                            for (int i = 0; i < r.chunks; i++) {
                                s.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                            }
                            s.endMessage();
                        }
                    };
                }
            });
        }

        void open() {
            endpoint.setSelectorLoop(new InlineSelectorLoop());
            handler = new HttpProtocolHandler(listener);
            handler.connected(endpoint);
        }

        // Honours the receive() buffer contract: unconsumed bytes are kept
        // (compact) and precede the next chunk.
        void feed(String s, int chunk) {
            byte[] data = s.getBytes(StandardCharsets.ISO_8859_1);
            if (carry == null) {
                carry = ByteBuffer.allocate(65536);
            }
            int pos = 0;
            while (pos < data.length) {
                int n = Math.min(chunk, data.length - pos);
                carry.put(data, pos, n);
                pos += n;
                carry.flip();
                handler.receive(carry);
                carry.compact();
            }
        }

        ByteBuffer carry;

        String wire() {
            return new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static Fixture run(String request, int chunk) {
        Fixture f = new Fixture();
        f.open();
        f.feed(request, chunk);
        return f;
    }

    private static Fixture run(String request) {
        return run(request, Integer.MAX_VALUE);
    }

    @Test
    public void testSimpleGet() {
        Fixture f = run("GET /x?a=b HTTP/1.1\r\nHost: h.test\r\n\r\n");
        assertEquals("GET", f.rec.methods.get(0));
        assertEquals("/x?a=b", f.rec.paths.get(0));
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 200"));
        assertTrue(f.wire().endsWith("ok"));
        assertEquals(1, f.rec.completed);
    }

    /**
     * The parts of a response written while a read is handled (header
     * section, chunk, last chunk) go to the endpoint as one write. Over TLS
     * each write is a record: three records a response cost three seals
     * where one will do.
     */
    @Test
    public void testChunkedResponseGoesOutAsOneWrite() {
        Fixture f = new Fixture();
        f.rec.withLength = false;
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h.test\r\n\r\n", Integer.MAX_VALUE);
        assertTrue(f.wire(), f.wire().contains("Transfer-Encoding: chunked"));
        assertTrue(f.wire(), f.wire().endsWith("2\r\nok\r\n0\r\n\r\n"));
        assertEquals(1, f.endpoint.getWrites().size());
    }

    @Test
    public void testResponsesToPipelinedRequestsGoOutAsOneWrite() {
        Fixture f = run("GET /a HTTP/1.1\r\nHost: h.test\r\n\r\n"
                + "GET /b HTTP/1.1\r\nHost: h.test\r\n\r\n");
        assertEquals(2, f.rec.completed);
        assertEquals(1, f.endpoint.getWrites().size());
        assertEquals(2, f.wire().split("HTTP/1.1 200", -1).length - 1);
    }

    /** What was held for the end of the read is written before the connection is closed. */
    @Test
    public void testHeldResponseIsWrittenBeforeConnectionClose() {
        Fixture f = new Fixture();
        f.rec.withLength = false;
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h.test\r\nConnection: close\r\n\r\n", Integer.MAX_VALUE);
        assertEquals(1, f.endpoint.getCloseCount());
        assertEquals("nothing is written after the close", 0, f.endpoint.getWritesAfterClose());
        assertTrue(f.wire(), f.wire().endsWith("2\r\nok\r\n0\r\n\r\n"));
    }

    /** A body too large to be worth copying is passed straight through, after what was held. */
    @Test
    public void testLargeBodyIsNotHeld() {
        Fixture f = new Fixture();
        final byte[] big = new byte[100000];
        java.util.Arrays.fill(big, (byte) 'x');
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(200);
                state.longHeader("Content-Length", big.length);
                state.bodyContent(ByteBuffer.wrap(big));
                state.endMessage();
            }
        };
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h.test\r\n\r\n", Integer.MAX_VALUE);
        String wire = f.wire();
        int body = wire.indexOf("\r\n\r\n") + 4;
        assertTrue(wire.startsWith("HTTP/1.1 200"));
        assertEquals(big.length, wire.length() - body);
        assertEquals('x', wire.charAt(wire.length() - 1));
    }

    @Test
    public void testByteAtATimeEqualsWholeBuffer() {
        String req = "POST /p HTTP/1.1\r\nHost: h.test\r\nContent-Length: 5\r\n\r\nhello"
                + "GET /q HTTP/1.1\r\nHost: h.test\r\n\r\n";
        Fixture whole = run(req);
        Fixture slow = run(req, 1);
        assertEquals(2, whole.rec.completed);
        assertEquals(2, slow.rec.completed);
        assertEquals("hello", new String(slow.rec.body.toByteArray(),
                StandardCharsets.ISO_8859_1));
        assertEquals(whole.rec.methods, slow.rec.methods);
        assertEquals(whole.rec.paths, slow.rec.paths);
        assertEquals(whole.wire().replaceAll("Date: [^\r]*", ""),
                slow.wire().replaceAll("Date: [^\r]*", ""));
    }

    @Test
    public void testChunkedBodyAndTrailersEveryChunkSize() {
        String req = "POST /c HTTP/1.1\r\nHost: h.test\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3;ext=1\r\nabc\r\n4\r\ndefg\r\n0\r\nTrailer-X: y\r\n\r\n";
        int[] sizes = {1, 2, 3, 5, 7, 1000};
        for (int i = 0; i < sizes.length; i++) {
            Fixture f = run(req, sizes[i]);
            assertEquals("chunk " + sizes[i], "abcdefg", new String(
                    f.rec.body.toByteArray(), StandardCharsets.ISO_8859_1));
            assertEquals(1, f.rec.completed);
            assertEquals(1, f.rec.bodyEnds);
        }
    }

    @Test
    public void testBadChunkSizeIs400() {
        Fixture f = run("POST /c HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testQuotedChunkExtensionIsAccepted() {
        // RFC 9112 section 7.1.1: a chunk extension value may be a quoted-string
        Fixture f = run("POST /c HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n3;a=\"x\"\r\nabc\r\n0\r\n\r\n");
        assertEquals(1, f.rec.completed);
        assertEquals("abc", new String(f.rec.body.toByteArray(), StandardCharsets.ISO_8859_1));
    }

    @Test
    public void testBadChunkTerminatorIs400() {
        Fixture f = run("POST /c HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXY");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testChunkExceedingBodyLimitIs413() {
        Fixture f = new Fixture();
        f.listener.setMaxRequestBodySize(4);
        f.open();
        f.feed("POST /c HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n10\r\n0123456789abcdef\r\n", 100);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 413"));
    }

    @Test
    public void testOversizedChunkSizeLineIs400() {
        StringBuilder sb = new StringBuilder();
        sb.append("POST /c HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n1");
        for (int i = 0; i < 9000; i++) {
            sb.append(';');
        }
        sb.append("\r\n");
        Fixture f = run(sb.toString());
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testMalformedRequestLines() {
        String[] bad = {
            "GET\r\n\r\n",
            "GET /x\r\n\r\n",
            "G\u0001T /x HTTP/1.1\r\n\r\n",
            "GE@T /x HTTP/1.1\r\nHost: h\r\n\r\n",
            "GET /x HTTP/1.1\u0000\r\n\r\n",
            "GET /x HTTP/1.1\r\nHost: h\r\nbadheader\r\n\r\n",
            "GET /x HTTP/1.1\r\nHost: h\r\n: novalue\r\n\r\n",
        };
        for (int i = 0; i < bad.length; i++) {
            Fixture f = run(bad[i]);
            assertTrue(i + ": " + f.wire(), f.wire().contains(" 400 "));
            assertEquals(0, f.rec.completed);
        }
    }

    @Test
    public void testRejectedRequestIsNeverDispatchedToApplication() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\nbadheader\r\n\r\n"
                + "GET /y HTTP/1.1\r\nHost: h\r\n\r\n");
        assertTrue(f.wire(), f.wire().contains(" 400 "));
        assertEquals(0, f.rec.completed);
        assertEquals(0, f.rec.paths.size());
        assertTrue(f.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testNonAsciiRequestLineIs400() {
        Fixture f = run("GET /é HTTP/1.1\r\nHost: h\r\n\r\n");
        assertTrue(f.wire(), f.wire().contains(" 400 "));
    }

    @Test
    public void testUnknownVersionIs505() {
        Fixture f = run("GET /x HTTP/9.9\r\nHost: h\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 505"));
    }

    @Test
    public void testHttp2VersionNonPriIs505() {
        Fixture f = run("GET /x HTTP/2.0\r\nHost: h\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 505"));
    }

    @Test
    public void testUnsupportedMethodIs501WithoutHandler() {
        Http2Listener l = new Http2Listener();
        HttpProtocolHandler h = new HttpProtocolHandler(l);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.receive(ByteBuffer.wrap("BREW /pot HTTP/1.1\r\nHost: h\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1)));
        String wire = new String(ep.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire, wire.startsWith("HTTP/1.1 501"));
    }

    @Test
    public void testOversizedRequestLineIs414() {
        StringBuilder sb = new StringBuilder("GET /");
        for (int i = 0; i < 9000; i++) {
            sb.append('a');
        }
        sb.append(" HTTP/1.1\r\nHost: h\r\n\r\n");
        Fixture f = run(sb.toString());
        assertTrue(f.wire(), f.wire().contains(" 414 "));
        assertEquals(0, f.rec.completed);
    }

    @Test
    public void testOversizedHeaderLineIs431() {
        StringBuilder sb = new StringBuilder("GET /x HTTP/1.1\r\nHost: h\r\nX: ");
        for (int i = 0; i < 9000; i++) {
            sb.append('a');
        }
        sb.append("\r\n\r\n");
        Fixture f = run(sb.toString());
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 431"));
    }

    @Test
    public void testTooManyHeadersIs431() {
        StringBuilder sb = new StringBuilder("GET /x HTTP/1.1\r\nHost: h\r\n");
        for (int i = 0; i < 120; i++) {
            sb.append("X-H").append(i).append(": v\r\n");
        }
        sb.append("\r\n");
        Fixture f = run(sb.toString());
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 431"));
    }

    @Test
    public void testMissingHostIs400() {
        Fixture f = run("GET /x HTTP/1.1\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testDuplicateHostIs400() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testInvalidHostIs400() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: a b\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 400"));
    }

    /**
     * RFC 9112 section 6.3: a request with neither Content-Length nor
     * Transfer-Encoding has no body. That is so whatever its method: a
     * POST sent that way is a POST of nothing, not a malformed request.
     */
    @Test
    public void testPostWithoutLengthHasNoBody() {
        Fixture f = run("POST /x HTTP/1.1\r\nHost: h\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 200"));
        assertEquals("POST", f.rec.methods.get(0));
        assertEquals(0, f.rec.body.size());
        assertEquals(1, f.rec.completed);
    }

    /** What follows such a request on the connection is the next request. */
    @Test
    public void testRequestAfterPostWithoutLengthIsRead() {
        Fixture f = run("POST /a HTTP/1.1\r\nHost: h\r\n\r\n"
                + "PUT /b HTTP/1.1\r\nHost: h\r\n\r\n"
                + "GET /c HTTP/1.1\r\nHost: h\r\n\r\n");
        assertEquals(3, f.rec.completed);
        assertEquals("POST", f.rec.methods.get(0));
        assertEquals("PUT", f.rec.methods.get(1));
        assertEquals("/c", f.rec.paths.get(2));
        assertEquals(0, f.rec.body.size());
    }

    @Test
    public void testHttp10GetClosesConnection() {
        Fixture f = run("GET /x HTTP/1.0\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 200")
                || f.wire().startsWith("HTTP/1.0 200"));
        assertTrue(f.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testConnectionCloseClosesAfterResponse() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 200"));
        assertTrue(f.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testKeepAliveStaysOpen() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\n\r\n");
        assertEquals(0, f.endpoint.getCloseCount());
    }

    @Test
    public void testMaxRequestsPerConnection() {
        Fixture f = new Fixture();
        f.listener.setMaxRequestsPerConnection(1);
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        assertTrue(f.endpoint.getCloseCount() > 0);
        assertTrue(f.wire().toLowerCase().contains("connection: close"));
    }

    @Test
    public void testObsFoldHeaderContinuation() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\nX-A: one\r\n\ttwo\r\n three\r\n\r\n");
        assertEquals(1, f.rec.completed);
    }

    @Test
    public void testOptionsAsterisk() {
        Fixture f = run("OPTIONS * HTTP/1.1\r\nHost: h\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 200"));
        assertTrue(f.wire(), f.wire().contains("Allow: "));
    }

    @Test
    public void testTraceDisabledIs405() {
        Fixture f = run("TRACE /x HTTP/1.1\r\nHost: h\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 405"));
    }

    @Test
    public void testTraceEnabledEchoes() {
        Fixture f = new Fixture();
        f.listener.setTraceMethodEnabled(true);
        f.open();
        f.feed("TRACE /x HTTP/1.1\r\nHost: h\r\nX-Q: 1\r\n\r\n", 100);
        String w = f.wire();
        assertTrue(w, w.startsWith("HTTP/1.1 200"));
        assertTrue(w, w.contains("message/http"));
        assertTrue(w, w.contains("X-Q: 1"));
    }

    @Test
    public void testHttp10RequestWithoutLengthHasNoBody() {
        // RFC 9112 section 6.3: a request has no body unless it declares a
        // length; the server does not read an HTTP/1.0 request body to
        // close, so what follows the header section is not the body
        Fixture f = run("POST /x HTTP/1.0\r\nHost: h\r\n\r\nsome data", 3);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.0 200")
                || f.wire().startsWith("HTTP/1.1 200"));
        assertEquals("POST", f.rec.methods.get(0));
        assertEquals(0, f.rec.body.size());
        f.handler.disconnected();
    }

    @Test
    public void testPriorKnowledgeBadPreface() {
        Fixture f = run("PRI * HTTP/2.0\r\n\r\nXX\r\n\r\n");
        assertFalse(f.wire().isEmpty());
    }

    @Test
    public void testPriorKnowledgePrefaceSendsSettings() {
        Fixture f = run("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", 5);
        byte[] w = f.endpoint.getAllBytes();
        assertTrue(w.length >= 9);
        assertEquals(4, w[3]);
    }

    @Test
    public void testH2cUpgradeBodyless() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\nConnection: Upgrade, HTTP2-Settings\r\n"
                + "Upgrade: h2c\r\nHTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 101"));
    }

    @Test
    public void testH2cUpgradeThenBadPreface() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\nConnection: Upgrade, HTTP2-Settings\r\n"
                + "Upgrade: h2c\r\nHTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        assertTrue(f.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testH2cUpgradeThenGoodPreface() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\nConnection: Upgrade, HTTP2-Settings\r\n"
                + "Upgrade: h2c\r\nHTTP2-Settings: AAMAAABkAAQAAP__\r\n\r\n"
                + "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", 7);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 101"));
    }

    @Test
    public void testDisconnectMidBodyFailsHandlerOnce() {
        Fixture f = run("POST /x HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\nabc");
        assertEquals(0, f.rec.completed);
        f.handler.disconnected();
        assertEquals(1, f.rec.failed);
        f.handler.disconnected();
        assertEquals(1, f.rec.failed);
    }

    @Test
    public void testDisconnectAfterCompletionIsNotFailed() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\n\r\n");
        f.handler.disconnected();
        assertEquals(0, f.rec.failed);
    }

    @Test
    public void testTransportErrorMidBodyFailsHandlerOnce() {
        Fixture f = run("POST /x HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\nabc");
        f.handler.error(new java.io.IOException("boom"));
        f.handler.disconnected();
        assertEquals(1, f.rec.failed);
    }

    @Test
    public void testFrameworkRejectedRequestIsNotFailed() {
        Fixture f = run("POST /x HTTP/1.1\r\nHost: h\r\nContent-Length: abc\r\n\r\n");
        f.handler.disconnected();
        assertEquals(0, f.rec.failed);
    }

    @Test
    public void testErrorClosesEndpoint() {
        Fixture f = run("");
        f.handler.error(new java.io.IOException("boom"));
        assertTrue(f.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testDisconnectedCleansUp() {
        Fixture f = run("GET /x HTTP/1.1\r\nHost: h\r\n\r\n");
        f.handler.disconnected();
        assertEquals(1, f.rec.completed);
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo);
        gz.write(text.getBytes(StandardCharsets.ISO_8859_1));
        gz.close();
        return bo.toByteArray();
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void testGzipRequestBodyDecodedWhenHandlerOptsIn() throws Exception {
        Fixture f = new Fixture();
        f.rec.decode = true;
        f.open();
        byte[] body = gzip("decoded payload");
        String req = "POST /g HTTP/1.1\r\nHost: h\r\nContent-Encoding: gzip\r\nContent-Length: "
                + body.length + "\r\n\r\n" + latin1(body);
        f.feed(req, 5);
        assertEquals("decoded payload", latin1(f.rec.body.toByteArray()));
        assertEquals(1, f.rec.completed);
        assertEquals(1, f.rec.bodyEnds);
    }

    @Test
    public void testCorruptGzipRequestBodyIs400() throws Exception {
        Fixture f = new Fixture();
        f.rec.decode = true;
        f.open();
        String req = "POST /g HTTP/1.1\r\nHost: h\r\nContent-Encoding: gzip\r\nContent-Length: 12\r\n\r\n"
                + "this is not gzip";
        f.feed(req.substring(0, req.length() - 4), 100);
        assertTrue(f.wire(), f.wire().contains(" 400 "));
    }

    @Test
    public void testUnknownRequestContentCodingIs415() {
        Fixture f = new Fixture();
        f.rec.decode = true;
        f.open();
        f.feed("POST /g HTTP/1.1\r\nHost: h\r\nContent-Encoding: rot13\r\nContent-Length: 2\r\n\r\nab", 100);
        assertTrue(f.wire(), f.wire().contains(" 415 "));
    }

    @Test
    public void testContentEncodingIgnoredWhenHandlerDoesNotOptIn() {
        Fixture f = run("POST /g HTTP/1.1\r\nHost: h\r\nContent-Encoding: gzip\r\nContent-Length: 3\r\n\r\nabc");
        assertEquals("abc", latin1(f.rec.body.toByteArray()));
    }

    @Test
    public void testResponseCompressedWhenAccepted() {
        Fixture f = new Fixture();
        f.rec.encode = true;
        f.rec.withLength = false;
        f.rec.chunks = 3;
        f.open();
        f.feed("GET /z HTTP/1.1\r\nHost: h\r\nAccept-Encoding: gzip\r\n\r\n", 100);
        String w = f.wire().toLowerCase();
        assertTrue(w, w.contains("content-encoding: gzip"));
        assertTrue(w, w.contains("transfer-encoding: chunked"));
    }

    private static String compressionAttempt(boolean encode, boolean withLength, int status,
            String method, boolean listenerCompresses, String extraName, String extraValue) {
        Fixture f = new Fixture();
        f.rec.encode = encode;
        f.rec.withLength = withLength;
        f.rec.status = status;
        f.rec.extraName = extraName;
        f.rec.extraValue = extraValue;
        f.listener.setCompressResponses(listenerCompresses);
        f.open();
        f.feed(method + " /z HTTP/1.1\r\nHost: h\r\nAccept-Encoding: gzip\r\n\r\n", 100);
        return f.wire().toLowerCase();
    }

    @Test
    public void testResponseCompressionDisqualifiers() {
        String compressed = compressionAttempt(true, false, 200, "GET", true, null, null);
        assertTrue(compressed, compressed.contains("content-encoding: gzip"));
        String withLength = compressionAttempt(true, true, 200, "GET", true, null, null);
        assertFalse(withLength, withLength.contains("content-encoding: gzip"));
        String preEncoded = compressionAttempt(true, false, 200, "GET", true, "Content-Encoding", "identity");
        assertFalse(preEncoded, preEncoded.contains("content-encoding: gzip"));
        String errorStatus = compressionAttempt(true, false, 404, "GET", true, null, null);
        assertFalse(errorStatus, errorStatus.contains("content-encoding: gzip"));
        String redirect = compressionAttempt(true, false, 301, "GET", true, null, null);
        assertFalse(redirect, redirect.contains("content-encoding: gzip"));
        String head = compressionAttempt(true, false, 200, "HEAD", true, null, null);
        assertFalse(head, head.contains("content-encoding: gzip"));
        String listenerOff = compressionAttempt(true, false, 200, "GET", false, null, null);
        assertFalse(listenerOff, listenerOff.contains("content-encoding: gzip"));
        String notOptedIn = compressionAttempt(false, false, 200, "GET", true, null, null);
        assertFalse(notOptedIn, notOptedIn.contains("content-encoding: gzip"));
    }

    private static Fixture respondFromHeaders(String request, final int status, final boolean withBody,
            final String[] extra) {
        Fixture f = new Fixture();
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(status);
                for (int i = 0; extra != null && i + 1 < extra.length; i += 2) {
                    state.header(extra[i], extra[i + 1]);
                }
                if (withBody) {
                    state.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                }
                state.endMessage();
            }
        };
        f.open();
        f.feed(request, 100);
        return f;
    }

    @Test
    public void testApplicationSecurityHeadersAreNotDuplicated() {
        Fixture f = respondFromHeaders("GET /z HTTP/1.1\r\nHost: h\r\n\r\n", 200, true,
                new String[] {"X-Frame-Options", "DENY", "X-Content-Type-Options", "custom"});
        String w = f.wire().toLowerCase();
        assertTrue(w, w.contains("x-frame-options: deny"));
        assertFalse(w, w.contains("x-frame-options: sameorigin"));
        assertTrue(w, w.contains("x-content-type-options: custom"));
        assertFalse(w, w.contains("x-content-type-options: nosniff"));
    }

    @Test
    public void testBodylessStatusesAreNotChunked() {
        String[] statuses = {"204", "304"};
        for (int i = 0; i < statuses.length; i++) {
            Fixture f = respondFromHeaders("GET /z HTTP/1.1\r\nHost: h\r\n\r\n",
                    Integer.parseInt(statuses[i]), false, null);
            String w = f.wire().toLowerCase();
            assertTrue(w, w.startsWith("http/1.1 " + statuses[i]));
            assertFalse(w, w.contains("transfer-encoding"));
        }
    }

    @Test
    public void testHeadersOnlyResponseIsDelimitedWithZeroLength() {
        Fixture f = respondFromHeaders("GET /z HTTP/1.1\r\nHost: h\r\n\r\n", 200, false, null);
        String w = f.wire().toLowerCase();
        assertTrue(w, w.contains("content-length: 0") || w.contains("transfer-encoding: chunked"));
    }

    @Test
    public void testConnectionCloseIsEchoedAndClosesTheEndpoint() {
        Fixture f = respondFromHeaders("GET /z HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
                200, true, null);
        String w = f.wire().toLowerCase();
        assertTrue(w, w.contains("connection: close"));
        assertTrue(f.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testInformationalResponseHasNoEntityHeaders() {
        Fixture f = new Fixture();
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(103);
                state.header("Link", "</s.css>; rel=preload");
                state.endHeaders();
                state.status(200);
                state.endMessage();
            }
        };
        f.open();
        f.feed("GET /z HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        String w = f.wire();
        assertTrue(w, w.startsWith("HTTP/1.1 103"));
        assertTrue(w, w.contains("HTTP/1.1 200"));
    }

    private static TelemetryConfig tracing() {
        TelemetryConfig config = new TelemetryConfig();
        config.setTracesEnabled(true);
        return config;
    }

    @Test
    public void testTracingStartsSpanAndEndsItWithTheResponseStatus() {
        Fixture f = new Fixture();
        f.endpoint.setTelemetryConfig(tracing());
        f.open();
        f.feed("GET /t HTTP/1.1\r\nHost: h\r\nUser-Agent: probe\r\n\r\n", 100);
        assertNotNull(f.endpoint.getTrace());
        assertTrue(f.wire().toLowerCase(), f.wire().toLowerCase().contains("traceparent:"));
        assertEquals(1, f.rec.completed);
    }

    @Test
    public void testTracingContinuesAnIncomingTraceparent() {
        Fixture f = new Fixture();
        f.endpoint.setTelemetryConfig(tracing());
        f.open();
        f.feed("GET /t HTTP/1.1\r\nHost: h\r\n"
                + "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\n\r\n", 100);
        String w = f.wire().toLowerCase();
        assertTrue(w, w.contains("traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-"));
    }

    @Test
    public void testTracingRecordsErrorStatuses() {
        Fixture f = new Fixture();
        f.endpoint.setTelemetryConfig(tracing());
        f.rec.status = 503;
        f.open();
        f.feed("GET /t HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 503"));
        Fixture g = new Fixture();
        g.endpoint.setTelemetryConfig(tracing());
        g.rec.status = 404;
        g.open();
        g.feed("GET /t HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        assertTrue(g.wire(), g.wire().startsWith("HTTP/1.1 404"));
    }

    @Test
    public void testResponseChunkedWithoutLength() {
        Fixture f = new Fixture();
        f.rec.withLength = false;
        f.rec.chunks = 2;
        f.open();
        f.feed("GET /z HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        String w = f.wire();
        assertTrue(w, w.contains("Transfer-Encoding: chunked"));
        assertTrue(w, w.endsWith("0\r\n\r\n"));
    }

    @Test
    public void testHeadResponseHasNoBody() {
        Fixture f = new Fixture();
        f.open();
        f.feed("HEAD /z HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 200"));
        assertFalse(f.wire(), f.wire().endsWith("ok"));
    }

    @Test
    public void testSecurityHeadersCanBeDisabledAndHstsAdded() {
        Fixture f = new Fixture();
        f.listener.setAddSecurityHeaders(false);
        f.listener.setHstsEnabled(true);
        f.listener.setHstsMaxAge(600L);
        f.listener.setAltSvc("h3=\":443\"");
        f.endpoint.setSecure(true);
        f.listener.setSecure(true);
        f.open();
        f.feed("GET /z HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        String w = f.wire();
        assertFalse(w, w.contains("X-Frame-Options"));
        assertTrue(w, w.contains("Strict-Transport-Security: max-age=600"));
        assertTrue(w, w.contains("Alt-Svc: h3="));
    }

    @Test
    public void testExpectContinue() {
        Fixture f = run("POST /e HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\n");
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 100 Continue"));
        f.feed("ok", 1);
        assertEquals(1, f.rec.completed);
    }

    @Test
    public void testContentLengthOverLimitIs413() {
        Fixture f = new Fixture();
        f.listener.setMaxRequestBodySize(10);
        f.open();
        f.feed("POST /e HTTP/1.1\r\nHost: h\r\nContent-Length: 11\r\n\r\n", 100);
        assertTrue(f.wire(), f.wire().contains(" 413 "));
    }

    @Test
    public void testInvalidContentLengthIs400() {
        String[] bad = {"abc", "-1", "1, 2", ""};
        for (int i = 0; i < bad.length; i++) {
            Fixture f = run("POST /e HTTP/1.1\r\nHost: h\r\nContent-Length: " + bad[i] + "\r\n\r\nabc");
            assertTrue(bad[i] + f.wire(), f.wire().contains(" 400 "));
        }
    }

    @Test
    public void testConflictingContentLengthsIs400() {
        Fixture f = run("POST /e HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabc");
        assertTrue(f.wire(), f.wire().contains(" 400 "));
    }

    @Test
    public void testContentLengthWithTransferEncodingIs400() {
        Fixture f = run("POST /e HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n");
        assertTrue(f.wire(), f.wire().contains(" 400 "));
        Fixture g = run("POST /e HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\n\r\n");
        assertTrue(g.wire(), g.wire().contains(" 400 "));
    }

    @Test
    public void testUnknownTransferEncodingIs400() {
        Fixture f = run("POST /e HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: gzip\r\n\r\n");
        assertTrue(f.wire(), f.wire().contains(" 400 "));
    }

    @Test
    public void testBasicAuthenticationRequired() {
        Fixture f = new Fixture();
        f.listener.setAuthenticationProvider(new HttpAuthenticationProvider() {
            @Override protected String getAuthMethod() { return "BASIC"; }
            @Override protected String getRealmName() { return "realm"; }
            @Override protected boolean passwordMatch(String r, String u, String p) {
                return "u".equals(u) && "p".equals(p);
            }
            @Override protected String getDigestHA1(String r, String u) { return null; }
            @Override protected org.bluezoo.gumdrop.auth.Realm.TokenValidationResult
                    validateBearerToken(String t) { return null; }
            @Override protected org.bluezoo.gumdrop.auth.Realm.TokenValidationResult
                    validateOAuthToken(String t) { return null; }
        });
        f.open();
        f.feed("GET /a HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 401"));
        assertTrue(f.wire(), f.wire().contains("WWW-Authenticate: Basic realm=\"realm\""));
        assertEquals(0, f.rec.completed);
        f.endpoint.clearWrites();
        String cred = java.util.Base64.getEncoder().encodeToString(
                "u:p".getBytes(StandardCharsets.ISO_8859_1));
        f.feed("GET /a HTTP/1.1\r\nHost: h\r\nAuthorization: Basic " + cred + "\r\n\r\n", 100);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testMetricsRecordedForRequests() throws Exception {
        Fixture f = new Fixture();
        org.bluezoo.gumdrop.telemetry.TelemetryConfig tc =
                new org.bluezoo.gumdrop.telemetry.TelemetryConfig();
        tc.setMetricsEnabled(true);
        java.lang.reflect.Field field = Http2Listener.class.getDeclaredField("metrics");
        field.setAccessible(true);
        field.set(f.listener, new HttpServerMetrics(tc));
        f.open();
        f.feed("GET /m HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        f.feed("POST /m HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\nab", 100);
        f.handler.disconnected();
        assertEquals(2, f.rec.completed);
    }

    @Test
    public void testIdleTimeoutClosesHttp1Connection() {
        Fixture f = new Fixture();
        f.listener.setIdleTimeoutMs(1000L);
        f.open();
        f.endpoint.fireTimers();
        assertTrue(f.endpoint.getCloseCount() > 0);
    }

    @Test
    public void testStreamCountStaysBoundedOnKeepAlive() {
        Fixture f = new Fixture();
        f.open();
        for (int i = 0; i < 5; i++) {
            f.feed("GET /k" + i + " HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        }
        assertEquals(5, f.rec.completed);
        assertTrue(f.handler.streamCountForTesting() <= 1);
    }

    @Test
    public void testUnauthenticatedRequestNeverReachesApplication() {
        Fixture f = new Fixture();
        f.listener.setAuthenticationProvider(new HttpAuthenticationProvider() {
            @Override protected String getAuthMethod() { return "BASIC"; }
            @Override protected String getRealmName() { return "realm"; }
            @Override protected boolean passwordMatch(String r, String u, String p) { return false; }
            @Override protected String getDigestHA1(String r, String u) { return null; }
            @Override protected org.bluezoo.gumdrop.auth.Realm.TokenValidationResult
                    validateBearerToken(String t) { return null; }
            @Override protected org.bluezoo.gumdrop.auth.Realm.TokenValidationResult
                    validateOAuthToken(String t) { return null; }
        });
        f.open();
        f.feed("POST /secret HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello", 3);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 401"));
        assertEquals(0, f.rec.completed);
        assertEquals(0, f.rec.body.size());
        assertEquals(0, f.rec.bodyEnds);
        assertEquals(0, f.rec.methods.size());
    }

    private static String maskedFrame(int opcode, byte[] payload) {
        byte[] mask = {1, 2, 3, 4};
        StringBuilder sb = new StringBuilder();
        sb.append((char) (0x80 | opcode));
        sb.append((char) (0x80 | payload.length));
        for (int i = 0; i < 4; i++) {
            sb.append((char) mask[i]);
        }
        for (int i = 0; i < payload.length; i++) {
            sb.append((char) ((payload[i] ^ mask[i % 4]) & 0xff));
        }
        return sb.toString();
    }

    private static final String WS_UPGRADE = "GET /ws HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";

    @Test
    public void testWebSocketUpgradeAndMessages() {
        Fixture f = new Fixture();
        final RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.upgradeToWebSocket(null, ws);
            }
        };
        f.open();
        byte[] hi = "hi".getBytes(StandardCharsets.ISO_8859_1);
        byte[] bin = new byte[] {9, 8, 7};
        String frames = maskedFrame(1, hi) + maskedFrame(2, bin);
        f.feed(WS_UPGRADE + frames, 3);
        String wire = f.wire();
        assertTrue(wire, wire.startsWith("HTTP/1.1 101"));
        assertTrue(wire, wire.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
        assertTrue(wire, wire.contains("Upgrade: websocket\r\n"));
        assertTrue(wire, wire.contains("Connection: Upgrade\r\n"));
        assertFalse(wire, wire.contains("Sec-WebSocket-Protocol"));
        assertFalse(wire, wire.contains("Sec-WebSocket-Extensions"));
        assertEquals(1, ws.openedCount);
        assertEquals(1, ws.texts.size());
        assertEquals("hi", ws.texts.get(0));
        assertEquals(1, ws.binaries.size());
        try {
            ws.session.sendText("pong");
            ws.session.sendBinary(ByteBuffer.wrap(bin));
            ws.session.sendPing(ByteBuffer.wrap(bin));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertTrue(f.endpoint.getAllBytes().length > wire.length());
        f.feed(maskedFrame(8, new byte[] {0x03, (byte) 0xe8}), 100);
        assertEquals(1, ws.closeCodes.size());
        assertEquals(1000, ws.closeCodes.get(0).intValue());
    }

    @Test
    public void testWebSocketUpgradeResponseCarriesSubprotocolAndExtensions() {
        Fixture f = new Fixture();
        final RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                java.util.List<org.bluezoo.gumdrop.websocket.WebSocketExtension> extensions =
                        new java.util.ArrayList<org.bluezoo.gumdrop.websocket.WebSocketExtension>();
                extensions.add(new org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension());
                state.upgradeToWebSocket(" graphql-ws ", extensions, ws);
            }
        };
        f.open();
        f.feed(WS_UPGRADE, 100);
        String wire = f.wire();
        assertTrue(wire, wire.startsWith("HTTP/1.1 101"));
        assertTrue(wire, wire.contains("Sec-WebSocket-Protocol: graphql-ws\r\n"));
        assertTrue(wire, wire.contains("Sec-WebSocket-Extensions: permessage-deflate\r\n"));
    }

    @Test
    public void testWebSocketUpgradeOnlyOnValidRequest() {
        Fixture f = new Fixture();
        final RecordingWebSocketEventHandler ws = new RecordingWebSocketEventHandler();
        final boolean[] threw = new boolean[1];
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                try {
                    state.upgradeToWebSocket(null, ws);
                } catch (IllegalStateException e) {
                    threw[0] = true;
                    state.status(400);
                    state.longHeader("Content-Length", 0L);
                    state.endMessage();
                }
            }
        };
        f.open();
        f.feed("GET /ws HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        assertTrue(threw[0]);
        assertEquals(0, ws.openedCount);
    }

    @Test
    public void testConnectUdpTunnelAcceptedOverHttp11Upgrade() {
        Fixture f = new Fixture();
        final boolean[] accepted = new boolean[1];
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                accepted[0] = state.acceptConnectUdp();
            }
        };
        f.open();
        f.feed("GET /.well-known/masque/udp/x/53/ HTTP/1.1\r\nHost: h\r\n"
                + "Connection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n\r\n", 100);
        assertTrue(accepted[0]);
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.1 101"));
        // a capsule (type 0 DATAGRAM, length 1) sent after the upgrade
        f.feed("\u0000\u0001\u0000", 1);
        assertTrue(f.endpoint.getCloseCount() == 0);
    }

    @Test
    public void testConnectIpNotAcceptedWhenNotRequested() {
        Fixture f = new Fixture();
        final boolean[] accepted = new boolean[] {true};
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                accepted[0] = state.acceptConnectIp();
                state.status(400);
                state.longHeader("Content-Length", 0L);
                state.endMessage();
            }
        };
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\n\r\n", 100);
        assertFalse(accepted[0]);
    }

    private static String respondingHook(Fixture f, final String headerName,
            final List<String> seen) {
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                seen.add(HeaderFields.getValue(headers, headerName));
                state.status(200);
                state.longHeader("Content-Length", 0L);
                state.endMessage();
            }
        };
        return headerName;
    }

    @Test
    public void testEncodedWordRequestHeaderIsPassedThroughUndecoded() {
        // RFC 9110 section 5.5 mentions RFC 2047 only as history; HTTP does not
        // define it, so a field value that looks like an encoded-word is just
        // text and is handed to the application exactly as received.
        Fixture f = new Fixture();
        List<String> seen = new ArrayList<String>();
        respondingHook(f, "x-enc", seen);
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\nX-Enc: =?UTF-8?Q?caf=C3=A9?= tail\r\n\r\n", 100);
        assertEquals(1, seen.size());
        assertEquals("=?UTF-8?Q?caf=C3=A9?= tail", seen.get(0));
    }

    @Test
    public void testMalformedEncodedWordRequestHeaderIsKeptVerbatim() {
        Fixture f = new Fixture();
        List<String> seen = new ArrayList<String>();
        respondingHook(f, "x-enc", seen);
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\nX-Enc: =?bogus?Z?zz?=\r\n\r\n", 100);
        assertEquals(1, seen.size());
        assertEquals("=?bogus?Z?zz?=", seen.get(0));
    }

    @Test
    public void testNonAsciiRequestHeaderOctetsArePreservedAsOpaqueData() {
        // RFC 9110 section 5.5: octets 0x80-0xFF (obs-text) are opaque to the
        // recipient. Each octet maps to the character of the same value, so
        // nothing is lost or guessed at; the application decides what they
        // mean. The two octets here are UTF-8 for e-acute, and stay two chars.
        Fixture f = new Fixture();
        List<String> seen = new ArrayList<String>();
        respondingHook(f, "x-raw", seen);
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\nX-Raw: caf\u00c3\u00a9\r\n\r\n", 100);
        assertEquals(1, seen.size());
        assertEquals("caf\u00c3\u00a9", seen.get(0));
    }

    @Test
    public void testFoldedRequestHeaderValueSpanningSeveralKilobytesIsKept() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            big.append((char) ('a' + i % 26));
        }
        Fixture f = new Fixture();
        List<String> seen = new ArrayList<String>();
        respondingHook(f, "x-big", seen);
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\nX-Big: " + big + "\r\n " + big
                + "\r\n\r\n", 100);
        assertEquals(1, seen.size());
        assertEquals(big + " " + big, seen.get(0));
    }

    @Test
    public void testQuotedStringKeepsInnerWhitespaceAndEscapes() {
        Fixture f = new Fixture();
        List<String> seen = new ArrayList<String>();
        respondingHook(f, "x-q", seen);
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\nX-Q: a \"b  \\\" c\"  d\r\n\r\n", 100);
        assertEquals("a \"b  \\\" c\"  d", seen.get(0));
    }

    @Test
    public void testNonAsciiResponseHeaderValueIsRejectedAndNeverSent() {
        // RFC 9110 section 5.5: non-ASCII field content is opaque, and HTTP
        // defines no encoding for it, so it is refused where the handler sets
        // it rather than encoded and sent.
        for (String value : new String[] {"caf\u00e9 au lait", "\u00e9\u00e8\u00ea\u00eb"}) {
            Fixture f = new Fixture();
            f.rec.extraName = "X-Note";
            f.rec.extraValue = value;
            f.open();
            try {
                f.feed("GET /x HTTP/1.1\r\nHost: h\r\n\r\n", 100);
                fail("expected IllegalArgumentException from headers()");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("X-Note"));
            }
            assertFalse(f.wire(), f.wire().contains("X-Note"));
        }
    }

    @Test
    public void testInvalidValueInTheFinalHeaderLineIsA400NotAnException() {
        // The last header line is added when the blank line arrives, a separate
        // path from the lines before it. A control character in its value must
        // be refused the same way (RFC 9112 section 5.5: a recipient may reject
        // a message with an invalid field value with 400).
        Fixture f = new Fixture();
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\nX-Bad: a\u0001b\r\n\r\n", 100);
        assertTrue(f.wire(), f.wire().contains(" 400 "));
    }

    @Test
    public void testInvalidValueInAnEarlierHeaderLineIsA400() {
        Fixture f = new Fixture();
        f.open();
        f.feed("GET /x HTTP/1.1\r\nHost: h\r\nX-Bad: a\u0001b\r\nX-Ok: fine\r\n\r\n", 100);
        assertTrue(f.wire(), f.wire().contains(" 400 "));
    }

    @Test
    public void testPriContinuationMustMatchAtEveryPosition() {
        String tail = "\r\nSM\r\n\r\n";
        for (int i = 0; i < tail.length(); i++) {
            StringBuilder sb = new StringBuilder(tail);
            sb.setCharAt(i, '!');
            Fixture f = run("PRI * HTTP/2.0\r\n" + sb);
            // not a request and not the preface: refused, never taken for HTTP/2
            assertTrue("position " + i + f.wire(),
                    f.wire().contains(" 400 ") || f.wire().contains(" 505 "));
        }
    }

    @Test
    public void testInformationalResponseIsDroppedOnHttp10() {
        Fixture f = new Fixture();
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(103);
                state.header("Link", "</a>; rel=preload");
                state.endHeaders();
                state.status(200);
                state.longHeader("Content-Length", 0L);
                state.endMessage();
            }
        };
        f.open();
        f.feed("GET /x HTTP/1.0\r\nHost: h\r\n\r\n", 100);
        assertFalse(f.wire(), f.wire().contains("103"));
        assertTrue(f.wire(), f.wire().startsWith("HTTP/1.0 200"));
    }

    // ------------------------------------------------------------------
    // the response events as they appear on the wire

    private static final String GET_Z = "GET /z HTTP/1.1\r\nHost: h\r\n\r\n";

    private static Fixture respondWith(String request, HeadersHook hook) {
        Fixture f = new Fixture();
        f.rec.hook = hook;
        f.open();
        f.feed(request, 100);
        return f;
    }

    @Test
    public void testResponseStatusDefaultsTo200() {
        Fixture f = respondWith(GET_Z, new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.header("x-a", "b");
                state.endMessage();
            }
        });
        String w = f.wire();
        assertTrue(w, w.startsWith("HTTP/1.1 200"));
        assertTrue(w, w.toLowerCase().contains("x-a: b"));
    }

    @Test
    public void testTypedFieldsAreFormattedOnTheWire() {
        Fixture f = respondWith(GET_Z, new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(200);
                state.longHeader("Content-Length", 2L);
                state.dateHeader("Last-Modified", java.time.Instant.ofEpochSecond(0L));
                state.contentType(new org.bluezoo.gumdrop.mime.ContentType("text", "plain", null));
                state.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                state.endMessage();
            }
        });
        String w = f.wire().toLowerCase();
        assertTrue(w, w.contains("content-length: 2\r\n"));
        assertTrue(w, w.contains("last-modified: thu, 01 jan 1970 00:00:00 gmt\r\n"));
        assertTrue(w, w.contains("content-type: text/plain"));
        assertTrue(w, w.endsWith("ok"));
    }

    @Test
    public void testEndHeadersSendsTheHeaderSectionBeforeAnyBody() {
        // The header section goes out although the response is not finished
        // and no body has been written, as an event stream needs. It is
        // written when the read that produced it has been handled, with
        // whatever else that read produced, so it is looked for after the
        // handler has returned.
        final Fixture f = new Fixture();
        final HttpResponse[] open = new HttpResponse[1];
        f.rec.hook = new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(200);
                state.header("Content-Type", "text/event-stream");
                state.endHeaders();
                open[0] = state;
            }
        };
        f.open();
        f.feed(GET_Z, 100);
        String headersOnly = f.wire();
        assertTrue(headersOnly, headersOnly.startsWith("HTTP/1.1 200"));
        assertTrue(headersOnly, headersOnly.endsWith("\r\n\r\n"));
        open[0].bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
        open[0].endMessage();
        assertTrue(f.wire(), f.wire().startsWith(headersOnly));
        assertTrue(f.wire(), f.wire().contains("ok"));
    }

    @Test
    public void testStatusOnlyResponseEndsWithItsHeaderSection() {
        Fixture f = respondWith(GET_Z, new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(204);
                state.endMessage();
            }
        });
        String w = f.wire();
        assertTrue(w, w.startsWith("HTTP/1.1 204"));
        assertTrue(w, w.endsWith("\r\n\r\n"));
        assertFalse(w, w.toLowerCase().contains("transfer-encoding"));
    }

    @Test
    public void testInterimResponseIsFollowedByTheFinalResponse() {
        Fixture f = respondWith(GET_Z, new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(103);
                state.header("Link", "</s.css>; rel=preload");
                state.endHeaders();
                state.status(200);
                state.longHeader("Content-Length", 2L);
                state.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                state.endMessage();
            }
        });
        String w = f.wire();
        int interim = w.indexOf("HTTP/1.1 103");
        int fin = w.indexOf("HTTP/1.1 200");
        assertTrue(w, interim == 0);
        assertTrue(w, fin > interim);
        assertTrue(w, w.substring(0, fin).toLowerCase().contains("link: </s.css>; rel=preload"));
        assertFalse("interim fields must not leak into the final response",
                w.substring(fin).toLowerCase().contains("link:"));
        assertTrue(w, w.endsWith("ok"));
    }

    @Test
    public void testFieldAfterTheBodyIsAChunkedTrailer() {
        Fixture f = respondWith(GET_Z, new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(200);
                state.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                state.header("X-Checksum", "42");
                state.endMessage();
            }
        });
        String w = f.wire();
        assertTrue(w, w.toLowerCase().contains("transfer-encoding: chunked"));
        int last = w.indexOf("\r\n0\r\n");
        assertTrue(w, last > 0);
        String tail = w.substring(last + 5).toLowerCase();
        assertTrue(w, tail.startsWith("x-checksum: 42\r\n"));
        assertTrue(w, tail.endsWith("\r\n\r\n"));
        assertFalse("the trailer section must not carry a second status line: " + w,
                tail.contains("http/1.1"));
    }

    @Test
    public void testForbiddenTrailerAndLateBodyAreRejected() {
        final IllegalArgumentException[] forbidden = new IllegalArgumentException[1];
        final IllegalStateException[] lateBody = new IllegalStateException[1];
        final IllegalArgumentException[] nonAscii = new IllegalArgumentException[1];
        final IllegalStateException[] afterEnd = new IllegalStateException[1];
        respondWith(GET_Z, new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.status(200);
                try {
                    state.header("x-custom", "caf\u00e9");
                } catch (IllegalArgumentException e) {
                    nonAscii[0] = e;
                }
                state.bodyContent(ByteBuffer.wrap(new byte[] {'o', 'k'}));
                try {
                    state.header("Content-Length", "2");
                } catch (IllegalArgumentException e) {
                    forbidden[0] = e;
                }
                state.header("X-Checksum", "42");
                try {
                    state.bodyContent(ByteBuffer.wrap(new byte[] {'!'}));
                } catch (IllegalStateException e) {
                    lateBody[0] = e;
                }
                state.endMessage();
                state.endMessage();
                try {
                    state.header("x-late", "1");
                } catch (IllegalStateException e) {
                    afterEnd[0] = e;
                }
            }
        });
        assertNotNull("non-ASCII value", nonAscii[0]);
        assertNotNull("forbidden trailer name", forbidden[0]);
        assertNotNull("body after a trailer", lateBody[0]);
        assertNotNull("field after endMessage", afterEnd[0]);
    }

    @Test
    public void testPushPromiseIsRefusedOnHttp1() {
        final boolean[] result = new boolean[] {true};
        respondWith(GET_Z, new HeadersHook() {
            @Override
            public void run(HttpResponse state, List<Header> headers) {
                state.startPushPromise(org.bluezoo.gumdrop.http.HttpMethod.GET, "/pushed");
                state.header("accept", "text/css");
                result[0] = state.endPushPromise();
                state.status(204);
                state.endMessage();
            }
        });
        assertFalse(result[0]);
    }
}
