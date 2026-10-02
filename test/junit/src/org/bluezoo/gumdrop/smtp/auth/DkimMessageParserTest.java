/*
 * DkimMessageParserTest
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

package org.bluezoo.gumdrop.smtp.auth;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.mime.HeaderLineTooLongException;
import org.bluezoo.gumdrop.mime.HeaderValueTooLongException;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises {@link DkimMessageParser}: raw header capture (folding, ordering,
 * lookup), header limits, the DKIM and ARC message signature accessors, and
 * the RFC 6376 body hash under simple and relaxed canonicalization with and
 * without a length limit. Expected hashes are computed independently from the
 * canonical bytes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DkimMessageParserTest {

    private DkimMessageParser parser;

    @Before
    public void setUp() {
        parser = new DkimMessageParser();
        parser.setMessageHandler((MessageHandler) Proxy.newProxyInstance(
                MessageHandler.class.getClassLoader(),
                new Class<?>[] {MessageHandler.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                }));
    }

    private void feed(String message) throws MimeParseException {
        parser.receive(ByteBuffer.wrap(message.getBytes(StandardCharsets.ISO_8859_1)));
        parser.close();
    }

    private static byte[] sha256(String canonical) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String sig(String algorithm, String canon, String extra) {
        return "DKIM-Signature: v=1; a=" + algorithm + "; c=" + canon
                + "; d=example.com; s=sel; h=from; bh=AAAA; b=BBBB" + extra + "\r\n";
    }

    // ---- raw headers ----

    @Test
    public void rawHeadersKeepOrderFoldingAndCase() throws Exception {
        feed("Received: one\r\nSubject: first\r\n part two\r\n\tpart three\r\n"
                + "received: two\r\n\r\nbody\r\n");
        List<DkimMessageParser.RawHeader> all = parser.getRawHeaders();
        assertEquals(3, all.size());
        assertEquals("Received", all.get(0).getName());
        assertEquals("Subject: first\r\n part two\r\n\tpart three\r\n", all.get(1).asString());
        assertTrue(parser.isHeadersComplete());
        assertEquals(2, parser.getAllRawHeaders("RECEIVED").size());
        assertEquals("Received: one\r\n", parser.getRawHeader("received").asString());
        assertEquals(2, parser.getAllHeaderBytes("received").size());
        assertEquals("received: two\r\n",
                new String(parser.getAllHeaderBytes("received").get(1), StandardCharsets.ISO_8859_1));
        assertArrayEquals("Subject: first\r\n part two\r\n\tpart three\r\n"
                .getBytes(StandardCharsets.ISO_8859_1), parser.getHeaderBytes("subject"));
    }

    @Test
    public void unknownHeaderLookupsAreEmpty() throws Exception {
        feed("From: a@example.com\r\n\r\nx\r\n");
        assertNull(parser.getRawHeader("subject"));
        assertNull(parser.getHeaderBytes("subject"));
        assertTrue(parser.getAllRawHeaders("subject").isEmpty());
        assertTrue(parser.getAllHeaderBytes("subject").isEmpty());
        assertNull(parser.getDKIMSignature());
        assertNull(parser.getArcMessageSignature());
        assertNull(parser.getBodyHash());
    }

    @Test
    public void unfoldingRemovesOnlyFoldLineEnds() {
        byte[] crlf = "A: x\r\n y\r\n\tz\r\n".getBytes(StandardCharsets.ISO_8859_1);
        DkimMessageParser.RawHeader h = new DkimMessageParser.RawHeader("A", crlf);
        assertEquals("A: x y\tz\r\n", h.asStringUnfolded());
        assertEquals("A: x\r\n y\r\n\tz\r\n", h.asString());
        byte[] lf = "A: x\n y\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("A: x y\n",
                new DkimMessageParser.RawHeader("A", lf).asStringUnfolded());
        byte[] plain = "A: x\r\n".getBytes(StandardCharsets.ISO_8859_1);
        DkimMessageParser.RawHeader flat = new DkimMessageParser.RawHeader("A", plain);
        assertSame(plain, flat.getBytesUnfolded());
        byte[] mixed = "A: x\r\nB: y\r\n z\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("A: x\r\nB: y z\n",
                new DkimMessageParser.RawHeader("A", mixed).asStringUnfolded());
    }

    @Test
    public void lineWithoutColonIsAParseError() {
        try {
            feed("From: a@example.com\r\nno colon here\r\nTo: b@example.com\r\n\r\n");
            fail("header without colon");
        } catch (MimeParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("colon"));
        }
        assertEquals(1, parser.getRawHeaders().size());
    }

    @Test
    public void continuationBeforeAnyHeaderIsAParseError() {
        try {
            feed(" orphan\r\nFrom: a@example.com\r\n\r\n");
            fail("continuation without a header");
        } catch (MimeParseException expected) {
            assertNotNull(expected.getMessage());
        }
        assertTrue(parser.getRawHeaders().isEmpty());
    }

    @Test
    public void overlongHeaderLineIsRejected() {
        StringBuilder sb = new StringBuilder("X-Long: ");
        for (int i = 0; i < 1000; i++) {
            sb.append('a');
        }
        sb.append("\r\n\r\n");
        try {
            feed(sb.toString());
            fail("line over 998 octets");
        } catch (HeaderLineTooLongException expected) {
            assertNotNull(expected.getMessage());
        } catch (MimeParseException other) {
            fail("wrong exception " + other);
        }
    }

    @Test
    public void overlongFoldedHeaderValueIsRejected() {
        parser.setMaxHeaderValueSize(40);
        try {
            feed("X-Folded: aaaaaaaaaaaaaaaaaaaa\r\n bbbbbbbbbbbbbbbbbbbb\r\n cccccccccccc\r\n\r\n");
            fail("value over the limit");
        } catch (HeaderValueTooLongException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("40"));
        } catch (MimeParseException other) {
            fail("wrong exception " + other);
        }
    }

    // ---- signature accessors ----

    @Test
    public void dkimSignatureIsParsedFromTheHeader() throws Exception {
        feed(sig("rsa-sha256", "relaxed/simple", "") + "From: a@example.com\r\n\r\nbody\r\n");
        DkimSignature s = parser.getDKIMSignature();
        assertNotNull(s);
        assertEquals("example.com", s.getDomain());
        assertEquals("sel", s.getSelector());
        assertEquals("relaxed", s.getHeaderCanonicalization());
        assertEquals("simple", s.getBodyCanonicalization());
    }

    @Test
    public void arcMessageSignatureIsFoundWhenNoDkimSignature() throws Exception {
        feed("ARC-Message-Signature: i=1; v=1; a=rsa-sha256; c=relaxed/relaxed; d=arc.example;"
                + " s=k; h=from; bh=AAAA; b=BBBB\r\nFrom: a@example.com\r\n\r\nbody\r\n");
        assertNull(parser.getDKIMSignature());
        DkimSignature s = parser.getArcMessageSignature();
        assertNotNull(s);
        assertEquals("arc.example", s.getDomain());
        assertNotNull(parser.getBodyHash());
    }

    // ---- body hash ----

    @Test
    public void simpleCanonicalizationDropsTrailingEmptyLinesOnly() throws Exception {
        feed(sig("rsa-sha256", "simple/simple", "")
                + "From: a@example.com\r\n\r\nline1\r\n\r\nline2 \r\n\r\n\r\n");
        assertArrayEquals(sha256("line1\r\n\r\nline2 \r\n"), parser.getBodyHash());
    }

    @Test
    public void relaxedCanonicalizationCompressesWhitespace() throws Exception {
        feed(sig("rsa-sha256", "relaxed/relaxed", "")
                + "From: a@example.com\r\n\r\nline  one \t\r\n \t \r\n\tline\ttwo\r\n\r\n");
        assertArrayEquals(sha256("line one\r\n\r\n line two\r\n"), parser.getBodyHash());
    }

    @Test
    public void lengthLimitTruncatesTheHashedBody() throws Exception {
        feed(sig("rsa-sha256", "simple/simple", "; l=5")
                + "From: a@example.com\r\n\r\nabcdefghij\r\nsecond\r\n");
        assertArrayEquals(sha256("abcde"), parser.getBodyHash());
    }

    @Test
    public void lengthLimitAlsoAppliesToRelaxedBodies() throws Exception {
        feed(sig("rsa-sha256", "relaxed/relaxed", "; l=8")
                + "From: a@example.com\r\n\r\nab  cd\r\nef gh ij\r\n");
        assertArrayEquals(sha256("ab cd\r\ne"), parser.getBodyHash());
    }

    @Test
    public void sha1AlgorithmIsSupported() throws Exception {
        feed(sig("rsa-sha1", "simple/simple", "") + "From: a@example.com\r\n\r\nabc\r\n");
        byte[] expected = MessageDigest.getInstance("SHA-1")
                .digest("abc\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertArrayEquals(expected, parser.getBodyHash());
    }

    @Test
    public void unknownAlgorithmLeavesNoBodyHash() throws Exception {
        feed(sig("rsa-md5", "simple/simple", "") + "From: a@example.com\r\n\r\nabc\r\n");
        assertNull(parser.getBodyHash());
    }

    @Test
    public void explicitInitBodyHashOverridesAutomaticChoice() throws Exception {
        parser.initBodyHash("SHA-256", true, -1);
        feed("From: a@example.com\r\n\r\nx   y\r\n");
        assertArrayEquals(sha256("x y\r\n"), parser.getBodyHash());
    }

    @Test
    public void unsupportedExplicitAlgorithmIsReported() {
        try {
            parser.initBodyHash("NOPE-999", false, -1);
            fail("no such algorithm");
        } catch (java.security.NoSuchAlgorithmException expected) {
            assertFalse(expected.getMessage() == null);
        }
    }

    @Test
    public void resetDiscardsHeadersAndBodyHash() throws Exception {
        feed(sig("rsa-sha256", "simple/simple", "") + "From: a@example.com\r\n\r\nabc\r\n");
        assertNotNull(parser.getBodyHash());
        parser.reset();
        assertTrue(parser.getRawHeaders().isEmpty());
        assertFalse(parser.isHeadersComplete());
        assertNull(parser.getBodyHash());
        feed("To: b@example.com\r\n\r\nz\r\n");
        assertEquals(1, parser.getRawHeaders().size());
        assertNull(parser.getBodyHash());
    }
}
