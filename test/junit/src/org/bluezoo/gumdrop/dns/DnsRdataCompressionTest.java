/*
 * DnsRdataCompressionTest.java
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

package org.bluezoo.gumdrop.dns;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Compression pointers inside the RDATA of the RFC 1035 types (CNAME, NS,
 * PTR, MX, SOA) are expanded against the whole message at parse time, so
 * the stored RDATA and every accessor see canonical uncompressed names.
 * The messages are real wire encodings in the style of RFC 1035 4.1.4.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsRdataCompressionTest {

    /** Offset of the question name "example.com" in every test message. */
    private static final int QNAME = 12;

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] b(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] label(String s) {
        byte[] text = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return cat(b(text.length), text);
    }

    /** Resource record owned by the question name (a pointer), with raw RDATA. */
    private static byte[] rr(int type, byte[] rdata) {
        return cat(b(0xC0, QNAME), b(type >> 8, type, 0, 1), b(0, 0, 0, 60),
                b(rdata.length >> 8, rdata.length), rdata);
    }

    private static byte[] message(byte[] questionName, byte[]... records) {
        byte[] header = b(0, 1, 0x81, 0x80, 0, 1, records.length >> 8, records.length, 0, 0, 0, 0);
        byte[] question = cat(questionName, b(0, 1, 0, 1));
        return cat(header, question, cat(records));
    }

    private static byte[] exampleCom() {
        return cat(label("example"), label("com"), b(0));
    }

    private static DnsMessage parse(byte[] wire) throws DnsFormatException {
        return DnsMessage.parse(ByteBuffer.wrap(wire));
    }

    private static void assertRejected(byte[] wire) {
        try {
            parse(wire);
            fail("accepted");
        } catch (DnsFormatException expected) {
            assertEquals(DnsFormatException.class, expected.getClass());
        }
    }

    @Test
    public void cnameTargetPointingAtTheQuestionNameIsExpanded() throws Exception {
        byte[] wire = message(exampleCom(), rr(5, cat(label("www"), b(0xC0, QNAME))));
        DnsResourceRecord rr = parse(wire).getAnswers().get(0);
        assertEquals("www.example.com", rr.getTargetName());
        assertArrayEquals(DnsMessage.encodeName("www.example.com"), rr.getRData());
    }

    @Test
    public void nsAndPtrTargetsThatAreWholePointersAreExpanded() throws Exception {
        byte[] wire = message(exampleCom(), rr(2, b(0xC0, QNAME)), rr(12, b(0xC0, QNAME)));
        List<DnsResourceRecord> answers = parse(wire).getAnswers();
        assertEquals("example.com", answers.get(0).getTargetName());
        assertArrayEquals(DnsMessage.encodeName("example.com"), answers.get(0).getRData());
        assertEquals("example.com", answers.get(1).getTargetName());
    }

    @Test
    public void mxExchangeIsExpandedAndPreferenceKept() throws Exception {
        byte[] wire = message(exampleCom(), rr(15, cat(b(0, 10), label("mail"), b(0xC0, QNAME))));
        DnsResourceRecord rr = parse(wire).getAnswers().get(0);
        assertEquals(10, rr.getMXPreference());
        assertEquals("mail.example.com", rr.getMXExchange());
        assertArrayEquals(cat(b(0, 10), DnsMessage.encodeName("mail.example.com")), rr.getRData());
    }

    @Test
    public void soaNamesAreExpandedAndFixedFieldsKept() throws Exception {
        byte[] fixed = b(0, 0, 0, 7, 0, 0, 0, 8, 0, 0, 0, 9, 0, 0, 0, 10, 0, 0, 0, 11);
        byte[] rdata = cat(label("ns"), b(0xC0, QNAME), label("host"), b(0xC0, QNAME), fixed);
        DnsResourceRecord rr = parse(message(exampleCom(), rr(6, rdata))).getAnswers().get(0);
        DnsResourceRecord.SoaFields soa = rr.parseSoaFields();
        assertEquals("ns.example.com", soa.mname);
        assertEquals("host.example.com", soa.rname);
        assertEquals(7, soa.serial);
        assertEquals(11, soa.minimum);
        assertEquals(7, rr.getSoaSerial());
    }

    @Test
    public void pointerToAnEarlierRdataNameAndPointerChainsAreFollowed() throws Exception {
        byte[] first = rr(12, cat(label("a"), label("b"), b(0)));
        // the first record's RDATA name starts after header, question and the record's fixed part
        int firstRdata = 12 + exampleCom().length + 4 + 12;
        byte[] second = rr(12, b(0xC0 | (firstRdata >> 8), firstRdata));
        int secondRdata = 12 + exampleCom().length + 4 + first.length + 12;
        byte[] third = rr(12, cat(label("c"), b(0xC0 | (secondRdata >> 8), secondRdata)));
        List<DnsResourceRecord> answers = parse(message(exampleCom(), first, second, third)).getAnswers();
        assertEquals("a.b", answers.get(1).getTargetName());
        assertEquals("c.a.b", answers.get(2).getTargetName());
        assertArrayEquals(DnsMessage.encodeName("c.a.b"), answers.get(2).getRData());
    }

    @Test
    public void forwardPointerToALaterNameIsExpanded() throws Exception {
        byte[] first = rr(12, b(0xC0, 0));
        byte[] second = rr(12, cat(label("x"), b(0)));
        int secondRdata = 12 + exampleCom().length + 4 + first.length + 12;
        first = rr(12, b(0xC0 | (secondRdata >> 8), secondRdata));
        List<DnsResourceRecord> answers = parse(message(exampleCom(), first, second)).getAnswers();
        assertEquals("x", answers.get(0).getTargetName());
    }

    @Test
    public void pointerLoopsAndPointersPastTheEndAreRejected() {
        int selfRdata = 12 + exampleCom().length + 4 + 12;
        assertRejected(message(exampleCom(), rr(5, b(0xC0 | (selfRdata >> 8), selfRdata))));
        assertRejected(message(exampleCom(), rr(5, b(0xFF, 0xFF))));
        assertRejected(message(exampleCom(), rr(5, b(0xC0 | (200 >> 8), 200))));
    }

    @Test
    public void pointerIntoTheMiddleOfALabelNeverEscapesAsAnotherException() {
        byte[] wire = message(exampleCom(), rr(5, b(0xC0, QNAME + 3)));
        try {
            parse(wire);
        } catch (DnsFormatException expected) {
            assertEquals(DnsFormatException.class, expected.getClass());
        }
    }

    @Test
    public void nameLongerThan255OctetsAfterExpansionIsRejected() {
        String sixtyThree = "";
        for (int i = 0; i < 63; i++) {
            sixtyThree += "q";
        }
        byte[] longName = cat(label(sixtyThree), label(sixtyThree), label(sixtyThree), b(0));
        byte[] wire = message(longName, rr(5, cat(label(sixtyThree), b(0xC0, QNAME))));
        assertRejected(wire);
    }

    @Test
    public void typesThatMustNotBeCompressedAndMalformedRdataAreKeptRaw() throws Exception {
        byte[] srv = cat(b(0, 1, 0, 2, 1, 187), label("t"), b(0xC0, QNAME));
        byte[] truncated = cat(label("zzzzzzzz"));
        byte[] wire = message(exampleCom(), rr(33, srv), rr(5, truncated));
        List<DnsResourceRecord> answers = parse(wire).getAnswers();
        assertArrayEquals(srv, answers.get(0).getRData());
        assertArrayEquals(truncated, answers.get(1).getRData());
    }

    @Test
    public void expandedRecordsSerialiseAndReparseIdentically() throws Exception {
        byte[] wire = message(exampleCom(), rr(15, cat(b(0, 10), label("mail"), b(0xC0, QNAME))));
        DnsMessage first = parse(wire);
        ByteBuffer again = first.serialize();
        DnsMessage second = DnsMessage.parse(again);
        assertEquals("mail.example.com", second.getAnswers().get(0).getMXExchange());
        assertArrayEquals(first.getAnswers().get(0).getRData(), second.getAnswers().get(0).getRData());
    }
}
