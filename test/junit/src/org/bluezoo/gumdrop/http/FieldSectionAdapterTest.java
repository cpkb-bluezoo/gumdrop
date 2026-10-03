/*
 * FieldSectionAdapterTest.java
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


package org.bluezoo.gumdrop.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.RecordingMessageHandler;
import org.junit.Test;

/**
 * Tests for {@link FieldSectionAdapter}, which turns the fields of an HTTP/2
 * or HTTP/3 field section into {@link HttpMessageHandler} events and applies
 * the rules for those protocols (RFC 9113 section 8.2 and 8.3, RFC 9114
 * section 4.2 and 4.3): pseudo-headers first and each at most once, lower-case
 * names, no connection-specific fields, and the pseudo-headers a message must
 * have.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FieldSectionAdapterTest {

    private static ByteBuffer b(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Runs a section through an adapter and returns what the handler was told. */
    private static RecordingMessageHandler run(FieldSectionAdapter.Kind kind, boolean[] finished, String... pairs) {
        RecordingMessageHandler r = new RecordingMessageHandler();
        FieldSectionAdapter a = new FieldSectionAdapter(r, HttpVersion.HTTP_2_0, kind);
        for (int i = 0; i < pairs.length; i += 2) {
            a.field(b(pairs[i]), b(pairs[i + 1]));
        }
        boolean ok = a.finish();
        if (finished != null) {
            finished[0] = ok;
        }
        return r;
    }

    private static RecordingMessageHandler request(String... pairs) {
        return run(FieldSectionAdapter.Kind.REQUEST, null, pairs);
    }

    private static RecordingMessageHandler response(String... pairs) {
        return run(FieldSectionAdapter.Kind.RESPONSE, null, pairs);
    }

    private static void assertEvents(RecordingMessageHandler r, String... expected) {
        assertEquals(Arrays.asList(expected), r.events);
    }

    private static final String[] GET = {":method", "GET", ":scheme", "https", ":path", "/x", ":authority", "example.test"};

    private static String[] with(String[] base, String... more) {
        String[] out = Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }

    // ---- accepted ----

    @Test
    public void validRequestBecomesStartEventsThenFieldsThenEndHeaders() {
        assertEvents(request(with(GET, "accept", "*/*", "x-custom", "v")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test",
                "header accept */*", "header x-custom v", "endHeaders");
    }

    @Test
    public void validResponse() {
        assertEvents(response(":status", "200", "content-type", "text/plain; charset=utf-8", "content-length", "3"),
                "version HTTP/2.0", "status 200", "contentType text/plain charset=utf-8",
                "long content-length 3", "endHeaders");
    }

    @Test
    public void versionComesFromTheProtocol() {
        RecordingMessageHandler r = new RecordingMessageHandler();
        FieldSectionAdapter a = new FieldSectionAdapter(r, HttpVersion.HTTP_3, FieldSectionAdapter.Kind.RESPONSE);
        a.field(b(":status"), b("204"));
        assertTrue(a.finish());
        assertEvents(r, "version HTTP/3", "status 204", "endHeaders");
    }

    @Test
    public void typedFieldsAreTheSameAsInHttp1() {
        assertEvents(request(with(GET, "content-disposition", "attachment; filename=\"a.txt\"",
                "content-type", "(comment) text/plain")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test",
                "contentDisposition attachment filename=a.txt", "header content-type (comment) text/plain",
                "endHeaders");
    }

    @Test
    public void extensionMethodIsAccepted() {
        assertEvents(request(":method", "PURGE", ":scheme", "https", ":path", "/"),
                "version HTTP/2.0", "method PURGE", "scheme https", "target /", "endHeaders");
    }

    @Test
    public void aHostFieldIsAnOrdinaryHeaderAlongsideTheAuthority() {
        assertEvents(request(with(GET, "host", "example.test")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test",
                "header host example.test", "endHeaders");
    }

    @Test
    public void connectNeedsOnlyTheAuthority() {
        assertEvents(request(":method", "CONNECT", ":authority", "example.test:443"),
                "version HTTP/2.0", "method CONNECT", "authority example.test:443", "endHeaders");
    }

    @Test
    public void extendedConnectCarriesTheProtocolAndAllTheOthers() {
        assertEvents(request(":method", "CONNECT", ":protocol", "websocket", ":scheme", "https",
                ":path", "/chat", ":authority", "example.test"),
                "version HTTP/2.0", "method CONNECT", "protocol websocket", "scheme https",
                "target /chat", "authority example.test", "endHeaders");
    }

    @Test
    public void extendedConnectNeedsSchemeAndPath() {
        // RFC 8441 section 4
        assertRefused(request(":method", "CONNECT", ":protocol", "websocket", ":path", "/chat",
                ":authority", "example.test"),
                "version HTTP/2.0", "method CONNECT", "protocol websocket", "target /chat",
                "authority example.test");
        assertRefused(request(":method", "CONNECT", ":protocol", "websocket", ":scheme", "https",
                ":authority", "example.test"),
                "version HTTP/2.0", "method CONNECT", "protocol websocket", "scheme https",
                "authority example.test");
    }

    @Test
    public void octetsAboveAsciiInAValueAreOpaque() {
        assertEvents(request(with(GET, "x-raw", "cafÃ©")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test",
                "header x-raw cafÃ©", "endHeaders");
    }

    @Test
    public void teTrailersIsTheOnlyTeAllowed() {
        assertEvents(request(with(GET, "te", "trailers")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test",
                "header te trailers", "endHeaders");
    }

    // ---- refused ----

    private static void assertRefused(RecordingMessageHandler r, String... before) {
        java.util.List<String> want = new java.util.ArrayList<String>(Arrays.asList(before));
        want.add("error MALFORMED");
        assertEquals(want, r.events);
    }

    @Test
    public void aMissingMandatoryPseudoHeaderIsMalformed() {
        assertRefused(request(":method", "GET", ":scheme", "https"),
                "version HTTP/2.0", "method GET", "scheme https");
        assertRefused(request(":scheme", "https", ":path", "/"),
                "version HTTP/2.0", "scheme https", "target /");
        assertRefused(response("content-length", "0"), "version HTTP/2.0", "long content-length 0");
    }

    @Test
    public void connectMustNotCarrySchemeOrPath() {
        assertRefused(request(":method", "CONNECT", ":authority", "h:1", ":path", "/"),
                "version HTTP/2.0", "method CONNECT", "authority h:1", "target /");
        assertRefused(request(":method", "CONNECT", ":path", "/"),
                "version HTTP/2.0", "method CONNECT", "target /");
    }

    @Test
    public void duplicatePseudoHeaderIsMalformed() {
        assertRefused(request(":method", "GET", ":method", "POST", ":scheme", "https", ":path", "/"),
                "version HTTP/2.0", "method GET");
    }

    @Test
    public void pseudoHeaderAfterARegularFieldIsMalformed() {
        assertRefused(request(":method", "GET", "accept", "*/*", ":scheme", "https", ":path", "/"),
                "version HTTP/2.0", "method GET", "header accept */*");
    }

    @Test
    public void unknownPseudoHeaderIsMalformed() {
        assertRefused(request(":method", "GET", ":scheme", "https", ":path", "/", ":foo", "x"),
                "version HTTP/2.0", "method GET", "scheme https", "target /");
    }

    @Test
    public void aPseudoHeaderOfTheWrongKindOfMessageIsMalformed() {
        assertRefused(request(":status", "200"), "version HTTP/2.0");
        assertRefused(response(":status", "200", ":method", "GET"), "version HTTP/2.0", "status 200");
    }

    @Test
    public void emptyPathIsMalformed() {
        assertRefused(request(":method", "GET", ":scheme", "https", ":path", ""),
                "version HTTP/2.0", "method GET", "scheme https");
    }

    @Test
    public void statusMustBeThreeDigits() {
        String[] bad = {"99", "abc", "2000", "600", "", "20x"};
        for (int i = 0; i < bad.length; i++) {
            assertRefused(response(":status", bad[i]), "version HTTP/2.0");
        }
    }

    @Test
    public void upperCaseFieldNameIsMalformed() {
        assertRefused(request(with(GET, "Accept", "*/*")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test");
    }

    @Test
    public void invalidFieldNameIsMalformed() {
        assertRefused(request(with(GET, "bad name", "x")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test");
        assertRefused(request(with(GET, ":", "x")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test");
        assertRefused(request(with(GET, "", "x")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test");
    }

    @Test
    public void invalidMethodTokenIsMalformed() {
        assertRefused(request(":method", "GE T", ":scheme", "https", ":path", "/"), "version HTTP/2.0");
    }

    @Test
    public void controlCharactersAndEdgeWhitespaceInAValueAreMalformed() {
        String[] bad = {"a\u0000b", "a\rb", "a\nb", "\u007f", " leading", "trailing ", "\tleading", "trailing\t"};
        for (int i = 0; i < bad.length; i++) {
            assertRefused(request(with(GET, "x-v", bad[i])),
                    "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test");
        }
    }

    @Test
    public void connectionSpecificFieldsAreMalformed() {
        String[] names = {"connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade"};
        for (int i = 0; i < names.length; i++) {
            assertRefused(request(with(GET, names[i], "x")),
                    "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test");
        }
    }

    @Test
    public void teOtherThanTrailersIsMalformed() {
        assertRefused(request(with(GET, "te", "gzip")),
                "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test");
    }

    @Test
    public void nothingIsReportedAfterTheFirstFailureAndErrorComesOnce() {
        assertRefused(request(":method", "GET", "Bad", "x", ":scheme", "https", "accept", "*/*", "x-y", "z"),
                "version HTTP/2.0", "method GET");
    }

    @Test
    public void conflictingContentLengthIsAFramingConflictReportedOnce() {
        RecordingMessageHandler r = request(with(GET, "content-length", "2", "content-length", "3", "x", "y"));
        assertEvents(r, "version HTTP/2.0", "method GET", "scheme https", "target /x", "authority example.test",
                "long content-length 2", "error FRAMING_CONFLICT");
    }

    @Test
    public void finishReportsWhetherTheSectionWasAccepted() {
        boolean[] ok = new boolean[1];
        run(FieldSectionAdapter.Kind.REQUEST, ok, GET);
        assertTrue(ok[0]);
        run(FieldSectionAdapter.Kind.REQUEST, ok, ":method", "GET");
        assertFalse(ok[0]);
    }

    // ---- trailers ----

    @Test
    public void trailersAreReportedAsTrailersAndNeedNoPseudoHeaders() {
        boolean[] ok = new boolean[1];
        RecordingMessageHandler r = run(FieldSectionAdapter.Kind.TRAILERS, ok, "x-checksum", "abc");
        assertEvents(r, "trailer x-checksum abc");
        assertTrue(ok[0]);
    }

    @Test
    public void pseudoHeaderInTrailersIsMalformed() {
        RecordingMessageHandler r = run(FieldSectionAdapter.Kind.TRAILERS, null, ":status", "200");
        assertEvents(r, "error MALFORMED");
    }

    // ---- raw tap ----

    @Test
    public void aTapSeesEveryAcceptedFieldAsTheOriginalOctets() {
        final List<String> tapped = new ArrayList<String>();
        HeaderFieldHandler tap = new HeaderFieldHandler() {
            @Override
            public void field(ByteBuffer name, ByteBuffer value) {
                tapped.add(RecordingMessageHandler.text(name) + "=" + RecordingMessageHandler.text(value));
            }
        };
        RecordingMessageHandler r = new RecordingMessageHandler();
        FieldSectionAdapter a = new FieldSectionAdapter(r, HttpVersion.HTTP_2_0,
                FieldSectionAdapter.Kind.REQUEST, tap);
        String[] fields = with(GET, "content-type", "text/plain;charset=utf-8", "content-length", "007");
        for (int i = 0; i < fields.length; i += 2) {
            a.field(b(fields[i]), b(fields[i + 1]));
        }
        assertTrue(a.finish());
        // typed events lose the exact text ("007" becomes 7); the tap keeps it
        assertEquals(Arrays.asList(":method=GET", ":scheme=https", ":path=/x", ":authority=example.test",
                "content-type=text/plain;charset=utf-8", "content-length=007"), tapped);
        assertTrue(r.events.contains("long content-length 7"));
    }

    @Test
    public void aTapIsNotGivenAFieldTheAdapterRefused() {
        final List<String> tapped = new ArrayList<String>();
        HeaderFieldHandler tap = new HeaderFieldHandler() {
            @Override
            public void field(ByteBuffer name, ByteBuffer value) {
                tapped.add(RecordingMessageHandler.text(name));
            }
        };
        FieldSectionAdapter a = new FieldSectionAdapter(new RecordingMessageHandler(), HttpVersion.HTTP_2_0,
                FieldSectionAdapter.Kind.REQUEST, tap);
        a.field(b(":method"), b("GET"));
        a.field(b("Bad-Name"), b("x"));
        a.field(b("accept"), b("*/*"));
        assertFalse(a.finish());
        assertEquals(Arrays.asList(":method"), tapped);
    }
}
