/*
 * ZoneFileSyntaxTest.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Zone file syntax tests: record forms, directives, errors and byte-at-a-time
 * push parsing for {@link ZoneFileParser}, {@link ZoneFileLexer} and
 * {@link ZoneFileLoader}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ZoneFileSyntaxTest {

    private static final String HEAD = ""
            + "$ORIGIN example.com.\n"
            + "$TTL 300\n"
            + "@ IN SOA ns1.example.com. host.example.com. 7 7200 3600 1209600 60\n"
            + "@ IN NS ns1.example.com.\n";

    private final Path tmp = newTmp();

    private static Path newTmp() {
        try {
            return MemoryTemp.createTempDirectory("syntax");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path write(String name, String text) throws IOException {
        Path f = tmp.resolve(name);
        Files.write(f, text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private ZoneFile load(String body) throws IOException {
        Path p = write("zone" + System.identityHashCode(body) + ".zone", HEAD + body);
        return ZoneFile.load(p);
    }

    private void assertLoadFails(String body, String messagePart) throws IOException {
        try {
            load(body);
            fail("expected IOException containing: " + messagePart);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private static List<DnsResourceRecord> answers(ZoneFile zone, String name, DnsType type) {
        ZoneLookupResult r = zone.lookup(name, type);
        assertEquals(ZoneLookupResult.STATUS_ANSWER, r.getStatus());
        return r.getAnswers();
    }

    @Test
    public void testRecordTypesAndExplicitTtlAndClass() throws Exception {
        ZoneFile z = load(""
                + "a 60 IN A 192.0.2.1\n"
                + "a2 IN 90 A 192.0.2.2\n"
                + "v6 AAAA 2001:db8::1\n"
                + "mx IN MX 10 mail.example.com.\n"
                + "ptr IN PTR host.example.com.\n"
                + "alias IN CNAME a\n");
        assertEquals(60, answers(z, "a.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(1, answers(z, "v6.example.com.", DnsType.AAAA).size());
        assertEquals(10, answers(z, "mx.example.com.", DnsType.MX).get(0).getMXPreference());
        assertEquals("host.example.com",
                answers(z, "ptr.example.com.", DnsType.PTR).get(0).getTargetName());
        assertEquals("a.example.com",
                answers(z, "alias.example.com.", DnsType.CNAME).get(0).getTargetName());
    }

    @Test
    public void testClassMayBeOmittedBeforeTwoLetterTypes() throws Exception {
        // RFC 1035 section 5.1: the class is optional; NS and MX are two-letter
        // type mnemonics and must not be mistaken for a class.
        ZoneFile z = load(""
                + "sub NS ns.sub.example.com.\n"
                + "mx 120 MX 20 mail.example.com.\n"
                + "mx2 MX 5 mail2.example.com.\n");
        assertEquals(1, answers(z, "sub.example.com.", DnsType.NS).size());
        DnsResourceRecord mx = answers(z, "mx.example.com.", DnsType.MX).get(0);
        assertEquals(20, mx.getMXPreference());
        assertEquals(120, mx.getTTL());
        assertEquals(5, answers(z, "mx2.example.com.", DnsType.MX).get(0).getMXPreference());
    }

    @Test
    public void testRelativeNamesInRdataAreExpandedAgainstOrigin() throws Exception {
        Path p = write("rel.zone", ""
                + "$ORIGIN example.com.\n"
                + "@ IN SOA ns1 hostmaster 1 2 3 4 5\n"
                + "@ IN NS ns1\n"
                + "@ IN MX 10 mail\n"
                + "www IN CNAME @\n"
                + "ptr IN PTR www\n"
                + "ns1 IN A 192.0.2.1\n");
        ZoneFile z = ZoneFile.load(p);
        assertEquals("ns1.example.com.", z.copySoaData().mname);
        assertEquals("hostmaster.example.com.", z.copySoaData().rname);
        assertEquals("ns1.example.com", answers(z, "example.com.", DnsType.NS).get(0).getTargetName());
        assertEquals("mail.example.com", answers(z, "example.com.", DnsType.MX).get(0).getMXExchange());
        assertEquals("example.com", answers(z, "www.example.com.", DnsType.CNAME).get(0).getTargetName());
        assertEquals("www.example.com", answers(z, "ptr.example.com.", DnsType.PTR).get(0).getTargetName());
    }

    @Test
    public void testLaterOriginDoesNotChangeZoneOrigin() throws Exception {
        ZoneFile z = load("$ORIGIN sub.example.com.\nh IN A 192.0.2.10\n");
        assertEquals("example.com.", z.getOrigin());
    }

    @Test
    public void testTxtQuotingCommentsAndCrLf() throws Exception {
        String body = "t1 IN TXT \"has ; semicolon\" ; trailing comment\r\n"
                + "; whole line comment\r\n"
                + "\r\n"
                + "t2 IN TXT plain\r\n"
                + "t3 IN TXT ( \"a\"\n \"b\" )\n";
        ZoneFile z = load(body);
        assertEquals("has ; semicolon", answers(z, "t1.example.com.", DnsType.TXT).get(0).getText());
        assertEquals("plain", answers(z, "t2.example.com.", DnsType.TXT).get(0).getText());
        // RFC 1035 section 3.3.14: two quoted strings are two character-strings
        assertEquals("ab", answers(z, "t3.example.com.", DnsType.TXT).get(0).getText());
    }

    @Test
    public void testEmptyQuotedTxtString() throws Exception {
        ZoneFile z = load("empty IN TXT \"\"\n");
        List<DnsResourceRecord> r = answers(z, "empty.example.com.", DnsType.TXT);
        assertEquals(1, r.size());
        assertEquals("", r.get(0).getText());
    }

    @Test
    public void testWildcardAndAbsoluteOwners() throws Exception {
        ZoneFile z = load(""
                + "* IN A 192.0.2.50\n"
                + "abs.other.com.example.com. IN A 192.0.2.51\n");
        ZoneLookupResult r = z.lookup("anything.example.com.", DnsType.A);
        assertEquals(ZoneLookupResult.STATUS_ANSWER, r.getStatus());
        assertTrue(r.isFromWildcard());
        assertEquals(1, answers(z, "abs.other.com.example.com.", DnsType.A).size());
    }

    @Test
    public void testUnknownDirectiveIsIgnored() throws Exception {
        ZoneFile z = load("$FOO bar baz\nx IN A 192.0.2.9\n");
        assertEquals(1, answers(z, "x.example.com.", DnsType.A).size());
    }

    @Test
    public void testOriginChangeAndRelativeNames() throws Exception {
        ZoneFile z = load("$ORIGIN sub.example.com.\nh IN A 192.0.2.10\n@ IN A 192.0.2.11\n");
        assertEquals("example.com.", z.getOrigin());
        assertEquals(1, answers(z, "h.sub.example.com.", DnsType.A).size());
        assertEquals(1, answers(z, "sub.example.com.", DnsType.A).size());
    }

    @Test
    public void testIncludeRelativeWithOriginOverrideAndDefault() throws Exception {
        write("inc1.zone", "i1 IN A 192.0.2.21\n");
        write("inc2.zone", "i2 IN A 192.0.2.22\n");
        ZoneFile z = load("$INCLUDE inc1.zone\n$INCLUDE \"inc2.zone\" sub.example.com.\nafter IN A 192.0.2.23\n");
        assertEquals(1, answers(z, "i1.example.com.", DnsType.A).size());
        assertEquals(1, answers(z, "i2.sub.example.com.", DnsType.A).size());
        // origin restored after the include
        assertEquals(1, answers(z, "after.example.com.", DnsType.A).size());
    }

    @Test
    public void testFinalLineWithoutNewlineIsAccepted() throws Exception {
        write("inc3.zone", "i3 IN A 192.0.2.24");
        Path p = write("main-nonl.zone", HEAD + "$INCLUDE inc3.zone\nlast IN A 192.0.2.25 ; no newline after this");
        ZoneFile z = ZoneFile.load(p);
        assertEquals(1, answers(z, "i3.example.com.", DnsType.A).size());
        assertEquals(1, answers(z, "last.example.com.", DnsType.A).size());
    }

    @Test
    public void testIncludeErrors() throws Exception {
        assertLoadFails("$INCLUDE missing.zone\n", "not found");
        write("cycle-a.zone", "$INCLUDE cycle-b.zone\n");
        write("cycle-b.zone", "$INCLUDE cycle-a.zone\n");
        assertLoadFails("$INCLUDE cycle-a.zone\n", "cycle");
        assertLoadFails("$INCLUDE\n", "missing file");
    }

    @Test
    public void testGenerateVariants() throws Exception {
        ZoneFile z = load(""
                + "$GENERATE 1-5/2 odd$ A 192.0.2.$\n"
                + "$GENERATE 05-06 w$ IN A 192.0.2.1$\n"
                + "$GENERATE 3-1/-1 down$ 60 IN A 192.0.2.7$\n"
                + "$GENERATE 9 one$ CNAME odd1\n");
        assertEquals(1, answers(z, "odd1.example.com.", DnsType.A).size());
        assertEquals(1, answers(z, "odd3.example.com.", DnsType.A).size());
        assertEquals(1, answers(z, "odd5.example.com.", DnsType.A).size());
        assertEquals(ZoneLookupResult.STATUS_NXDOMAIN,
                z.lookup("odd2.example.com.", DnsType.A).getStatus());
        assertEquals(1, answers(z, "w05.example.com.", DnsType.A).size());
        assertEquals(1, answers(z, "w06.example.com.", DnsType.A).size());
        assertEquals(60, answers(z, "down2.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(1, answers(z, "one9.example.com.", DnsType.CNAME).size());
    }

    @Test
    public void testGenerateErrors() throws Exception {
        assertLoadFails("$GENERATE 1-3/0 x$ A 192.0.2.1\n", "step");
        assertLoadFails("$GENERATE 5-1 x$ A 192.0.2.1\n", "range");
        assertLoadFails("$GENERATE 1-2\n", "owner template");
        assertLoadFails("$GENERATE\n", "range");
        assertLoadFails("$GENERATE 1-2 x$\n", "type");
    }

    @Test
    public void testGenerateBeforeOrigin() throws Exception {
        Path p = write("noorigin.zone", "$GENERATE 1-2 x$ A 192.0.2.1\n");
        try {
            ZoneFile.load(p);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(String.valueOf(expected.getMessage()), String.valueOf(expected.getMessage()).contains("before $ORIGIN"));
        }
        Path p2 = write("noorigin2.zone", "x IN A 192.0.2.1\n");
        try {
            ZoneFile.load(p2);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(String.valueOf(expected.getMessage()), String.valueOf(expected.getMessage()).contains("before $ORIGIN"));
        }
    }

    @Test
    public void testSyntaxErrors() throws Exception {
        assertLoadFails("$ORIGIN\n", "missing origin");
        assertLoadFails("$TTL\n", "missing value");
        assertLoadFails("x IN A 192.0.2.1 )\n", "incomplete");
        assertLoadFails("x IN A ( 192.0.2.1\n", "incomplete");
        assertLoadFails("x IN\n", "missing type");
        assertLoadFails("x IN SRV 1 2 3 t.\n", "Unsupported");
        assertLoadFails("x IN SOA a. b. 1 2\n", "Malformed SOA");
        assertLoadFails("x IN TXT \"unterminated\n", "incomplete");
    }

    @Test
    public void testLoadRequiresSoa() throws Exception {
        Path p = write("nosoa.zone", "$ORIGIN example.com.\nx IN A 192.0.2.1\n");
        try {
            ZoneFile.load(p);
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testOverlongTokenIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("x IN TXT ");
        for (int i = 0; i < 1100; i++) {
            sb.append('a');
        }
        sb.append('\n');
        assertLoadFails(sb.toString(), "exceeds");
    }

    @Test
    public void testBinaryDataIsRejected() throws Exception {
        Path p = write("bin.zone", HEAD + "x IN TXT a\u0000b\n");
        try {
            ZoneFile.load(p);
            // a NUL inside an atom is passed through as text; acceptable
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ---- blank owner, escapes, TTL units, multi-string TXT ----

    private static List<String> txtStrings(DnsResourceRecord rr) {
        byte[] rdata = rr.getRData();
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < rdata.length) {
            int len = rdata[i] & 0xFF;
            out.add(new String(rdata, i + 1, len, StandardCharsets.UTF_8));
            i += 1 + len;
        }
        return out;
    }

    @Test
    public void testBlankOwnerMeansPreviousOwner() throws Exception {
        ZoneFile z = load(""
                + "a IN A 192.0.2.1\n"
                + "    IN A 192.0.2.2\n"
                + "   ; indented comment only\n"
                + "\t60 TXT hello\n"
                + "b IN A 192.0.2.3\n");
        assertEquals(2, answers(z, "a.example.com.", DnsType.A).size());
        assertEquals(60, answers(z, "a.example.com.", DnsType.TXT).get(0).getTTL());
        assertEquals(1, answers(z, "b.example.com.", DnsType.A).size());
    }

    @Test
    public void testBlankOwnerContinuesAfterParenthesisGroup() throws Exception {
        ZoneFile z = load("a IN TXT ( \"x\"\n   \"y\" )\n  IN A 192.0.2.1\n");
        assertEquals(1, answers(z, "a.example.com.", DnsType.A).size());
        assertEquals(2, txtStrings(answers(z, "a.example.com.", DnsType.TXT).get(0)).size());
    }

    @Test
    public void testBlankOwnerWithoutPreviousOwnerFails() throws Exception {
        Path p = write("blank-first.zone", "$ORIGIN example.com.\n  IN A 192.0.2.1\n");
        try {
            ZoneFile.load(p);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("previous owner"));
        }
    }

    @Test
    public void testBlankOwnerParserEventsAtEveryChunkSize() throws Exception {
        String text = "a IN A 1.2.3.4\n  IN A 1.2.3.5 ; c\n\t\n x \"q\\\"r\\065\"\n";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        List<String> whole = parse(data, data.length);
        assertEquals("rec:a", whole.get(0));
        assertTrue(whole.toString(), whole.contains("rec:null"));
        assertTrue(whole.toString(), whole.contains("f:q\"rA"));
        for (int chunk = 1; chunk <= 9; chunk++) {
            assertEquals("chunk " + chunk, whole, parse(data, chunk));
        }
    }

    @Test
    public void testQuotedStringEscapes() throws Exception {
        ZoneFile z = load(""
                + "e1 IN TXT \"say \\\"hi\\\" back\\\\slash\"\n"
                + "e2 IN TXT \"\\065\\066\\067 semi\\; end\"\n"
                + "e3 IN TXT \"\\\"x\\\"\"\n");
        assertEquals("say \"hi\" back\\slash", answers(z, "e1.example.com.", DnsType.TXT).get(0).getText());
        assertEquals("ABC semi; end", answers(z, "e2.example.com.", DnsType.TXT).get(0).getText());
        assertEquals("\"x\"", answers(z, "e3.example.com.", DnsType.TXT).get(0).getText());
    }

    @Test
    public void testTtlUnitSuffixes() throws Exception {
        ZoneFile z = load(""
                + "$TTL 1h30m\n"
                + "d1 IN A 192.0.2.1\n"
                + "d2 2d IN A 192.0.2.2\n"
                + "d3 IN 1W A 192.0.2.3\n"
                + "d4 30S A 192.0.2.4\n"
                + "d5 15m A 192.0.2.5\n"
                + "d6 1d2h3m4s A 192.0.2.6\n"
                + "d7 90 A 192.0.2.7\n");
        assertEquals(5400, z.getDefaultTtl());
        assertEquals(5400, answers(z, "d1.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(172800, answers(z, "d2.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(604800, answers(z, "d3.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(30, answers(z, "d4.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(900, answers(z, "d5.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(93784, answers(z, "d6.example.com.", DnsType.A).get(0).getTTL());
        assertEquals(90, answers(z, "d7.example.com.", DnsType.A).get(0).getTTL());
    }

    @Test
    public void testTtlUnitsInSoaFields() throws Exception {
        Path p = write("soa-units.zone", ""
                + "$ORIGIN example.com.\n"
                + "@ IN SOA ns1 host 5 2h 15m 2w 1h\n");
        ZoneFile z = ZoneFile.load(p);
        ZoneFile.SoaData soa = z.copySoaData();
        assertEquals(7200, soa.refresh);
        assertEquals(900, soa.retry);
        assertEquals(1209600, soa.expire);
        assertEquals(3600, soa.minimum);
    }

    @Test
    public void testBadTtlIsRejected() throws Exception {
        assertLoadFails("$TTL 5x\n", "TTL");
        assertLoadFails("$TTL 99999999999\n", "TTL");
    }

    @Test
    public void testTxtMultipleStringsAreSeparateCharacterStrings() throws Exception {
        ZoneFile z = load(""
                + "m1 IN TXT \"a\" \"b c\"\n"
                + "m2 IN TXT x y\n"
                + "m3 IN TXT \"\" \"z\"\n");
        List<String> m1 = txtStrings(answers(z, "m1.example.com.", DnsType.TXT).get(0));
        assertEquals(2, m1.size());
        assertEquals("a", m1.get(0));
        assertEquals("b c", m1.get(1));
        assertEquals(2, txtStrings(answers(z, "m2.example.com.", DnsType.TXT).get(0)).size());
        List<String> m3 = txtStrings(answers(z, "m3.example.com.", DnsType.TXT).get(0));
        assertEquals(2, m3.size());
        assertEquals("", m3.get(0));
    }

    @Test
    public void testTxtLongStringIsSplitAtCharacterStringLimit() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('x');
        }
        ZoneFile z = load("long IN TXT \"" + sb + "\"\n");
        DnsResourceRecord rr = answers(z, "long.example.com.", DnsType.TXT).get(0);
        List<String> parts = txtStrings(rr);
        assertEquals(2, parts.size());
        assertEquals(255, parts.get(0).length());
        assertEquals(45, parts.get(1).length());
        assertEquals(sb.toString(), rr.getText());
    }

    @Test
    public void testTxtBoundariesAndEscapesSurviveWriteAndReload() throws Exception {
        ZoneFile z = load(""
                + "w IN TXT \"a\" \"b \\\"q\\\" \\\\\" \"\" \"tab\\009end\"\n");
        Path out = tmp.resolve("rt-out.zone");
        ZoneFileWriter.writeAtomic(out, z.asMutable());
        ZoneFile again = ZoneFile.load(out);
        List<String> parts = txtStrings(answers(again, "w.example.com.", DnsType.TXT).get(0));
        assertEquals(4, parts.size());
        assertEquals("a", parts.get(0));
        assertEquals("b \"q\" \\", parts.get(1));
        assertEquals("", parts.get(2));
        assertEquals("tab\tend", parts.get(3));
    }

    // ---- push parsing at every chunk size ----

    private static final class Events implements ZoneFileHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public void origin(String origin) {
            log.add("origin:" + origin);
        }

        @Override
        public void defaultTtl(int ttl) {
            log.add("ttl:" + ttl);
        }

        @Override
        public void include(String filename, String originOverride) {
            log.add("include:" + filename + ":" + originOverride);
        }

        @Override
        public void unknownDirective(String name) {
            log.add("unknown:" + name);
        }

        @Override
        public void beginGenerate(String rangeSpec, String ownerTemplate) {
            log.add("gen:" + rangeSpec + ":" + ownerTemplate);
        }

        @Override
        public void beginRecord(String ownerToken) {
            log.add("rec:" + ownerToken);
        }

        @Override
        public void appendField(String token) {
            log.add("f:" + token);
        }

        @Override
        public void endGenerate() {
            log.add("endGen");
        }

        @Override
        public void endRecord() {
            log.add("endRec");
        }

        @Override
        public void endFile(Path file) {
            log.add("endFile");
        }
    }

    private static List<String> parse(byte[] data, int chunk) throws IOException {
        Events events = new Events();
        ZoneFileParser parser = new ZoneFileParser(events);
        ByteBuffer buf = ByteBuffer.allocate(Math.max(chunk, 2048));
        int offset = 0;
        while (offset < data.length) {
            if (!parser.isUnderflow()) {
                buf.clear();
            } else {
                buf.compact();
            }
            int n = Math.min(chunk, Math.min(buf.remaining(), data.length - offset));
            buf.put(data, offset, n);
            buf.flip();
            parser.receive(buf);
            offset += n;
        }
        parser.close();
        return events.log;
    }

    @Test
    public void testChunkedParsingMatchesWholeBufferAtEveryChunkSize() throws Exception {
        String text = "$ORIGIN example.com.\n$TTL 300\n$INCLUDE \"a b.zone\" sub.\n$FOO x\n"
                + "@ IN SOA ns. h. ( 1 2 ; c\n 3 4 5 )\r\n"
                + "t IN TXT \"quoted ; text\" \"two\"\n"
                + "$GENERATE 1-2 g$ A 192.0.2.$\n"
                + "last 60 IN A 192.0.2.1";
        byte[] data = (text + "\n").getBytes(StandardCharsets.UTF_8);
        List<String> whole = parse(data, data.length);
        assertTrue(whole.contains("f:quoted ; text"));
        assertTrue(whole.contains("include:a b.zone:sub."));
        for (int chunk = 1; chunk <= 9; chunk++) {
            assertEquals("chunk " + chunk, whole, parse(data, chunk));
        }
    }

    @Test
    public void testParserLifecycle() throws Exception {
        try {
            new ZoneFileParser(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("handler", expected.getMessage());
        }
        Events events = new Events();
        ZoneFileParser parser = new ZoneFileParser(events);
        parser.receive(ByteBuffer.wrap("$TTL 5\n".getBytes(StandardCharsets.UTF_8)));
        parser.close();
        parser.close();
        try {
            parser.receive(ByteBuffer.wrap(new byte[] {'x'}));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("parser closed", expected.getMessage());
        }
        assertEquals("ttl:5", events.log.get(0));
    }

    @Test
    public void testCloseDetectsIncompleteInput() throws Exception {
        String[] partial = {"$TTL 5", "x IN A (", "x IN TXT \"abc", "x IN A 1.2.3.4 ; comment", "x IN A 1.2.3.4"};
        for (int i = 0; i < partial.length; i++) {
            ZoneFileParser parser = new ZoneFileParser(new Events());
            parser.receive(ByteBuffer.wrap(partial[i].getBytes(StandardCharsets.UTF_8)));
            try {
                parser.close();
                fail("expected IOException for: " + partial[i]);
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void testUnquoteAtom() {
        assertEquals("abc", ZoneFileParser.unquoteAtom("\"abc\""));
        assertEquals("\"abc", ZoneFileParser.unquoteAtom("\"abc"));
        assertEquals("\"", ZoneFileParser.unquoteAtom("\""));
        assertEquals("abc", ZoneFileParser.unquoteAtom("abc"));
    }

    @Test
    public void testMessageRoundTripOfLoadedZone() throws Exception {
        ZoneFile z = load("w IN A 192.0.2.1\n");
        DnsMessage q = DnsMessage.createQuery(1, "w.example.com.", DnsType.A);
        assertNotNull(q);
        assertEquals(7, z.getSoaRecord().getSoaSerial());
        assertEquals(60, z.getMinimumTtl());
        assertEquals(60, z.authoritySoa().getTTL());
        assertTrue(z.isWithinZone("a.example.com."));
        assertFalse(z.isWithinZone("a.example.org."));
    }
}
