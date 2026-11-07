/*
 * NntpAttachmentsTest.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.mime.UuScanner;
import org.bluezoo.gumdrop.nntp.client.NntpClient.StartTls;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Taking the uuencoded attachments out of articles, which is how files
 * travel on Usenet: as blocks in the body, not as MIME parts.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpAttachmentsTest {

    /** What the handler was told, with adjacent text and content merged. */
    private static final class Found implements UuScanner.Handler {
        final StringBuilder text = new StringBuilder();
        final List<String> events = new ArrayList<String>();
        final List<byte[]> files = new ArrayList<byte[]>();
        private ByteArrayOutputStream current;

        @Override
        public void text(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            text.append(new String(b, StandardCharsets.ISO_8859_1));
        }

        @Override
        public void begin(int mode, String name) {
            events.add("begin " + Integer.toOctalString(mode) + " " + name);
            current = new ByteArrayOutputStream();
        }

        @Override
        public void content(ByteBuffer data) {
            while (data.hasRemaining()) {
                current.write(data.get());
            }
        }

        @Override
        public void end(boolean complete) {
            events.add(complete ? "end" : "cut off");
            files.add(current.toByteArray());
            current = null;
        }
    }

    private static final class Outcome implements NntpCallback<Void> {
        boolean done;
        NntpException error;

        @Override
        public void completed(Void result) {
            done = true;
        }

        @Override
        public void failed(NntpException e) {
            done = true;
            error = e;
        }
    }

    private static final class Events implements NntpClientHandler {
        NntpSession session;

        @Override
        public void connected(NntpSession s, NntpGreeting g) {
            session = s;
        }

        @Override
        public void error(Exception cause) {
        }

        @Override
        public void disconnected() {
        }
    }

    private RecordingStubEndpoint wire;
    private Events events;
    private NntpClientProtocolHandler handler;

    @Before
    public void setUp() {
        wire = new RecordingStubEndpoint();
        events = new Events();
        handler = new NntpClientProtocolHandler(events, null, null, StartTls.NEVER, 0, null, "news.example.com");
        handler.connected(wire);
        server("200 hi\r\n101 caps\r\n.\r\n");
        assertNotNull(events.session);
        wire.clearResponses();
    }

    private void server(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private void serverByByte(String text) {
        byte[] b = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(b.length + 1);
        for (int i = 0; i < b.length; i++) {
            buf.put(b[i]);
            buf.flip();
            handler.receive(buf);
            buf.compact();
        }
    }

    // ── making articles ──

    private static char chr(int v) {
        return v == 0 ? '`' : (char) (v + 32);
    }

    /** A uuencoded block as an old news reader would write it. */
    static String uuencode(byte[] data, String name) {
        StringBuilder sb = new StringBuilder("begin 644 ").append(name).append("\r\n");
        for (int p = 0; p < data.length; p += 45) {
            int n = Math.min(45, data.length - p);
            sb.append(chr(n));
            for (int i = 0; i < n; i += 3) {
                int b0 = data[p + i] & 0xff;
                int b1 = i + 1 < n ? data[p + i + 1] & 0xff : 0;
                int b2 = i + 2 < n ? data[p + i + 2] & 0xff : 0;
                sb.append(chr(b0 >> 2));
                sb.append(chr(((b0 << 4) | (b1 >> 4)) & 63));
                sb.append(chr(((b1 << 2) | (b2 >> 6)) & 63));
                sb.append(chr(b2 & 63));
            }
            sb.append("\r\n");
        }
        return sb.append("`\r\nend\r\n").toString();
    }

    /** What the server puts on the wire for an article: dot-stuffed, then the terminator. */
    static String onTheWire(String article) {
        StringBuilder sb = new StringBuilder();
        for (String line : article.split("\r\n", -1)) {
            if (line.startsWith(".")) {
                sb.append('.');
            }
            sb.append(line).append("\r\n");
        }
        // split leaves one empty piece after the final line ending
        sb.setLength(sb.length() - 2);
        return sb.append(".\r\n").toString();
    }

    /** 59 bytes: a full line of 45 and a last one of 14, whose length character is a dot. */
    private static byte[] data59() {
        byte[] d = new byte[59];
        for (int i = 0; i < d.length; i++) {
            d[i] = (byte) (i * 7 + 3);
        }
        return d;
    }

    private static final String HEADERS = "From: a@example.com\r\nSubject: pic\r\n\r\n";

    // ── tests ──

    @Test
    public void testAttachmentIsDecodedAndTheTextAroundItKept() {
        byte[] data = data59();
        String article = HEADERS + "Here is the picture:\r\n" + uuencode(data, "pic.jpg") + "-- \r\nbye\r\n";
        assertTrue("the last data line begins with a dot", uuencode(data, "x").contains("\r\n.") );
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments(5, found, done);
        assertEquals("ARTICLE 5", wire.getResponses().get(0));
        server("220 5 <a@b> article\r\n" + onTheWire(article));
        assertTrue(done.done);
        assertNull(done.error);
        assertEquals(HEADERS + "Here is the picture:\r\n-- \r\nbye\r\n", found.text.toString());
        assertEquals(java.util.Arrays.asList("begin 644 pic.jpg", "end"), found.events);
        assertArrayEquals(data, found.files.get(0));
    }

    @Test
    public void testTheSameWhateverTheTcpSegmentation() {
        byte[] data = data59();
        String article = HEADERS + "text\r\n" + uuencode(data, "pic.jpg") + "tail\r\n";
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments(1, found, done);
        serverByByte("220 1 <a@b> article\r\n" + onTheWire(article));
        assertTrue(done.done);
        assertEquals(HEADERS + "text\r\ntail\r\n", found.text.toString());
        assertArrayEquals(data, found.files.get(0));
    }

    @Test
    public void testSeveralAttachmentsInOneArticle() {
        byte[] a = "first file".getBytes(StandardCharsets.ISO_8859_1);
        byte[] b = data59();
        String article = HEADERS + "two files\r\n" + uuencode(a, "a.txt") + "and\r\n" + uuencode(b, "b.bin");
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments(1, found, done);
        server("220 1 <a@b> article\r\n" + onTheWire(article));
        assertTrue(done.done);
        assertEquals(java.util.Arrays.asList("begin 644 a.txt", "end", "begin 644 b.bin", "end"), found.events);
        assertArrayEquals(a, found.files.get(0));
        assertArrayEquals(b, found.files.get(1));
        assertEquals(HEADERS + "two files\r\nand\r\n", found.text.toString());
    }

    @Test
    public void testAttachmentWithNoEndLineIsReportedAsCutOff() {
        String article = HEADERS + "begin 644 f\r\n#0V%T\r\n";
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments(1, found, done);
        server("220 1 <a@b> article\r\n" + onTheWire(article));
        assertTrue(done.done);
        assertNull(done.error);
        assertEquals(java.util.Arrays.asList("begin 644 f", "cut off"), found.events);
        assertEquals("Cat", new String(found.files.get(0), StandardCharsets.ISO_8859_1));
    }

    @Test
    public void testConnectionLostInTheMiddleOfAnAttachment() {
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments(1, found, done);
        server("220 1 <a@b> article\r\n" + HEADERS + "begin 644 f\r\n#0V%T\r\n");
        handler.disconnected();
        assertTrue(done.done);
        assertNotNull(done.error);
        assertEquals(java.util.Arrays.asList("begin 644 f", "cut off"), found.events);
    }

    @Test
    public void testByMessageId() {
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments("<a@b>", found, done);
        assertEquals("ARTICLE <a@b>", wire.getResponses().get(0));
        server("220 0 <a@b> article\r\n" + onTheWire(HEADERS + "just text\r\n"));
        assertTrue(done.done);
        assertEquals(HEADERS + "just text\r\n", found.text.toString());
        assertTrue(found.events.isEmpty());
    }

    @Test
    public void testNoSuchArticle() {
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments(9, found, done);
        server("423 no such article\r\n");
        assertEquals(423, done.error.getCode());
        assertTrue(found.events.isEmpty());
        assertEquals("", found.text.toString());
    }

    @Test
    public void testProseThatMentionsBeginIsText() {
        String article = HEADERS + "begin the work\r\nbegin 644\r\nbeginning 644 x\r\nend of story\r\n";
        Found found = new Found();
        Outcome done = new Outcome();
        events.session.articleWithAttachments(1, found, done);
        server("220 1 <a@b> article\r\n" + onTheWire(article));
        assertTrue(done.done);
        assertTrue(found.events.toString(), found.events.isEmpty());
        assertEquals(article, found.text.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLineBreakInMessageIdIsRefused() {
        events.session.articleWithAttachments("<a@b>\r\nQUIT", new Found(), new Outcome());
    }

    // ── posting attachments ──

    /** Everything the client sends from here on. */
    private ByteArrayOutputStream capture() {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        wire.setSendFilter(new RecordingStubEndpoint.SendFilter() {
            @Override
            public byte[] filter(byte[] outbound) {
                sent.write(outbound, 0, outbound.length);
                return outbound;
            }
        });
        return sent;
    }

    /** The article as the server would receive it: the POST payload, unstuffed. */
    private static String received(ByteArrayOutputStream sent) {
        String all = new String(sent.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(all, all.startsWith("POST\r\n"));
        String payload = all.substring("POST\r\n".length());
        assertTrue(payload, payload.endsWith("\r\n.\r\n"));
        payload = payload.substring(0, payload.length() - 3); // the terminating dot line
        StringBuilder sb = new StringBuilder();
        for (String line : payload.split("\r\n", -1)) {
            sb.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
        }
        sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String post(String article, List<NntpAttachment> attachments, Outcome done) {
        ByteArrayOutputStream sent = capture();
        events.session.postWithAttachments(article.getBytes(StandardCharsets.ISO_8859_1), attachments, done);
        server("340 send it\r\n");
        return received(sent);
    }

    private static NntpAttachment attachment(String name, byte[] data) {
        return new NntpAttachment(name, 0644, data);
    }

    @Test
    public void testPostedAttachmentsComeBackOutOfTheArticle() {
        byte[] first = "first file".getBytes(StandardCharsets.ISO_8859_1);
        byte[] second = data59();
        Outcome done = new Outcome();
        String article = post(HEADERS + "Two files for you.\r\n",
                java.util.Arrays.asList(attachment("a.txt", first), attachment("b.bin", second)), done);
        server("240 received\r\n");
        assertTrue(done.done);
        assertNull(done.error);
        Found found = new Found();
        UuScanner scanner = new UuScanner(found);
        scanner.receive(ByteBuffer.wrap(article.getBytes(StandardCharsets.ISO_8859_1)));
        scanner.finish();
        assertEquals(HEADERS + "Two files for you.\r\n", found.text.toString());
        assertEquals(java.util.Arrays.asList("begin 644 a.txt", "end", "begin 644 b.bin", "end"), found.events);
        assertArrayEquals(first, found.files.get(0));
        assertArrayEquals(second, found.files.get(1));
    }

    @Test
    public void testLineThatBeginsWithADotIsStuffedOnTheWire() {
        ByteArrayOutputStream sent = capture();
        events.session.postWithAttachments(latin1(HEADERS + "x\r\n"),
                java.util.Arrays.asList(attachment("b.bin", data59())), new Outcome());
        server("340 go\r\n");
        String wireText = new String(sent.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(wireText, wireText.contains("\r\n..") );
        // and it is the same article once unstuffed
        assertEquals(uuencode(data59(), "b.bin"), received(sent).substring((HEADERS + "x\r\n").length()));
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    public void testFinalLineEndingOfTheTextIsSupplied() {
        Outcome done = new Outcome();
        String article = post(HEADERS + "no line ending", java.util.Arrays.asList(attachment("f", latin1("Cat"))), done);
        assertTrue(article, article.startsWith(HEADERS + "no line ending\r\nbegin 644 f\r\n"));
        assertTrue(article, article.endsWith("end\r\n"));
    }

    @Test
    public void testNoAttachmentsIsAnOrdinaryPost() {
        Outcome done = new Outcome();
        String article = post(HEADERS + "text\r\n", java.util.Collections.<NntpAttachment>emptyList(), done);
        assertEquals(HEADERS + "text\r\n", article);
    }

    @Test
    public void testEmptyAttachment() {
        Outcome done = new Outcome();
        String article = post(HEADERS + "t\r\n", java.util.Arrays.asList(attachment("empty", new byte[0])), done);
        assertTrue(article, article.endsWith("begin 644 empty\r\n`\r\nend\r\n"));
    }

    @Test
    public void testLargeAttachmentRoundTrips() {
        byte[] big = new byte[300000];
        new java.util.Random(5).nextBytes(big);
        Outcome done = new Outcome();
        String article = post(HEADERS + "big\r\n", java.util.Arrays.asList(attachment("big.bin", big)), done);
        Found found = new Found();
        UuScanner scanner = new UuScanner(found);
        scanner.receive(ByteBuffer.wrap(latin1(article)));
        scanner.finish();
        assertArrayEquals(big, found.files.get(0));
    }

    @Test
    public void testPostingRefused() {
        Outcome done = new Outcome();
        events.session.postWithAttachments(latin1(HEADERS), java.util.Arrays.asList(attachment("f", latin1("x"))), done);
        server("440 posting not permitted\r\n");
        assertEquals(440, done.error.getCode());
    }

    @Test
    public void testAttachmentsMustBeValid() {
        String[] names = {null, "", "a\nb", "a\u0000b"};
        for (int i = 0; i < names.length; i++) {
            try {
                new NntpAttachment(names[i], 0644, new byte[0]);
                org.junit.Assert.fail("accepted a bad name");
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
        for (int mode : new int[] {-1, 01000}) {
            try {
                new NntpAttachment("f", mode, new byte[0]);
                org.junit.Assert.fail("accepted a bad mode");
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
        try {
            new NntpAttachment("f", 0644, (byte[]) null);
            org.junit.Assert.fail("accepted no content");
        } catch (IllegalArgumentException expected) {
            // good
        }
        try {
            new NntpAttachment("f", 0644, (java.nio.file.Path) null);
            org.junit.Assert.fail("accepted no file");
        } catch (IllegalArgumentException expected) {
            // good
        }
        try {
            new NntpAttachment("f", 0644, (NntpAttachment.Source) null);
            org.junit.Assert.fail("accepted no source");
        } catch (IllegalArgumentException expected) {
            // good
        }
        try {
            events.session.postWithAttachments(latin1(HEADERS), java.util.Arrays.asList((NntpAttachment) null),
                    new Outcome());
            org.junit.Assert.fail("accepted a null attachment");
        } catch (IllegalArgumentException expected) {
            // good
        }
        NntpAttachment a = new NntpAttachment("f.txt", 0600, latin1("x"));
        assertEquals("f.txt", a.getName());
        assertEquals(0600, a.getMode());
    }
}
