/*
 * Http1ParserTest.java
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


package org.bluezoo.gumdrop.http.h1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.testsupport.RecordingMessageHandler;
import org.junit.Test;

/**
 * Tests for {@link Http1Parser}.
 *
 * <p>Every scenario is replayed with the input cut into chunks of several
 * sizes, down to one byte at a time, and must produce exactly the same events
 * each time: a push parser that depends on where the network happens to split
 * the stream is wrong.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http1ParserTest {

    private static final int[] CHUNK_SIZES = {1, 2, 3, 5, 7, 64, 100000};

    /** What to do to the parser before feeding it. */
    private interface Setup {
        void apply(Http1Parser p);
    }

    private static final Setup NONE = new Setup() {
        @Override public void apply(Http1Parser p) { }
    };

    private static RecordingMessageHandler run(boolean request, String wire, int chunk, Setup setup, boolean close, boolean direct) {
        RecordingMessageHandler r = new RecordingMessageHandler();
        Http1Parser p = request ? Http1Parser.forRequests(r) : Http1Parser.forResponses(r);
        setup.apply(p);
        byte[] all = wire.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = direct
                ? ByteBuffer.allocateDirect(all.length + 16)
                : ByteBuffer.allocate(all.length + 16);
        for (int pos = 0; pos < all.length; pos += chunk) {
            int n = Math.min(chunk, all.length - pos);
            buf.put(all, pos, n);
            buf.flip();
            p.receive(buf);
            buf.compact();
        }
        if (close) {
            p.close();
        }
        return r;
    }

    /** Asserts the events for the whole input at every chunk size. */
    private static void expect(boolean request, String wire, Setup setup, boolean close, String... expected) {
        List<String> want = Arrays.asList(expected);
        for (int i = 0; i < CHUNK_SIZES.length; i++) {
            RecordingMessageHandler r = run(request, wire, CHUNK_SIZES[i], setup, close, false);
            assertEquals("chunk size " + CHUNK_SIZES[i], want, r.events);
            assertTrue("views must be read-only at chunk size " + CHUNK_SIZES[i], r.viewsReadOnly);
        }
    }

    private static void expectRequest(String wire, String... expected) {
        expect(true, wire, NONE, false, expected);
    }

    private static void expectResponse(String wire, String... expected) {
        expect(false, wire, NONE, false, expected);
    }

    // ---- requests ----

    @Test
    public void simpleRequest() {
        expectRequest("GET /x?y=1 HTTP/1.1\r\nHost: example.test\r\nX-Custom:  spaced value \t\r\n"
                + "Accept: a\r\nAccept: b\r\n\r\n",
                "method GET", "target /x?y=1", "version HTTP/1.1",
                "authority example.test", "header x-custom spaced value",
                "header accept a", "header accept b",
                "endHeaders", "endMessage");
    }

    @Test
    public void fieldNamesAreLowerCased() {
        expectRequest("GET / HTTP/1.1\r\nHOST: h\r\nX-MiXeD-Case: v\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1",
                "authority h", "header x-mixed-case v", "endHeaders", "endMessage");
    }

    @Test
    public void requestWithContentLengthBody() {
        expectRequest("POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhelloGET",
                "method POST", "target /p", "version HTTP/1.1",
                "authority h", "long content-length 5", "endHeaders",
                "body hello", "endMessage");
    }

    @Test
    public void requestWithZeroContentLengthHasNoBody() {
        expectRequest("POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n\r\n",
                "method POST", "target /p", "version HTTP/1.1",
                "authority h", "long content-length 0", "endHeaders", "endMessage");
    }

    @Test
    public void dateFieldsAreReportedAsInstants() {
        expectRequest("GET /p HTTP/1.1\r\nHost: h\r\n"
                + "If-Modified-Since: Sun, 06 Nov 1994 08:49:37 GMT\r\n"
                + "If-Unmodified-Since: Sun Nov  6 08:49:37 1994\r\n\r\n",
                "method GET", "target /p", "version HTTP/1.1", "authority h",
                "date if-modified-since 1994-11-06T08:49:37Z",
                "date if-unmodified-since 1994-11-06T08:49:37Z",
                "endHeaders", "endMessage");
    }

    @Test
    public void theObsoleteDateFormsAreReadAndTheBrokenOnesAreNot() {
        expectRequest("GET /p HTTP/1.1\r\nHost: h\r\n"
                + "If-Modified-Since: Sunday, 06-Nov-94 08:49:37 GMT\r\n"
                + "If-Unmodified-Since: Sun, 06 Nov 94 08:49:37 GMT\r\n\r\n",
                "method GET", "target /p", "version HTTP/1.1", "authority h",
                "date if-modified-since 1994-11-06T08:49:37Z",
                "header if-unmodified-since Sun, 06 Nov 94 08:49:37 GMT",
                "endHeaders", "endMessage");
    }

    @Test
    public void aDateFieldThatIsNotADateIsReportedAsAnOrdinaryField() {
        expectRequest("GET /p HTTP/1.1\r\nHost: h\r\n"
                + "If-Modified-Since: yesterday-ish\r\n"
                + "If-Range: \"etag-1\"\r\n\r\n",
                "method GET", "target /p", "version HTTP/1.1", "authority h",
                "header if-modified-since yesterday-ish",
                "header if-range \"etag-1\"",
                "endHeaders", "endMessage");
    }

    @Test
    public void retryAfterIsSecondsOrADate() {
        expectResponse("HTTP/1.1 503 Busy\r\nRetry-After: 120\r\nContent-Length: 0\r\n\r\n",
                "version HTTP/1.1", "status 503", "reason Busy",
                "long retry-after 120", "long content-length 0", "endHeaders", "endMessage");
        expectResponse("HTTP/1.1 503 Busy\r\nRetry-After: Sun, 06 Nov 1994 08:49:37 GMT\r\n"
                + "Content-Length: 0\r\n\r\n",
                "version HTTP/1.1", "status 503", "reason Busy",
                "date retry-after 1994-11-06T08:49:37Z", "long content-length 0", "endHeaders", "endMessage");
    }

    @Test
    public void requestWithChunkedBodyAndTrailers() {
        expectRequest("PUT /p HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "4;ext=1\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trail: v\r\n\r\n",
                "method PUT", "target /p", "version HTTP/1.1",
                "authority h", "header transfer-encoding chunked", "endHeaders",
                "body Wikipedia", "header x-trail v", "endMessage");
    }

    @Test
    public void trailerFieldsAreTypedAndForbiddenOnesDropped() {
        expectRequest("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n"
                + "Expires: Sun, 06 Nov 1994 08:49:37 GMT\r\nContent-Length: 9\r\nX-A: b\r\n\r\n",
                "method POST", "target /", "version HTTP/1.1", "header transfer-encoding chunked",
                "endHeaders", "body x", "date expires 1994-11-06T08:49:37Z", "header x-a b", "endMessage");
    }

    @Test
    public void emptyLinesBeforeTheRequestLineAreIgnored() {
        // RFC 9112 section 2.2
        expectRequest("\r\n\r\nGET / HTTP/1.0\r\n\r\n",
                "method GET", "target /", "version HTTP/1.0", "endHeaders", "endMessage");
    }

    @Test
    public void pipelinedRequestsAreParsedInTurn() {
        expectRequest("GET /a HTTP/1.1\r\nHost: h\r\n\r\nPOST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\nhiGET /c HTTP/1.1\r\n\r\n",
                "method GET", "target /a", "version HTTP/1.1", "authority h", "endHeaders", "endMessage",
                "method POST", "target /b", "version HTTP/1.1", "authority h", "long content-length 2",
                "endHeaders", "body hi", "endMessage",
                "method GET", "target /c", "version HTTP/1.1", "endHeaders", "endMessage");
    }

    @Test
    public void extensionMethodIsPassedOn() {
        expectRequest("PURGE /x HTTP/1.1\r\n\r\n",
                "method PURGE", "target /x", "version HTTP/1.1", "endHeaders", "endMessage");
    }

    @Test
    public void foldedRequestFieldIsUnfoldedToSingleSpaces() {
        // RFC 9112 section 5.2: each obs-fold is replaced by SP.
        expectRequest("GET / HTTP/1.1\r\nX-Long: part one\r\n  part two\r\n\tpart three\r\nX-Next: n\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1",
                "header x-long part one part two part three", "header x-next n",
                "endHeaders", "endMessage");
    }

    // ---- typed fields ----

    @Test
    public void typedFieldEvents() {
        expectRequest("POST /u HTTP/1.1\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Disposition: attachment; filename=\"a.txt\"\r\nContent-Length: 3\r\n\r\nabc",
                "method POST", "target /u", "version HTTP/1.1",
                "contentType text/plain charset=utf-8",
                "contentDisposition attachment filename=a.txt",
                "long content-length 3", "endHeaders", "body abc", "endMessage");
    }

    @Test
    public void unparseableTypedFieldIsReportedAsAnOrdinaryHeader() {
        expectRequest("GET / HTTP/1.1\r\nContent-Type: (comment) text/plain\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1",
                "header content-type (comment) text/plain", "endHeaders", "endMessage");
    }

    // ---- responses ----

    @Test
    public void responseWithContentLengthBody() {
        expectResponse("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcHTTP/1.1 204 No Content\r\n\r\n",
                "version HTTP/1.1", "status 200", "reason OK", "long content-length 3",
                "endHeaders", "body abc", "endMessage",
                "version HTTP/1.1", "status 204", "reason No Content", "endHeaders", "endMessage");
    }

    @Test
    public void responseWithoutReasonPhrase() {
        expectResponse("HTTP/1.1 204\r\n\r\n",
                "version HTTP/1.1", "status 204", "reason ", "endHeaders", "endMessage");
        expectResponse("HTTP/1.1 204 \r\n\r\n",
                "version HTTP/1.1", "status 204", "reason ", "endHeaders", "endMessage");
    }

    @Test
    public void chunkedResponse() {
        expectResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",
                "version HTTP/1.1", "status 200", "reason OK", "header transfer-encoding chunked",
                "endHeaders", "body abc", "endMessage");
    }

    @Test
    public void foldedResponseFieldIsAccepted() {
        // A legacy server may fold; a client must still read the response.
        expectResponse("HTTP/1.1 200 OK\r\nX-Long: a\r\n b\r\nContent-Length: 0\r\n\r\n",
                "version HTTP/1.1", "status 200", "reason OK", "header x-long a b",
                "long content-length 0", "endHeaders", "endMessage");
    }

    @Test
    public void bodylessResponses() {
        // RFC 9112 section 6.3: 204 and 304 have no body whatever the framing fields say.
        expectResponse("HTTP/1.1 304 Not Modified\r\nContent-Length: 10\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n",
                "version HTTP/1.1", "status 304", "reason Not Modified", "long content-length 10",
                "endHeaders", "endMessage",
                "version HTTP/1.1", "status 204", "reason No Content", "endHeaders", "endMessage");
    }

    @Test
    public void responseToHeadHasNoBody() {
        Setup head = new Setup() {
            @Override public void apply(Http1Parser p) { p.expectResponseTo(HttpMethod.HEAD); }
        };
        expect(false, "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n", head, false,
                "version HTTP/1.1", "status 200", "reason OK", "long content-length 10",
                "endHeaders", "endMessage");
    }

    @Test
    public void interimResponseIsAMessageOfItsOwn() {
        expectResponse("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nx",
                "version HTTP/1.1", "status 100", "reason Continue", "endHeaders", "endMessage",
                "version HTTP/1.1", "status 200", "reason OK", "long content-length 1",
                "endHeaders", "body x", "endMessage");
    }

    @Test
    public void responseWithNoFramingRunsUntilClose() {
        expect(false, "HTTP/1.1 200 OK\r\nX: y\r\n\r\nhello", NONE, true,
                "version HTTP/1.1", "status 200", "reason OK", "header x y", "endHeaders",
                "body hello", "endMessage");
    }

    // ---- zero copy ----

    @Test
    public void plainFieldValuesAreViewsOfTheWireBytes() {
        RecordingMessageHandler r = run(true, "GET /x HTTP/1.1\r\nHost: example.test\r\nX-A: b\r\n\r\n", 100000, NONE, false, true);
        assertTrue("views of a direct buffer are direct: nothing was copied", r.sawDirectView);
        assertFalse("and no heap copy was made", r.sawHeapView);
        assertTrue(r.viewsReadOnly);
    }

    // ---- errors ----

    private static void expectRequestError(String wire, String... expected) {
        expect(true, wire, NONE, false, expected);
    }

    @Test
    public void bareLfInTheStartLineIsMalformed() {
        expectRequestError("GET / HTTP/1.1\nHost: x\r\n\r\n", "error MALFORMED");
    }

    @Test
    public void bareLfInAFieldLineIsMalformed() {
        expectRequestError("GET / HTTP/1.1\r\nHost: x\nY: z\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
    }

    @Test
    public void bareCrIsMalformed() {
        expectRequestError("GET / HTTP/1.1\r\nHost: x\rY: z\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
    }

    @Test
    public void whitespaceBeforeTheColonIsMalformed() {
        // RFC 9112 section 5.1
        expectRequestError("GET / HTTP/1.1\r\nHost : x\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
    }

    @Test
    public void invalidFieldNameCharacterIsMalformed() {
        expectRequestError("GET / HTTP/1.1\r\nHo@st: x\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
        expectRequestError("GET / HTTP/1.1\r\n: x\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
    }

    @Test
    public void controlCharacterInAValueIsMalformed() {
        expectRequestError("GET / HTTP/1.1\r\nX: a\u0001b\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
        expectRequestError("GET / HTTP/1.1\r\nX: a\u007fb\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
    }

    @Test
    public void octetsAboveAsciiInAValueAreOpaqueNotMalformed() {
        expectRequest("GET / HTTP/1.1\r\nX: cafÃ©\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "header x cafÃ©",
                "endHeaders", "endMessage");
    }

    @Test
    public void startLineMustHaveSingleSpaces() {
        expectRequestError("GET  / HTTP/1.1\r\n\r\n", "error MALFORMED");
        expectRequestError("GET / HTTP/1.1 \r\n\r\n", "error MALFORMED");
        expectRequestError("GET /\r\n\r\n", "error MALFORMED");
    }

    @Test
    public void unsupportedAndGarbledVersions() {
        expectRequestError("GET / HTTP/2.0\r\n\r\n", "error UNSUPPORTED_VERSION");
        expectRequestError("GET / HTTP/0.9\r\n\r\n", "error UNSUPPORTED_VERSION");
        expectRequestError("GET / HTTP/1.1x\r\n\r\n", "error MALFORMED");
        expectRequestError("GET / FTP/1.1\r\n\r\n", "error MALFORMED");
    }

    @Test
    public void contentLengthMustBeDigits() {
        String[] bad = {"5x", "-1", "", "+5", "0x10", "99999999999999999999"};
        for (int i = 0; i < bad.length; i++) {
            expectRequestError("POST / HTTP/1.1\r\nContent-Length: " + bad[i] + "\r\n\r\n",
                    "method POST", "target /", "version HTTP/1.1", "error MALFORMED");
        }
    }

    @Test
    public void repeatedContentLengthMustAgree() {
        expectRequest("POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nhi",
                "method POST", "target /", "version HTTP/1.1",
                "long content-length 2", "long content-length 2", "endHeaders", "body hi", "endMessage");
        expectRequestError("POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nhi",
                "method POST", "target /", "version HTTP/1.1",
                "long content-length 2", "error FRAMING_CONFLICT");
    }

    @Test
    public void contentLengthWithTransferEncodingIsAFramingConflict() {
        expectRequestError("POST / HTTP/1.1\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n",
                "method POST", "target /", "version HTTP/1.1",
                "long content-length 2", "error FRAMING_CONFLICT");
        expectRequestError("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n",
                "method POST", "target /", "version HTTP/1.1",
                "header transfer-encoding chunked", "error FRAMING_CONFLICT");
    }

    @Test
    public void requestTransferEncodingMustEndInChunked() {
        // RFC 9112 section 6.3: the length cannot be determined, so 400.
        expectRequestError("POST / HTTP/1.1\r\nTransfer-Encoding: gzip\r\n\r\n",
                "method POST", "target /", "version HTTP/1.1",
                "header transfer-encoding gzip", "error MALFORMED");
        expectRequestError("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: gzip\r\n\r\n",
                "method POST", "target /", "version HTTP/1.1",
                "header transfer-encoding chunked", "header transfer-encoding gzip", "error MALFORMED");
    }

    @Test
    public void chunkedMayNotBeAppliedTwice() {
        expectRequestError("POST / HTTP/1.1\r\nTransfer-Encoding: chunked, chunked\r\n\r\n",
                "method POST", "target /", "version HTTP/1.1", "error MALFORMED");
    }

    @Test
    public void transferEncodingSplitAcrossLinesIsOneList() {
        expectRequest("POST / HTTP/1.1\r\nTransfer-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                "method POST", "target /", "version HTTP/1.1",
                "header transfer-encoding gzip", "header transfer-encoding chunked",
                "endHeaders", "endMessage");
    }

    @Test
    public void badChunkFramingIsMalformed() {
        expectRequestError("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",
                "method POST", "target /", "version HTTP/1.1", "header transfer-encoding chunked",
                "endHeaders", "error MALFORMED");
        expectRequestError("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXY",
                "method POST", "target /", "version HTTP/1.1", "header transfer-encoding chunked",
                "endHeaders", "body abc", "error MALFORMED");
    }

    @Test
    public void limitsAreEnforcedBeforeTheUnitIsComplete() {
        Setup tight = new Setup() {
            @Override public void apply(Http1Parser p) {
                p.setMaxStartLineLength(20);
                p.setMaxFieldLineLength(12);
                p.setMaxFieldCount(2);
            }
        };
        expect(true, "GET /aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa HTTP/1.1\r\n\r\n", tight, false,
                "error URI_TOO_LONG");
        expect(true, "GET / HTTP/1.1\r\nX: aaaaaaaaaaaaaaaaaaaaaaaaa\r\n\r\n", tight, false,
                "method GET", "target /", "version HTTP/1.1", "error FIELD_SECTION_TOO_LARGE");
        expect(true, "GET / HTTP/1.1\r\nA: 1\r\nB: 2\r\nC: 3\r\n\r\n", tight, false,
                "method GET", "target /", "version HTTP/1.1", "header a 1", "header b 2",
                "error FIELD_SECTION_TOO_LARGE");
    }

    @Test
    public void statusCodeMustBeThreeDigits() {
        // The start line is one unit: its events are sent only once all of it is valid.
        expectResponse("HTTP/1.1 20 OK\r\n\r\n", "error MALFORMED");
        expectResponse("HTTP/1.1 2000 OK\r\n\r\n", "error MALFORMED");
        expectResponse("HTTP/1.1 abc OK\r\n\r\n", "error MALFORMED");
        expectResponse("HTTP/1.1 099 OK\r\n\r\n", "error MALFORMED");
        expectResponse("HTTP/1.1 600 OK\r\n\r\n", "error MALFORMED");
    }

    @Test
    public void nothingFollowsAnError() {
        RecordingMessageHandler r = run(true, "GET / HTTP/1.1\r\nBad Header: x\r\n\r\nGET /next HTTP/1.1\r\n\r\n",
                100000, NONE, false, false);
        assertEquals(Arrays.asList("method GET", "target /", "version HTTP/1.1", "error MALFORMED"), r.events);
    }

    @Test
    public void closeInTheMiddleOfAMessageIsMalformed() {
        expect(true, "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc", NONE, true,
                "method POST", "target /", "version HTTP/1.1", "long content-length 10",
                "endHeaders", "body abc", "error MALFORMED");
    }

    @Test
    public void closeBetweenMessagesIsNotAnError() {
        expect(true, "GET / HTTP/1.1\r\n\r\n", NONE, true,
                "method GET", "target /", "version HTTP/1.1", "endHeaders", "endMessage");
    }

    // ---- protocol switches, one-shot context, boundaries ----

    @Test
    public void switchingProtocolsStopsTheParserAndLeavesTheRestAlone() {
        for (int i = 0; i < CHUNK_SIZES.length; i++) {
            RecordingMessageHandler r = new RecordingMessageHandler();
            Http1Parser p = Http1Parser.forResponses(r);
            byte[] all = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\nRAW".getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer buf = ByteBuffer.allocate(all.length + 8);
            for (int pos = 0; pos < all.length; pos += CHUNK_SIZES[i]) {
                buf.put(all, pos, Math.min(CHUNK_SIZES[i], all.length - pos));
                buf.flip();
                p.receive(buf);
                buf.compact();
            }
            assertEquals("chunk size " + CHUNK_SIZES[i], Arrays.asList("version HTTP/1.1", "status 101",
                    "reason Switching Protocols", "header upgrade websocket", "endHeaders", "endMessage"),
                    r.events);
            assertTrue(p.isTunnel());
            buf.flip();
            assertEquals("the bytes after the switch are not ours: " + CHUNK_SIZES[i],
                    "RAW", RecordingMessageHandler.text(buf));
        }
    }

    @Test
    public void successfulResponseToConnectBecomesATunnel() {
        RecordingMessageHandler r = new RecordingMessageHandler();
        Http1Parser p = Http1Parser.forResponses(r);
        p.expectResponseTo(HttpMethod.CONNECT);
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\ntunnel".getBytes(StandardCharsets.ISO_8859_1));
        p.receive(buf);
        assertTrue(p.isTunnel());
        assertEquals("tunnel", RecordingMessageHandler.text(buf));
        assertEquals("endMessage", r.events.get(r.events.size() - 1));
    }

    @Test
    public void failedResponseToConnectIsAnOrdinaryResponse() {
        RecordingMessageHandler r = new RecordingMessageHandler();
        Http1Parser p = Http1Parser.forResponses(r);
        p.expectResponseTo(HttpMethod.CONNECT);
        p.receive(ByteBuffer.wrap("HTTP/1.1 403 Forbidden\r\nContent-Length: 2\r\n\r\nno".getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(p.isTunnel());
        assertEquals(Arrays.asList("version HTTP/1.1", "status 403", "reason Forbidden",
                "long content-length 2", "endHeaders", "body no", "endMessage"), r.events);
    }

    @Test
    public void expectResponseToAppliesToOneResponseOnly() {
        Setup head = new Setup() {
            @Override public void apply(Http1Parser p) { p.expectResponseTo(HttpMethod.HEAD); }
        };
        expect(false, "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nbody",
                head, false,
                "version HTTP/1.1", "status 200", "reason OK", "long content-length 4", "endHeaders", "endMessage",
                "version HTTP/1.1", "status 200", "reason OK", "long content-length 4", "endHeaders",
                "body body", "endMessage");
    }

    @Test
    public void interimResponseDoesNotUseUpTheHeadContext() {
        Setup head = new Setup() {
            @Override public void apply(Http1Parser p) { p.expectResponseTo(HttpMethod.HEAD); }
        };
        expect(false, "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n",
                head, false,
                "version HTTP/1.1", "status 100", "reason Continue", "endHeaders", "endMessage",
                "version HTTP/1.1", "status 200", "reason OK", "long content-length 4", "endHeaders", "endMessage");
    }

    @Test
    public void aFieldLineAtExactlyTheLimitIsAccepted() {
        Setup tight = new Setup() {
            @Override public void apply(Http1Parser p) { p.setMaxFieldLineLength(10); }
        };
        // "X: 1234567" is exactly 10 octets
        expect(true, "GET / HTTP/1.1\r\nX: 1234567\r\n\r\n", tight, false,
                "method GET", "target /", "version HTTP/1.1", "header x 1234567", "endHeaders", "endMessage");
        expect(true, "GET / HTTP/1.1\r\nX: 12345678\r\n\r\n", tight, false,
                "method GET", "target /", "version HTTP/1.1", "error FIELD_SECTION_TOO_LARGE");
    }

    @Test
    public void whitespaceAroundAFoldIsCollapsed() {
        expectRequest("GET / HTTP/1.1\r\nX: a \t\r\n \t b\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "header x a b", "endHeaders", "endMessage");
        expectRequest("GET / HTTP/1.1\r\nX:\r\n  late\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "header x late", "endHeaders", "endMessage");
    }

    @Test
    public void foldedValuesAreReadOnlyToo() {
        RecordingMessageHandler r = run(true, "GET / HTTP/1.1\r\nX: a\r\n b\r\n\r\n", 3, NONE, false, false);
        assertTrue(r.viewsReadOnly);
    }

    @Test
    public void transferEncodingOnAnHttp10RequestIsMalformed() {
        expectRequestError("POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n",
                "method POST", "target /", "version HTTP/1.0", "header transfer-encoding chunked", "error MALFORMED");
    }

    @Test
    public void contentLengthSurroundedByWhitespaceIsTrimmed() {
        expectRequest("POST / HTTP/1.1\r\nContent-Length:   3 \t\r\n\r\nabc",
                "method POST", "target /", "version HTTP/1.1", "long content-length 3",
                "endHeaders", "body abc", "endMessage");
    }

    // ---- Host is the authority ----

    @Test
    public void hostIsReportedAsTheAuthorityNotAHeader() {
        expectRequest("GET / HTTP/1.1\r\nHost: example.test:8080\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "authority example.test:8080",
                "endHeaders", "endMessage");
    }

    @Test
    public void hostWithUserinfoOrPathIsMalformed() {
        String[] bad = {"user@example.test", "example.test/path", "example .test", "ex?ample", "a#b"};
        for (int i = 0; i < bad.length; i++) {
            expectRequestError("GET / HTTP/1.1\r\nHost: " + bad[i] + "\r\n\r\n",
                    "method GET", "target /", "version HTTP/1.1", "error MALFORMED");
        }
    }

    @Test
    public void emptyHostIsPassedOn() {
        // RFC 9110 section 7.2: the field value may be empty when the target authority is
        expectRequest("GET / HTTP/1.1\r\nHost:\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "authority ", "endHeaders", "endMessage");
    }

    @Test
    public void everyHostLineIsReportedSoTheProtocolLayerCanCountThem() {
        expectRequest("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",
                "method GET", "target /", "version HTTP/1.1", "authority a", "authority b",
                "endHeaders", "endMessage");
    }

    @Test
    public void hostInAResponseIsAnOrdinaryField() {
        expectResponse("HTTP/1.1 200 OK\r\nHost: x\r\nContent-Length: 0\r\n\r\n",
                "version HTTP/1.1", "status 200", "reason OK", "header host x", "long content-length 0",
                "endHeaders", "endMessage");
    }

    // ---- raw tap and handing over the connection ----

    @Test
    public void aTapSeesEveryHeaderSectionFieldAsSentWithTheOriginalCase() {
        final List<String> tapped = new ArrayList<String>();
        Setup tap = new Setup() {
            @Override public void apply(Http1Parser p) {
                p.setFieldTap(new org.bluezoo.gumdrop.http.HeaderFieldHandler() {
                    @Override
                    public void field(ByteBuffer name, ByteBuffer value) {
                        tapped.add(RecordingMessageHandler.text(name) + "=" + RecordingMessageHandler.text(value));
                    }
                });
            }
        };
        for (int i = 0; i < CHUNK_SIZES.length; i++) {
            tapped.clear();
            run(true, "POST / HTTP/1.1\r\nHost: example.test\r\nContent-Type: text/plain;charset=utf-8\r\n"
                    + "Content-Length: 007\r\nX-Folded: a\r\n b\r\nTransfer-Encoding-X: y\r\n\r\n"
                    + "0000000", CHUNK_SIZES[i], tap, false, false);
            // names as sent; the typed events lose the exact text ("007" is the number 7), the tap keeps it
            assertEquals("chunk size " + CHUNK_SIZES[i], Arrays.asList("Host=example.test",
                    "Content-Type=text/plain;charset=utf-8", "Content-Length=007",
                    "X-Folded=a b", "Transfer-Encoding-X=y"), tapped);
        }
    }

    @Test
    public void theTapIsNotGivenTrailers() {
        final List<String> tapped = new ArrayList<String>();
        Setup tap = new Setup() {
            @Override public void apply(Http1Parser p) {
                p.setFieldTap(new org.bluezoo.gumdrop.http.HeaderFieldHandler() {
                    @Override
                    public void field(ByteBuffer name, ByteBuffer value) {
                        tapped.add(RecordingMessageHandler.text(name));
                    }
                });
            }
        };
        run(true, "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trail: v\r\n\r\n", 100000, tap, false, false);
        assertEquals(Arrays.asList("Transfer-Encoding"), tapped);
    }

    @Test
    public void handingOffAfterEndHeadersLeavesTheRestOfTheInputAlone() {
        for (int i = 0; i < CHUNK_SIZES.length; i++) {
            final Http1Parser[] parser = new Http1Parser[1];
            RecordingMessageHandler r = new RecordingMessageHandler() {
                @Override public void endHeaders() {
                    super.endHeaders();
                    parser[0].handOff();        // e.g. a WebSocket upgrade took the connection
                }
            };
            parser[0] = Http1Parser.forRequests(r);
            byte[] all = "GET /chat HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\n\r\nFRAMEBYTES".getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer buf = ByteBuffer.allocate(all.length + 8);
            for (int pos = 0; pos < all.length && !parser[0].isTunnel(); pos += CHUNK_SIZES[i]) {
                buf.put(all, pos, Math.min(CHUNK_SIZES[i], all.length - pos));
                buf.flip();
                parser[0].receive(buf);
                buf.compact();
            }
            assertTrue(parser[0].isTunnel());
            // what follows the blank line is not HTTP and is left for the new owner
            buf.flip();
            String rest = RecordingMessageHandler.text(buf);
            assertTrue("chunk size " + CHUNK_SIZES[i] + ": " + rest, "FRAMEBYTES".startsWith(rest) || rest.isEmpty());
            assertEquals(Arrays.asList("method GET", "target /chat", "version HTTP/1.1", "authority h",
                    "header upgrade websocket", "endHeaders"), r.events);
        }
    }

    @Test
    public void handingOffAfterEndMessageStopsBeforeTheNextPipelinedRequest() {
        final Http1Parser[] parser = new Http1Parser[1];
        RecordingMessageHandler r = new RecordingMessageHandler() {
            @Override public void endMessage() {
                super.endMessage();
                parser[0].handOff();
            }
        };
        parser[0] = Http1Parser.forRequests(r);
        ByteBuffer buf = ByteBuffer.wrap("GET /a HTTP/1.1\r\n\r\nGET /b HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        parser[0].receive(buf);
        assertEquals(Arrays.asList("method GET", "target /a", "version HTTP/1.1", "endHeaders", "endMessage"), r.events);
        assertEquals("GET /b HTTP/1.1\r\n\r\n", RecordingMessageHandler.text(buf));
    }
}
