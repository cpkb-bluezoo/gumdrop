/*
 * MimeParserBranchesTest.java
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

package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Branch-oriented tests for {@link MimeParser}: header error paths, every
 * Content-Transfer-Encoding, nested multiparts, boundary recognition, CR line
 * endings and incomplete input, each driven at several chunk sizes.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserBranchesTest {

    /** Records parser events and decoded body text. */
    static final class Rec implements MimeHandler {
        final List<String> events = new ArrayList<String>();
        final StringBuilder body = new StringBuilder();
        final StringBuilder unexpected = new StringBuilder();
        ContentType contentType;
        MimeLocator locator;
        long endHeadersOffset = -1L;
        long endHeadersLine = -1L;

        @Override
        public void setLocator(MimeLocator locator) {
            this.locator = locator;
            events.add("locator");
        }

        @Override
        public void startEntity(String boundary) {
            events.add("start:" + boundary);
        }

        @Override
        public void contentType(ContentType ct) {
            contentType = ct;
            events.add("type:" + ct.getPrimaryType() + "/" + ct.getSubType());
        }

        @Override
        public void contentDisposition(ContentDisposition cd) {
            events.add("disp:" + cd.getDispositionType());
        }

        @Override
        public void contentTransferEncoding(String encoding) {
            events.add("cte:" + encoding);
        }

        @Override
        public void contentID(ContentID cid) {
            events.add("cid:" + cid.getLocalPart());
        }

        @Override
        public void contentDescription(String description) {
            events.add("desc:" + description);
        }

        @Override
        public void mimeVersion(MimeVersion version) {
            events.add("version");
        }

        @Override
        public void endHeaders() {
            if (endHeadersOffset < 0) {
                endHeadersOffset = locator.getOffset();
                endHeadersLine = locator.getLineNumber();
            }
            events.add("endHeaders");
        }

        @Override
        public void bodyContent(ByteBuffer content) {
            byte[] b = new byte[content.remaining()];
            content.get(b);
            body.append(new String(b, StandardCharsets.ISO_8859_1));
        }

        @Override
        public void unexpectedContent(ByteBuffer content) {
            byte[] b = new byte[content.remaining()];
            content.get(b);
            unexpected.append(new String(b, StandardCharsets.ISO_8859_1));
            events.add("unexpected");
        }

        @Override
        public void endEntity(String boundary) {
            events.add("end:" + boundary);
        }
    }

    /** Parser subclass accepting bare CR line endings. */
    static final class CrParser extends MimeParser {
        CrParser() {
            setAllowCRLineEnd(true);
        }
    }

    private static Rec run(MimeParser p, String text, int chunk) throws MimeParseException {
        Rec rec = new Rec();
        p.setHandler(rec);
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(data.length + 8);
        int off = 0;
        while (off < data.length) {
            int n = Math.min(chunk, data.length - off);
            buf.put(data, off, n);
            off += n;
            buf.flip();
            p.receive(buf);
            buf.compact();
        }
        buf.flip();
        p.receive(buf);
        p.close();
        return rec;
    }

    private static Rec run(String text, int chunk) throws MimeParseException {
        return run(new MimeParser(), text, chunk);
    }

    private static final int[] CHUNKS = new int[] {1, 2, 3, 7, 1000};

    private static void expectFailure(String text, String needle) {
        for (int i = 0; i < CHUNKS.length; i++) {
            try {
                run(text, CHUNKS[i]);
                fail("expected failure for chunk " + CHUNKS[i]);
            } catch (MimeParseException e) {
                assertTrue(e.getMessage(), e.getMessage() != null);
            }
        }
    }

    // ===== configuration =====

    @Test
    public void testConfigurationAccessors() {
        MimeParser p = new MimeParser();
        assertEquals(4096, p.getMaxBufferSize());
        assertEquals(32 * 1024, p.getMaxHeaderValueSize());
        p.setMaxBufferSize(10);
        p.setMaxHeaderValueSize(20);
        assertEquals(10, p.getMaxBufferSize());
        assertEquals(20, p.getMaxHeaderValueSize());
        try {
            p.setMaxBufferSize(0);
            fail("buffer");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            p.setMaxHeaderValueSize(-1);
            fail("header");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testReceiveWithoutHandler() throws MimeParseException {
        MimeParser p = new MimeParser();
        try {
            p.receive(ByteBuffer.allocate(0));
            fail("no handler");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ===== header errors =====

    @Test
    public void testHeaderErrors() {
        expectFailure("NoColonHere\r\n\r\nbody", "colon");
        expectFailure(": value\r\n\r\n", "empty");
        expectFailure("Bad Name: v\r\n\r\n", "char");
        expectFailure("Bad\u0001Name: v\r\n\r\n", "char");
        expectFailure(" continuation first\r\n\r\n", "name");
    }

    @Test
    public void testHeaderLineTooLongAtEveryChunking() {
        StringBuilder sb = new StringBuilder("X: ");
        for (int i = 0; i < 1000; i++) {
            sb.append('a');
        }
        sb.append("\r\n\r\n");
        for (int i = 0; i < CHUNKS.length; i++) {
            try {
                run(sb.toString(), CHUNKS[i]);
                fail("too long");
            } catch (HeaderLineTooLongException expected) {
                assertNotNull(expected.getMessage());
            } catch (MimeParseException e) {
                fail("wrong exception " + e);
            }
        }
    }

    @Test
    public void testHeaderValueTooLongViaFolding() throws MimeParseException {
        MimeParser p = new MimeParser();
        p.setMaxHeaderValueSize(50);
        StringBuilder sb = new StringBuilder("X: a\r\n");
        for (int i = 0; i < 10; i++) {
            sb.append(" bbbbbbbbbb\r\n");
        }
        sb.append("\r\n");
        try {
            run(p, sb.toString(), 5);
            fail("too long");
        } catch (HeaderValueTooLongException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testLargeFoldedHeaderGrowsBuffer() throws MimeParseException {
        StringBuilder sb = new StringBuilder("Subject: s\r\n");
        for (int i = 0; i < 60; i++) {
            sb.append(" 0123456789012345678901234567890123456789\r\n");
        }
        sb.append("\r\nbody\r\n");
        Rec r = run(sb.toString(), 100);
        assertEquals("body\r\n", r.body.toString());
    }

    @Test
    public void testHeaderWithoutValueAndSpacedName() throws MimeParseException {
        Rec r = run("X-Empty:\r\nX-Spaced  : v\r\nContent-Description:   hello   \r\n\r\nb\r\n", 3);
        assertTrue(r.events.contains("desc:hello"));
        assertEquals("b\r\n", r.body.toString());
    }

    @Test
    public void testHeadersUnterminatedAtClose() throws MimeParseException {
        try {
            run("Subject: x", 3);
            fail("incomplete");
        } catch (MimeParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testHeaderOnlyWithFinalHeaderFlushedAtClose() throws MimeParseException {
        Rec r = run("Content-Description: tail\r\n", 4);
        assertTrue(r.events.contains("desc:tail"));
        assertTrue(r.events.contains("endHeaders"));
    }

    // ===== standard headers =====

    @Test
    public void testAllStandardHeadersDispatch() throws MimeParseException {
        String msg = "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Disposition: attachment; filename=a.txt\r\n"
                + "Content-ID: <abc@x>\r\n"
                + "Content-Description: d\r\n"
                + "Content-Transfer-Encoding: 7bit\r\n"
                + "X-Other: ignored\r\n"
                + "\r\nhello\r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertTrue(r.events.contains("version"));
            assertTrue(r.events.contains("type:text/plain"));
            assertTrue(r.events.contains("disp:attachment"));
            assertTrue(r.events.contains("cid:abc"));
            assertTrue(r.events.contains("desc:d"));
            assertTrue(r.events.contains("cte:7bit"));
            assertEquals("hello\r\n", r.body.toString());
        }
    }

    @Test
    public void testTransferEncodingVariants() throws MimeParseException {
        Rec r = run("Content-Transfer-Encoding: x-custom\r\n\r\nx", 5);
        assertTrue(r.events.contains("cte:x-custom"));
        r = run("Content-Transfer-Encoding: X-Custom\r\n\r\nx", 5);
        assertFalse("upper-case x- is not recognised", r.events.contains("cte:X-Custom"));
        r = run("Content-Transfer-Encoding: x-bad token\r\n\r\nx", 5);
        assertFalse(r.events.toString().contains("cte:"));
        r = run("Content-Transfer-Encoding: nonsense\r\n\r\nx", 5);
        assertFalse(r.events.toString().contains("cte:"));
        r = run("Content-Transfer-Encoding: 8bit\r\n\r\nx", 5);
        assertTrue(r.events.contains("cte:8bit"));
        r = run("Content-Transfer-Encoding: BINARY\r\n\r\nx", 5);
        assertTrue(r.events.contains("cte:BINARY"));
    }

    @Test
    public void testInvalidStandardHeadersAreDropped() throws MimeParseException {
        Rec r = run("Content-Type: ;;;\r\nContent-Disposition: ;;\r\nContent-ID: nonsense\r\nMIME-Version: x\r\n\r\nb", 4);
        assertFalse(r.events.toString().contains("version"));
        assertFalse(r.events.toString().contains("cid:"));
    }

    // ===== bodies with transfer encodings =====

    @Test
    public void testBase64BodyAtEveryChunking() throws MimeParseException {
        String msg = "Content-Transfer-Encoding: base64\r\n\r\nSGVsbG8sIFdv\r\ncmxkIQ==\r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertEquals("Hello, World!", r.body.toString());
        }
    }

    @Test
    public void testQuotedPrintableBodyAtEveryChunking() throws MimeParseException {
        String msg = "Content-Transfer-Encoding: quoted-printable\r\n\r\ncaf=C3=A9 soft=\r\nbreak\r\nline2=3D\r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertEquals("cafÃ© softbreak\r\nline2=\r\n", r.body.toString());
        }
    }

    @Test
    public void testSmallMaxBufferChunksBinaryAndDecoded() throws MimeParseException {
        MimeParser p = new MimeParser();
        p.setMaxBufferSize(4);
        Rec r = run(p, "\r\n0123456789\r\n", 100);
        assertEquals("0123456789\r\n", r.body.toString());
        MimeParser q = new MimeParser();
        q.setMaxBufferSize(4);
        Rec r2 = run(q, "Content-Transfer-Encoding: base64\r\n\r\nSGVsbG8sIFdvcmxkIQ==\r\n", 100);
        assertEquals("Hello, World!", r2.body.toString());
    }

    // ===== multipart =====

    private static final String MULTI = "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
            + "\r\n"
            + "preamble line\r\n"
            + "--outer\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
            + "part one\r\n"
            + "--outer\r\n"
            + "Content-Type: multipart/alternative; boundary=inner\r\n"
            + "\r\n"
            + "--inner\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
            + "alt a\r\n"
            + "--inner\r\n"
            + "Content-Transfer-Encoding: base64\r\n"
            + "\r\n"
            + "YWx0IGI=\r\n"
            + "--inner--\r\n"
            + "--outer--\r\n"
            + "epilogue\r\n";

    @Test
    public void testNestedMultipartAtEveryChunking() throws MimeParseException {
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(MULTI, CHUNKS[i]);
            assertTrue(r.events.contains("start:outer"));
            assertTrue(r.events.contains("start:inner"));
            assertEquals("part onealt aalt b", r.body.toString());
            assertTrue(r.unexpected.toString().startsWith("preamble line"));
        }
    }

    @Test
    public void testMultipartBareLfAndLfOnlyBoundaries() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\n\n--b\n\nA\n--b\n\nB\n--b--\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertEquals("AB", r.body.toString());
        }
    }

    @Test
    public void testBoundaryTransportPaddingAtEveryChunking() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b  \r\n\r\nA\r\n--b\t \r\n\r\nB\r\n--b--\t \r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertEquals("chunk " + CHUNKS[i], "AB", r.body.toString());
        }
    }

    @Test
    public void testBoundaryTransportPaddingAtEverySplitOffset() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b \t\r\n\r\nA\r\n--b--  \r\n";
        byte[] data = msg.getBytes(StandardCharsets.ISO_8859_1);
        for (int split = 1; split < data.length; split++) {
            MimeParser p = new MimeParser();
            Rec rec = new Rec();
            p.setHandler(rec);
            ByteBuffer buf = ByteBuffer.allocate(data.length);
            buf.put(data, 0, split);
            buf.flip();
            p.receive(buf);
            buf.compact();
            buf.put(data, split, data.length - split);
            buf.flip();
            p.receive(buf);
            p.close();
            assertEquals("split " + split, "A", rec.body.toString());
        }
    }

    @Test
    public void testBoundaryWithOtherTrailingCharactersIsContent() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n\r\n"
                + "--b x\r\n--b--x\r\n--b- \r\nend\r\n--b--\r\n";
        Rec r = run(msg, 3);
        assertTrue(r.body.toString().contains("--b x"));
        assertTrue(r.body.toString().contains("--b--x"));
        assertTrue(r.body.toString().contains("--b- "));
    }

    @Test
    public void testBoundaryLookalikesAreContent() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=bnd\r\n\r\n--bnd\r\n\r\n"
                + "--bndx\r\n--bn\r\n--\r\n-\r\n--bnd-x\r\n--bnd--x\r\nreal\r\n--bnd--\r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertTrue(r.body.toString().contains("--bndx"));
            assertTrue(r.body.toString().contains("--bnd-x"));
            assertTrue(r.body.toString().contains("--bnd--x"));
            assertTrue(r.body.toString().endsWith("real"));
        }
    }

    @Test
    public void testInvalidBoundaryIsIgnored() throws MimeParseException {
        Rec r = run("Content-Type: multipart/mixed; boundary=\"has trailing space \"\r\n\r\nplain\r\n", 5);
        assertEquals("plain\r\n", r.body.toString());
        r = run("Content-Type: multipart/mixed\r\n\r\nplain\r\n", 5);
        assertEquals("plain\r\n", r.body.toString());
    }

    @Test
    public void testDuplicateContentTypeReplacesBoundary() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=one\r\n"
                + "Content-Type: multipart/mixed; boundary=two\r\n\r\n--two\r\n\r\nX\r\n--two--\r\n";
        Rec r = run(msg, 6);
        assertEquals("X", r.body.toString());
    }

    @Test
    public void testUnclosedMultipartFailsAtClose() {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n\r\nX\r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            try {
                run(msg, CHUNKS[i]);
                fail("unclosed");
            } catch (MimeParseException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void testPartialLineInMultipartAtCloseFails() {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n\r\npartial";
        try {
            run(msg, 4);
            fail("incomplete multipart");
        } catch (MimeParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testPartialBodyLineAtCloseIsAccepted() throws MimeParseException {
        Rec r = run("\r\nfirst line\r\nno newline at end", 3);
        assertTrue(r.body.toString().startsWith("first line\r\n"));
        assertTrue(r.events.contains("end:null"));
    }

    @Test
    public void testEncodedPartsDropTheLineEndBeforeTheBoundary() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nabc=3D\r\ndef\r\n"
                + "--b\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nxyz\r\n--b--\r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertEquals("chunk " + CHUNKS[i], "abc=\r\ndefxyz", r.body.toString());
        }
    }

    @Test
    public void testUnpaddedBase64PartIsFlushedAtBoundary() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                + "--b\r\nContent-Transfer-Encoding: base64\r\n\r\nSGk\r\n--b--\r\n";
        Rec r = run(msg, 1000);
        assertEquals("Hi", r.body.toString());
    }

    // ===== CR line endings =====

    @Test
    public void testBareCrLineEndings() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\r--b\rContent-Description: d\r\rbody\r--b--\r\n";
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(new CrParser(), msg, CHUNKS[i]);
            assertTrue(r.events.contains("desc:d"));
            assertEquals("body", r.body.toString());
        }
    }

    @Test
    public void testBareCrDecodedBodyBeforeBoundary() throws MimeParseException {
        String msg = "Content-Type: multipart/mixed; boundary=b\r\r--b\rContent-Transfer-Encoding: quoted-printable\r\rabc\r--b--\r\n";
        Rec r = run(new CrParser(), msg, 3);
        assertEquals("abc", r.body.toString());
    }

    // ===== locator =====

    @Test
    public void testLocatorOffsetUnaffectedByChunking() throws MimeParseException {
        String msg = "Subject: hello\r\nTo: world\r\n\r\nbody\r\n";
        long expectedOffset = msg.indexOf("body");
        for (int i = 0; i < CHUNKS.length; i++) {
            Rec r = run(msg, CHUNKS[i]);
            assertEquals("chunk " + CHUNKS[i], expectedOffset, r.endHeadersOffset);
            assertEquals(3L, r.endHeadersLine);
        }
    }

    // ===== reuse =====

    @Test
    public void testResetAllowsReuse() throws MimeParseException {
        MimeParser p = new MimeParser();
        Rec first = run(p, "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n\r\nA\r\n--b--\r\n", 4);
        assertEquals("A", first.body.toString());
        p.reset();
        Rec second = run(p, "Content-Transfer-Encoding: base64\r\n\r\nQg==\r\n", 4);
        assertEquals("B", second.body.toString());
    }

    @Test
    public void testIsUnderflowReflectsPartialLine() throws MimeParseException {
        MimeParser p = new MimeParser();
        p.setHandler(new Rec());
        ByteBuffer buf = ByteBuffer.wrap("Subject: x".getBytes(StandardCharsets.ISO_8859_1));
        p.receive(buf);
        assertTrue(p.isUnderflow());
        assertEquals(0, buf.position());
        ByteBuffer rest = ByteBuffer.wrap("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        p.receive(rest);
        assertFalse(p.isUnderflow());
    }

    // ===== statics =====

    @Test
    public void testIndexOfAndDecodeSlice() {
        ByteBuffer b = ByteBuffer.wrap("abc:def".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(3, MimeParser.indexOf(b, (byte) ':'));
        assertEquals(-1, MimeParser.indexOf(b, (byte) '!'));
        b.position(4);
        assertEquals(-1, MimeParser.indexOf(b, (byte) ':'));
    }
}
