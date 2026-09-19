/*
 * ContentTypeParameterParsingTest.java
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Tests for {@link ContentTypeParser} parameter handling: malformed
 * parameters, quoting and RFC 2231 continuations and extended values, via
 * both the string and byte-buffer entry points.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentTypeParameterParsingTest {

    private static ContentType parseBuffer(String value) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        return ContentTypeParser.parse(
            ByteBuffer.wrap(value.getBytes(StandardCharsets.ISO_8859_1)), decoder);
    }

    @Test
    public void emptyOrShortValuesAreRejected() {
        assertNull(ContentTypeParser.parse((ByteBuffer) null, StandardCharsets.UTF_8.newDecoder()));
        assertNull(parseBuffer(""));
        assertNull(parseBuffer("a/"));
        assertNull(ContentTypeParser.parse((String) null));
        assertNull(ContentTypeParser.parse(""));
    }

    @Test
    public void missingSlashOrBadTokensAreRejected() {
        assertNull(parseBuffer("textplain"));
        assertNull(parseBuffer("te xt/plain"));
        assertNull(parseBuffer("text/pl ain"));
    }

    @Test
    public void bufferParserHandlesQuotedAndBareParameters() {
        ContentType ct = parseBuffer("text/plain; charset=\"utf-8\"; format=flowed");
        assertEquals("text", ct.getPrimaryType());
        assertEquals("plain", ct.getSubType());
        assertEquals("utf-8", ct.getParameter("charset"));
        assertEquals("flowed", ct.getParameter("format"));
    }

    @Test
    public void bufferParserDecodesEscapesInQuotedStrings() {
        ContentType ct = parseBuffer("text/plain; name=\"a\\\"b\\\\c\"");
        assertEquals("a\"b\\c", ct.getParameter("name"));
    }

    @Test
    public void bufferParserSkipsMalformedParameters() {
        ContentType ct = parseBuffer("text/plain; junk; b@d=1; good=ok");
        assertEquals("ok", ct.getParameter("good"));
        assertNull(ct.getParameter("junk"));
    }

    @Test
    public void bufferParserToleratesUnterminatedQuote() {
        ContentType ct = parseBuffer("text/plain; a=1; name=\"never closed");
        assertNotNull(ct);
        assertEquals("1", ct.getParameter("a"));
    }

    @Test
    public void bufferParserRfc2231ExtendedAndContinuations() {
        ContentType extended = parseBuffer("application/x-y; filename*=UTF-8''caf%C3%A9.txt");
        assertEquals("caf\u00e9.txt", extended.getParameter("filename"));

        ContentType continued = parseBuffer(
            "application/x-y; title*0=\"This is \"; title*1=\"a long \"; title*2=\"title\"");
        assertEquals("This is a long title", continued.getParameter("title"));

        ContentType continuedExtended = parseBuffer(
            "application/x-y; name*0*=UTF-8''caf%C3%A9; name*1*=%20bar");
        assertEquals("caf\u00e9 bar", continuedExtended.getParameter("name"));
    }

    @Test
    public void stringParserQuotedBareAndMalformed() {
        ContentType ct = ContentTypeParser.parse(
            "text/plain; charset=\"us-ascii\"; a=1; ;; bad; =x; n@me=1; q=\"a\\\"b\"");
        assertEquals("us-ascii", ct.getParameter("charset"));
        assertEquals("1", ct.getParameter("a"));
        assertEquals("a\"b", ct.getParameter("q"));
        assertNull(ct.getParameter("bad"));
    }

    @Test
    public void stringParserRfc2231() {
        ContentType extended = ContentTypeParser.parse("application/x-y; filename*=UTF-8''caf%C3%A9.txt");
        assertEquals("caf\u00e9.txt", extended.getParameter("filename"));

        ContentType continued = ContentTypeParser.parse(
            "application/x-y; title*0=\"This is \"; title*1=\"a long \"; title*2=\"title\"");
        assertEquals("This is a long title", continued.getParameter("title"));

        ContentType continuedExtended = ContentTypeParser.parse(
            "application/x-y; name*0*=UTF-8''caf%C3%A9; name*1*=%20bar");
        assertEquals("caf\u00e9 bar", continuedExtended.getParameter("name"));
    }

    @Test
    public void stringParserDecodesEncodedWordValues() {
        ContentType ct = ContentTypeParser.parse("text/plain; name=\"=?UTF-8?Q?caf=C3=A9?=\"");
        assertEquals("caf\u00e9", ct.getParameter("name"));
    }

    @Test
    public void duplicateParametersKeepFirst() {
        ContentType ct = ContentTypeParser.parse("text/plain; a=1; a=2");
        assertEquals("1", ct.getParameter("a"));
    }

    @Test
    public void contentDispositionSharesContinuationHandling() {
        ContentDisposition plain = ContentDispositionParser.parse(
            "attachment; filename*0=\"long file \"; filename*1=\"name.txt\"");
        assertEquals("long file name.txt", plain.getParameter("filename"));
        ContentDisposition extended = ContentDispositionParser.parse(
            "attachment; filename*0*=UTF-8''caf%C3%A9; filename*1*=%20bar.txt");
        assertEquals("caf\u00e9 bar.txt", extended.getParameter("filename"));
    }
}
