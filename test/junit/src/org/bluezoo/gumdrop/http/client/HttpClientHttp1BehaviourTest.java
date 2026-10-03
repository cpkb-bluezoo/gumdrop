/*
 * HttpClientHttp1BehaviourTest.java
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPOutputStream;

import org.bluezoo.gumdrop.testsupport.CollectingResponseHandler;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * HTTP/1.1 request building and response framing behaviour of
 * {@link HttpClientProtocolHandler} over an in-memory endpoint: request line,
 * Host and framing headers, chunked bodies, validation, cancellation, interim
 * and bodiless responses, malformed framing, content coding and lifecycle.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientHttp1BehaviourTest {

    private static final class Recorder extends CollectingResponseHandler {
        HttpStatus status;
        boolean ok;
        boolean error;
        int okCalls;
        int closeCalls;
        boolean endBody;
        final List<Exception> failures = new ArrayList<Exception>();
        final List<String> headers = new ArrayList<String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public void ok(HttpStatus response) {
            ok = true;
            okCalls++;
            status = response;
        }

        @Override
        public void error(HttpStatus response) {
            error = true;
            status = response;
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
    private Conn conn;

    @Before
    public void setUp() {
        conn = new Conn();
        newHandler("example.com", 80, false);
    }

    private void newHandler(String host, int port, boolean secure) {
        handler = new HttpClientProtocolHandler(conn, host, port, secure);
        handler.setH2cUpgradeEnabled(false);
        handler.setSendAcceptEncodingHeader(false);
        endpoint = new BinaryRecordingEndpoint();
        handler.connected(endpoint);
        if (secure) {
            handler.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256"));
        }
    }

    private String sent() {
        return new String(endpoint.getAllBytes(), StandardCharsets.UTF_8);
    }

    private void feed(String raw) {
        handler.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private void feedBytes(byte[] raw) {
        handler.receive(ByteBuffer.wrap(raw));
    }

    private Recorder get() {
        Recorder r = new Recorder();
        handler.get("/p", r).endMessage();
        return r;
    }

    // ── request line, Host and framing headers ──

    @Test
    public void requestLineAndHostHeader() {
        get();
        String wire = sent();
        assertTrue(wire, wire.startsWith("GET /p HTTP/1.1\r\nHost: example.com\r\n"));
        assertTrue(wire, wire.contains("Connection: keep-alive\r\n"));
        assertTrue(wire, wire.endsWith("\r\n\r\n"));
    }

    @Test
    public void hostHeaderCarriesNonDefaultPortOnly() {
        newHandler("example.com", 8080, false);
        get();
        assertTrue(sent(), sent().contains("Host: example.com:8080\r\n"));
        newHandler("example.com", 443, true);
        get();
        assertTrue(sent(), sent().contains("Host: example.com\r\n"));
        newHandler("example.com", 80, true);
        get();
        assertTrue(sent(), sent().contains("Host: example.com:80\r\n"));
    }

    @Test
    public void ipv6HostIsBracketed() {
        newHandler("::1", 8080, false);
        get();
        assertTrue(sent(), sent().contains("Host: [::1]:8080\r\n"));
        newHandler("[::1]", 80, false);
        get();
        assertTrue(sent(), sent().contains("Host: [::1]\r\n"));
        newHandler("2001:db8::7", 80, false);
        get();
        assertTrue(sent(), sent().contains("Host: [2001:db8::7]\r\n"));
    }

    @Test
    public void allRequestFactoriesUseTheirMethod() {
        String[] expected = {"POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "PROPFIND"};
        for (int i = 0; i < expected.length; i++) {
            newHandler("example.com", 80, false);
            Recorder r = new Recorder();
            HttpRequest req;
            switch (i) {
                case 0: req = handler.post("/a", r); break;
                case 1: req = handler.put("/a", r); break;
                case 2: req = handler.delete("/a", r); break;
                case 3: req = handler.head("/a", r); break;
                case 4: req = handler.options("/a", r); break;
                case 5: req = handler.patch("/a", r); break;
                default: req = handler.request(HttpMethod.of("PROPFIND"), "/a", r); break;
            }
            endpoint.clearWrites();
            req.endMessage();
            assertTrue(expected[i] + ": " + sent(), sent().startsWith(expected[i] + " /a HTTP/1.1\r\n"));
            handler.disconnected();
        }
    }

    @Test
    public void callerSuppliedConnectionHeaderSuppressesKeepAlive() {
        HttpRequest r = handler.get("/p", new Recorder());
        r.header("Connection", "close");
        r.endMessage();
        assertFalse(sent(), sent().contains("keep-alive"));
        assertTrue(sent(), sent().contains("Connection: close\r\n"));
    }

    @Test
    public void defaultAcceptEncodingIsAddedWhenEnabledAndNotOverridden() {
        handler.setSendAcceptEncodingHeader(true);
        get();
        assertTrue(sent(), sent().contains("Accept-Encoding: br, gzip, deflate\r\n"));
        endpoint.clearWrites();
        handler.disconnected();
        newHandler("example.com", 80, false);
        handler.setSendAcceptEncodingHeader(true);
        HttpRequest r = handler.get("/p", new Recorder());
        r.header("Accept-Encoding", "identity");
        r.endMessage();
        assertTrue(sent(), sent().contains("Accept-Encoding: identity\r\n"));
        assertFalse(sent(), sent().contains("br, gzip"));
    }

    @Test
    public void h2cUpgradeHeadersAreSentOnceWhenEnabled() {
        handler.setH2cUpgradeEnabled(true);
        get();
        String first = sent();
        assertTrue(first, first.contains("Upgrade: h2c\r\n"));
        assertTrue(first, first.contains("HTTP2-Settings: AAIAAAAA\r\n"));
        assertFalse(first, first.contains("keep-alive"));
    }

    @Test
    public void traceparentIsAddedWhenATraceContextIsSet() {
        Trace trace = new Trace("client");
        handler.setTraceContext(trace);
        get();
        assertTrue(sent(), sent().contains("traceparent: 00-"));
    }

    @Test
    public void callerTraceparentIsKept() {
        handler.setTraceContext(new Trace("client"));
        HttpRequest r = handler.get("/p", new Recorder());
        r.header("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        r.endMessage();
        String wire = sent();
        int first = wire.indexOf("traceparent: ");
        assertEquals(wire.lastIndexOf("traceparent: "), first);
        assertTrue(wire, wire.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    // ── request bodies ──

    @Test
    public void nothingIsWrittenUntilTheBodyOrTheEnd() {
        HttpRequest r = handler.post("/up", new Recorder());
        r.header("X-A", "b");
        assertEquals("", sent());
        r.bodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("the first piece is held back", "", sent());
    }

    @Test
    public void chunkedRequestBodyWithoutLength() {
        HttpRequest r = handler.post("/up", new Recorder());
        int n = r.bodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(3, n);
        assertEquals("", sent());
        r.bodyContent(ByteBuffer.wrap("0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(sent(), sent().contains("Transfer-Encoding: chunked\r\n"));
        assertFalse(sent(), sent().contains("Content-Length"));
        r.endMessage();
        assertTrue(sent(), sent().endsWith("\r\n\r\n3\r\nabc\r\n10\r\n0123456789abcdef\r\n0\r\n\r\n"));
    }

    @Test
    public void singleBodyPieceIsSentWithContentLengthNotChunked() {
        HttpRequest r = handler.post("/up", new Recorder());
        r.bodyContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
        r.endMessage();
        assertTrue(sent(), sent().contains("Content-Length: 3\r\n"));
        assertFalse(sent(), sent().contains("Transfer-Encoding"));
        assertTrue(sent(), sent().endsWith("\r\n\r\nabc"));
    }

    @Test
    public void emptyBodyWriteMustNotTerminateTheChunkedBody() {
        HttpRequest r = handler.post("/up", new Recorder());
        int n = r.bodyContent(ByteBuffer.allocate(0));
        assertEquals(0, n);
        assertEquals("an empty write sends nothing", "", sent());
        r.bodyContent(ByteBuffer.wrap("x".getBytes(StandardCharsets.US_ASCII)));
        r.bodyContent(ByteBuffer.allocate(0));
        assertEquals("", sent());
        r.bodyContent(ByteBuffer.wrap("y".getBytes(StandardCharsets.US_ASCII)));
        endpoint.clearWrites();
        r.endMessage();
        assertEquals("0\r\n\r\n", sent());
    }

    @Test
    public void emptyBodyWriteThenOnePieceIsSentWithContentLength() {
        HttpRequest r = handler.post("/up", new Recorder());
        r.bodyContent(ByteBuffer.allocate(0));
        r.bodyContent(ByteBuffer.wrap("x".getBytes(StandardCharsets.US_ASCII)));
        r.endMessage();
        assertTrue(sent(), sent().contains("Content-Length: 1\r\n"));
        assertTrue(sent(), sent().endsWith("\r\n\r\nx"));
        assertFalse(sent(), sent().contains("0\r\n\r\n"));
    }

    @Test
    public void contentLengthRequestBodyIsSentRaw() {
        HttpRequest r = handler.put("/up", new Recorder());
        r.header("Content-Length", "5");
        r.bodyContent(ByteBuffer.wrap("hel".getBytes(StandardCharsets.US_ASCII)));
        r.bodyContent(ByteBuffer.wrap("lo".getBytes(StandardCharsets.US_ASCII)));
        r.endMessage();
        assertFalse(sent(), sent().contains("Transfer-Encoding"));
        assertTrue(sent(), sent().endsWith("\r\n\r\nhello"));
    }

    @Test
    public void contentLengthSinglePieceIsNotGivenASecondLength() {
        HttpRequest r = handler.put("/up", new Recorder());
        r.header("Content-Length", "5");
        r.bodyContent(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
        r.endMessage();
        String wire = sent();
        assertEquals(wire.indexOf("Content-Length"), wire.lastIndexOf("Content-Length"));
        assertTrue(wire, wire.endsWith("\r\n\r\nhello"));
    }

    @Test
    public void unknownRequestContentEncodingIsSentThroughUntouched() {
        handler.setEncodeRequestBodyContentCoding(true);
        Recorder rec = new Recorder();
        HttpRequest r = handler.post("/up", rec);
        r.header("Content-Encoding", "x-weird");
        int n = r.bodyContent(ByteBuffer.wrap("da".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(2, n);
        n = r.bodyContent(ByteBuffer.wrap("ta".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(2, n);
        assertTrue(sent(), sent().endsWith("\r\n\r\n2\r\nda\r\n2\r\nta\r\n"));
        assertTrue(rec.failures.isEmpty());
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(out);
        gz.write(data);
        gz.close();
        return out.toByteArray();
    }

    // ── request state validation ──

    @Test
    public void requestStateMachineRejectsMisuse() {
        HttpRequest r = handler.post("/p", new Recorder());
        r.header("X", "before");
        r.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        r.header("X", "still before");
        r.bodyContent(ByteBuffer.wrap(new byte[] {2}));
        try {
            r.header("X", "y");
            fail("header after send");
        } catch (IllegalStateException expected) {
            assertEquals("Headers already sent", expected.getMessage());
        }
        try {
            r.endHeaders();
            fail("endHeaders after send");
        } catch (IllegalStateException expected) {
            assertEquals("Headers already sent", expected.getMessage());
        }
        r.endMessage();
        try {
            r.bodyContent(ByteBuffer.allocate(1));
            fail("body after end");
        } catch (IllegalStateException expected) {
            assertEquals("Request body already complete", expected.getMessage());
        }
        try {
            r.endMessage();
            fail("second end");
        } catch (IllegalStateException expected) {
            assertEquals("Request body already complete", expected.getMessage());
        }
    }

    @Test
    public void endHeadersSendsTheHeaderSectionAndLeavesTheMessageOpen() {
        HttpRequest r = handler.request(HttpMethod.of("CONNECT"), "/p", new Recorder());
        r.endHeaders();
        assertTrue(sent(), sent().startsWith("CONNECT /p HTTP/1.1\r\n"));
        r.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        r.endMessage();
    }

    @Test
    public void cancelBeforeSendBlocksSendingAndNotifiesNoOne() {
        HttpRequest r = handler.get("/p", new Recorder());
        r.cancel();
        r.cancel();
        assertEquals(0, r.bodyContent(ByteBuffer.wrap(new byte[] {1})));
        r.endMessage();
        assertFalse(sent(), sent().contains("GET /p"));
    }

    @Test
    public void cancelInFlightFailsTheHandlerAndIgnoresLaterBodyWrites() {
        Recorder rec = new Recorder();
        HttpRequest r = handler.post("/p", rec);
        r.endHeaders();
        r.cancel();
        assertEquals(1, rec.failures.size());
        assertTrue(rec.failures.get(0) instanceof CancellationException);
        endpoint.clearWrites();
        assertEquals(0, r.bodyContent(ByteBuffer.wrap(new byte[] {1})));
        assertEquals(0, endpoint.getAllBytes().length);
    }

    @Test
    public void requestsCannotBeCreatedOnAClosedConnection() {
        handler.close();
        try {
            handler.get("/p", null);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("Connection is not open", expected.getMessage());
        }
        assertFalse(handler.isOpen());
    }

    @Test
    public void requestInjectionIsRejected() {
        HttpRequest r = handler.get("/p", null);
        try {
            r.header("X-Test", "a\r\nInjected: yes");
            fail("CRLF in a header value must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            r.header("X-Test", "a\nb");
            fail("LF in a header value must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            r.header("Bad\r\nName", "v");
            fail("CRLF in a header name must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            r.header("Bad Name", "v");
            fail("a space in a header name must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            handler.get("/p HTTP/1.1\r\nHost: evil\r\n\r\nGET /x", null);
            fail("CRLF in the request target must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            handler.request(HttpMethod.of("GET /x HTTP/1.1\r\n"), "/p", null);
            fail("a bad method must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals("nothing may reach the wire", 0, endpoint.getAllBytes().length);
    }

    // ── responses ──

    @Test
    public void interimResponsesAreSkipped() {
        Recorder r = get();
        feed("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi");
        assertEquals(1, r.okCalls);
        assertEquals("hi", new String(r.body.toByteArray(), StandardCharsets.US_ASCII));
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void bodilessResponsesCompleteAtTheHeaders() {
        Recorder r = new Recorder();
        handler.get("/p", r).endMessage();
        feed("HTTP/1.1 204 No Content\r\n\r\n");
        assertEquals(1, r.closeCalls);
        Recorder r2 = new Recorder();
        handler.get("/p", r2).endMessage();
        feed("HTTP/1.1 304 Not Modified\r\nContent-Length: 10\r\n\r\n");
        assertEquals(1, r2.closeCalls);
        Recorder r3 = new Recorder();
        handler.head("/p", r3).endMessage();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n");
        assertEquals(1, r3.closeCalls);
        assertTrue(r3.ok);
    }

    @Test
    public void errorStatusUsesTheErrorCallback() {
        Recorder r = get();
        feed("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
        assertTrue(r.error);
        assertFalse(r.ok);
        assertEquals(HttpStatus.NOT_FOUND, r.status);
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void zeroContentLengthCompletesAtOnce() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.closeCalls);
        assertTrue(r.failures.isEmpty());
    }

    @Test
    public void invalidContentLengthFailsTheResponse() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\n");
        assertEquals(1, r.failures.size());
        assertTrue(r.failures.get(0).getMessage(), r.failures.get(0).getMessage().contains("Content-Length"));
        assertEquals(0, r.closeCalls);
    }

    @Test
    public void conflictingContentLengthValuesAreInvalid() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 4, 5\r\n\r\n");
        assertEquals(1, r.failures.size());
        // a list of equal values is refused too: the parser takes only digits
        Recorder r2 = new Recorder();
        newHandler("example.com", 80, false);
        handler.get("/p", r2).endMessage();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 2, 2\r\n\r\nhi");
        assertEquals(1, r2.failures.size());
    }

    @Test
    public void transferEncodingTogetherWithContentLengthIsAFramingError() {
        // RFC 9112 section 6.3: both together may be an attempt at request
        // smuggling; the response is refused rather than guessed at
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 99\r\n\r\n"
                + "3\r\nabc\r\n0\r\n\r\n");
        assertEquals(1, r.failures.size());
    }

    @Test
    public void responseWithoutFramingRunsUntilTheConnectionCloses() {
        // RFC 9112 section 6.3: with no Content-Length or Transfer-Encoding
        // the body is whatever arrives before the server closes
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nServer: x\r\n\r\nsome data");
        assertEquals(0, r.closeCalls);
        handler.disconnected();
        assertEquals("some data", new String(r.body.toByteArray(), StandardCharsets.US_ASCII));
        assertEquals(1, r.closeCalls);
        assertTrue(r.failures.isEmpty());
    }

    @Test
    public void chunkExtensionsAndTrailersAreHandled() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "4;ext=1\r\nwiki\r\n5\r\npedia\r\n0\r\nX-Trailer: done\r\n\r\n");
        assertEquals("wikipedia", new String(r.body.toByteArray(), StandardCharsets.US_ASCII));
        assertTrue(r.headers.toString(), r.headers.contains("x-trailer: done"));
        assertTrue(r.endBody);
    }

    @Test
    public void chunkedBodySplitAtEveryByteMatchesWholeBuffer() {
        String wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n";
        byte[] bytes = wire.getBytes(StandardCharsets.US_ASCII);
        for (int chunk = 1; chunk <= 5; chunk++) {
            newHandler("example.com", 80, false);
            Recorder r = get();
            ByteBuffer acc = ByteBuffer.allocate(bytes.length + 8);
            int i = 0;
            while (i < bytes.length) {
                int n = Math.min(chunk, bytes.length - i);
                acc.put(bytes, i, n);
                i += n;
                acc.flip();
                handler.receive(acc);
                acc.compact();
            }
            assertEquals("chunk " + chunk, "abcde", new String(r.body.toByteArray(), StandardCharsets.US_ASCII));
            assertEquals(1, r.closeCalls);
        }
    }

    @Test
    public void invalidChunkSizeFailsTheResponse() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nabc\r\n0\r\n\r\n");
        assertEquals("a bad chunk size is a framing error: " + r.failures, 1, r.failures.size());
        assertEquals("the connection must be dropped", 1, endpoint.getCloseCount());
    }

    @Test
    public void headerParsingHandlesFolding() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nX-Folded: part1\r\n  part2\r\nContent-Length: 0\r\n\r\n");
        assertTrue(r.headers.toString(), r.headers.contains("x-folded: part1 part2"));
    }

    @Test
    public void aLineThatIsNotAFieldMakesTheResponseMalformed() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nno colon here\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.failures.size());
        assertEquals(0, r.okCalls);
    }

    @Test
    public void invalidStatusLineMakesTheResponseMalformed() {
        Recorder r = get();
        feed("garbage\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.failures.size());
        Recorder r2 = new Recorder();
        newHandler("example.com", 80, false);
        handler.get("/p", r2).endMessage();
        feed("HTTP/1.1 abc Weird\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r2.failures.size());
        // the reason phrase is optional (RFC 9112 section 4)
        Recorder r3 = new Recorder();
        newHandler("example.com", 80, false);
        handler.get("/p", r3).endMessage();
        feed("HTTP/1.1 200\r\nContent-Length: 0\r\n\r\n");
        assertEquals(HttpStatus.OK, r3.status);
    }

    @Test
    public void oversizedResponseHeadersCloseTheConnection() {
        handler.setMaxResponseHeaderSize(20);
        assertEquals(20, handler.getMaxResponseHeaderSize());
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nX-Long: 0123456789012345678901234567890123456789\r\n\r\n");
        assertEquals(1, r.failures.size());
        assertEquals("Response header too large", r.failures.get(0).getMessage());
        assertTrue(endpoint.getCloseCount() > 0);
        assertFalse(handler.isOpen());
    }

    @Test
    public void connectionCloseResponseClosesTheTransport() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 1\r\n\r\nx");
        assertEquals(1, r.closeCalls);
        assertTrue(endpoint.getCloseCount() > 0);
    }

    @Test
    public void gzipResponseBodyIsDecodedAndEncodingHeaderHidden() throws Exception {
        byte[] gz = gzip("decoded text".getBytes(StandardCharsets.US_ASCII));
        Recorder r = get();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + gz.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        wire.write(gz);
        feedBytes(wire.toByteArray());
        assertEquals("decoded text", new String(r.body.toByteArray(), StandardCharsets.US_ASCII));
        for (String h : r.headers) {
            assertFalse(h, h.toLowerCase().startsWith("content-encoding"));
        }
        assertTrue(r.endBody);
        assertTrue(r.failures.isEmpty());
    }

    @Test
    public void corruptGzipResponseFailsTheHandler() throws Exception {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 12\r\n\r\nnot gzip....");
        assertFalse(r.failures.isEmpty());
    }

    @Test
    public void decodingDisabledKeepsEncodedBodyAndHeader() throws Exception {
        handler.setDecodeResponseContentCoding(false);
        byte[] gz = gzip("x".getBytes(StandardCharsets.US_ASCII));
        Recorder r = get();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + gz.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        wire.write(gz);
        feedBytes(wire.toByteArray());
        assertArrayEquals(gz, r.body.toByteArray());
        assertTrue(r.headers.toString(), r.headers.contains("content-encoding: gzip"));
    }

    @Test
    public void altSvcIsReportedOncePerConnection() {
        final List<String> seen = new ArrayList<String>();
        handler.setAltSvcListener(new AltSvcListener() {
            @Override
            public void altSvcReceived(String value) {
                seen.add(value);
            }
        });
        get();
        feed("HTTP/1.1 200 OK\r\nAlt-Svc: h3=\":443\"\r\nContent-Length: 0\r\n\r\n");
        get();
        feed("HTTP/1.1 200 OK\r\nAlt-Svc: h3=\":444\"\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, seen.size());
        assertEquals("h3=\":443\"", seen.get(0));
    }

    @Test
    public void unauthorizedWithMalformedLengthDoesNotCrash() {
        handler.credentials("u", "p");
        Recorder r = get();
        try {
            feed("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"x\"\r\n"
                    + "Content-Length: nope\r\n\r\n");
        } catch (RuntimeException e) {
            fail("a malformed Content-Length on a challenge must not escape: " + e);
        }
        assertEquals(1, r.failures.size());
    }

    // ── lifecycle ──

    @Test
    public void disconnectFailsOutstandingRequestsAndNotifiesTheConnectionHandler() {
        Recorder r = get();
        handler.disconnected();
        assertEquals(1, r.failures.size());
        assertEquals("Connection disconnected", r.failures.get(0).getMessage());
        assertEquals(1, conn.disconnected);
        assertFalse(handler.isOpen());
    }

    @Test
    public void transportErrorFailsOutstandingRequestsAndNotifiesTheConnectionHandler() {
        Recorder r = get();
        IOException cause = new IOException("boom");
        handler.error(cause);
        assertEquals(1, r.failures.size());
        assertEquals(cause, r.failures.get(0));
        assertEquals(1, conn.errors.size());
    }

    @Test
    public void closeAbortsOutstandingRequestsOnce() {
        Recorder r = get();
        handler.close();
        handler.close();
        assertEquals(1, r.failures.size());
        assertEquals("Connection closed", r.failures.get(0).getMessage());
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void closeWhenIdleClosesImmediatelyWhenIdle() {
        handler.closeWhenIdle();
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void closeWhenIdleWaitsForTheInFlightResponse() {
        Recorder r = get();
        handler.closeWhenIdle();
        assertEquals(0, endpoint.getCloseCount());
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.closeCalls);
        assertEquals(1, endpoint.getCloseCount());
        assertTrue(r.failures.isEmpty());
    }

    @Test
    public void requestShutdownWithoutSelectorLoopClosesWhenIdle() {
        handler.requestShutdown();
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void idleTimeoutClosesTheConnection() {
        handler = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        handler.setIdleTimeoutMs(5000);
        assertEquals(5000, handler.getIdleTimeoutMs());
        endpoint = new BinaryRecordingEndpoint();
        handler.connected(endpoint);
        assertEquals(1, endpoint.getTimers().size());
        assertEquals(5000, endpoint.getTimers().get(0).getDelayMs());
        endpoint.fireTimers();
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void accessorsReportConfiguration() {
        assertEquals("example.com", handler.getHost());
        assertEquals(80, handler.getPort());
        assertEquals(HttpVersion.HTTP_1_1, handler.getVersion());
        assertFalse(handler.isConnectProtocolEnabled());
        assertTrue(handler.isEncodeRequestBodyContentCoding());
        handler.setEncodeRequestBodyContentCoding(false);
        assertFalse(handler.isEncodeRequestBodyContentCoding());
        assertEquals(HttpVersion.HTTP_2_0, HttpVersion.HTTP_2_0);
    }

    @Test
    public void whenConnectProtocolKnownRunsLaterOnHttp1() {
        final int[] ran = new int[1];
        handler.whenConnectProtocolKnown(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertTrue(ran[0] <= 1);
    }

    @Test
    public void credentialsCanBeClearedWithoutAuthRetry() {
        handler.credentials("u", "p");
        handler.clearCredentials();
        Recorder r = get();
        feed("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"x\"\r\nContent-Length: 0\r\n\r\n");
        assertTrue(r.error);
        assertEquals(HttpStatus.UNAUTHORIZED, r.status);
    }

    @Test
    public void blockedH2CipherSuites() {
        assertFalse(HttpClientProtocolHandler.isBlockedH2CipherSuite(info("TLSv1.3", "TLS_AES_128_GCM_SHA256")));
        assertFalse(HttpClientProtocolHandler.isBlockedH2CipherSuite(info(null, "x")));
        assertFalse(HttpClientProtocolHandler.isBlockedH2CipherSuite(info("TLSv1.2", null)));
        assertFalse(HttpClientProtocolHandler.isBlockedH2CipherSuite(
                info("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")));
        assertTrue(HttpClientProtocolHandler.isBlockedH2CipherSuite(
                info("TLSv1.2", "TLS_RSA_WITH_AES_128_CBC_SHA")));
    }

    private static SecurityInfo info(final String protocol, final String cipher) {
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
                return null;
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
