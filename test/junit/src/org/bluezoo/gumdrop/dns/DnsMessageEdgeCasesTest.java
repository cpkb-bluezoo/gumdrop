/*
 * DnsMessageEdgeCasesTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Edge-case tests for {@link DnsMessage}: flag accessors, malformed input,
 * name encoding limits, DO-bit detection and EDNS padding.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsMessageEdgeCasesTest {

    private static DnsMessage withFlags(int flags) {
        List<DnsQuestion> none = Collections.emptyList();
        List<DnsResourceRecord> noRecords = Collections.emptyList();
        return new DnsMessage(7, flags, none, noRecords, noRecords, noRecords);
    }

    @Test
    public void flagAccessors() {
        DnsMessage m = withFlags(DnsMessage.FLAG_QR | DnsMessage.FLAG_AA | DnsMessage.FLAG_TC
            | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA | DnsMessage.FLAG_AD | DnsMessage.FLAG_CD | 3);
        assertEquals(7, m.getId());
        assertTrue(m.isResponse());
        assertFalse(m.isQuery());
        assertTrue(m.isAuthoritative());
        assertTrue(m.isTruncated());
        assertTrue(m.isRecursionDesired());
        assertTrue(m.isRecursionAvailable());
        assertTrue(m.isAuthenticatedData());
        assertTrue(m.isCheckingDisabled());
        assertEquals(3, m.getRcode());
        assertEquals(0, m.getOpcode());
        assertTrue(m.getFlags() != 0);
        String text = m.toString();
        assertTrue(text.contains("RESPONSE"));
        assertTrue(text.contains("rcode=3"));
        assertTrue(text.contains("AA"));
        assertTrue(text.contains("TC"));
        assertTrue(text.contains("RA"));
        assertTrue(text.contains("AD"));
        assertTrue(text.contains("CD"));
    }

    @Test
    public void plainQueryHasNoFlagsSet() {
        DnsMessage m = withFlags(0);
        assertTrue(m.isQuery());
        assertFalse(m.isRecursionDesired());
        assertFalse(m.isAuthenticatedData());
        assertFalse(m.isCheckingDisabled());
        assertTrue(m.toString().contains("QUERY"));
    }

    @Test
    public void dnssecOkBitDetection() {
        List<DnsResourceRecord> withDo = new ArrayList<DnsResourceRecord>();
        withDo.add(DnsResourceRecord.opt(1232, DnsResourceRecord.EDNS_FLAG_DO, new byte[0]));
        DnsMessage query = DnsMessage.createQuery(1, "example.com", DnsType.A, withDo);
        assertTrue(query.hasDO());

        List<DnsResourceRecord> withoutDo = new ArrayList<DnsResourceRecord>();
        withoutDo.add(DnsResourceRecord.opt(1232));
        assertFalse(DnsMessage.createQuery(1, "example.com", DnsType.A, withoutDo).hasDO());
        assertFalse(DnsMessage.createQuery(1, "example.com", DnsType.A).hasDO());
    }

    @Test
    public void responseAndErrorResponseCopyIdAndRd() {
        DnsMessage query = DnsMessage.createQuery(99, "example.com", DnsType.A);
        DnsMessage error = query.createErrorResponse(3);
        assertEquals(99, error.getId());
        assertTrue(error.isResponse());
        assertTrue(error.isRecursionDesired());
        assertEquals(3, error.getRcode());
    }

    @Test
    public void parseRejectsTruncatedQuestion() {
        DnsMessage query = DnsMessage.createQuery(1, "example.com", DnsType.A);
        ByteBuffer wire = query.serialize();
        byte[] all = new byte[wire.remaining()];
        wire.get(all);
        byte[] cut = new byte[all.length - 2];
        System.arraycopy(all, 0, cut, 0, cut.length);
        try {
            DnsMessage.parse(ByteBuffer.wrap(cut));
            fail("expected DnsFormatException");
        } catch (DnsFormatException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void parseRejectsUnknownQuestionType() {
        DnsMessage query = DnsMessage.createQuery(1, "a.b", DnsType.A);
        ByteBuffer wire = query.serialize();
        byte[] all = new byte[wire.remaining()];
        wire.get(all);
        int typeOffset = all.length - 4;
        all[typeOffset] = (byte) 0xFE;
        all[typeOffset + 1] = (byte) 0xFE;
        try {
            DnsMessage.parse(ByteBuffer.wrap(all));
            fail("expected DnsFormatException");
        } catch (DnsFormatException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void parseRejectsUnknownQuestionClass() {
        DnsMessage query = DnsMessage.createQuery(1, "a.b", DnsType.A);
        ByteBuffer wire = query.serialize();
        byte[] all = new byte[wire.remaining()];
        wire.get(all);
        int classOffset = all.length - 2;
        all[classOffset] = (byte) 0xFE;
        all[classOffset + 1] = (byte) 0xFE;
        try {
            DnsMessage.parse(ByteBuffer.wrap(all));
            fail("expected DnsFormatException");
        } catch (DnsFormatException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void parseRejectsTruncatedRecords() {
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        answers.add(DnsResourceRecord.txt("a.example.", 60, "hello"));
        List<DnsQuestion> none = Collections.emptyList();
        List<DnsResourceRecord> empty = Collections.emptyList();
        DnsMessage message = new DnsMessage(1, DnsMessage.FLAG_QR, none, answers, empty, empty);
        ByteBuffer wire = message.serialize();
        byte[] all = new byte[wire.remaining()];
        wire.get(all);
        for (int cut : new int[] { 5, 8, 3 }) {
            byte[] shorter = new byte[all.length - cut];
            System.arraycopy(all, 0, shorter, 0, shorter.length);
            try {
                DnsMessage.parse(ByteBuffer.wrap(shorter));
                fail("expected DnsFormatException for cut " + cut);
            } catch (DnsFormatException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void encodeNameRejectsOverlongLabel() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            sb.append('a');
        }
        DnsMessage.encodeName(sb.toString() + ".example");
    }

    @Test(expected = IllegalArgumentException.class)
    public void encodeNameRejectsOverlongName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.");
        }
        DnsMessage.encodeName(sb.toString());
    }

    @Test
    public void encodeNameAcceptsTrailingDotAndRoot() {
        assertArrayEquals(DnsMessage.encodeName("example.com"), DnsMessage.encodeName("example.com."));
        assertArrayEquals(new byte[] { 0 }, DnsMessage.encodeName("."));
        assertArrayEquals(new byte[] { 0 }, DnsMessage.encodeName(""));
    }

    @Test
    public void serializeRoundTripsAnswersAndCompressesNames() throws Exception {
        List<DnsQuestion> questions = new ArrayList<DnsQuestion>();
        questions.add(new DnsQuestion("www.example.com", DnsType.A, DnsClass.IN));
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        answers.add(DnsResourceRecord.cname("www.example.com", 60, "host.example.com"));
        answers.add(DnsResourceRecord.mx("example.com", 60, 10, "mail.example.com"));
        List<DnsResourceRecord> empty = Collections.emptyList();
        DnsMessage message = new DnsMessage(5, DnsMessage.FLAG_QR, questions, answers, empty, empty);
        ByteBuffer wire = message.serialize();
        assertEquals(message.wireSize(), wire.remaining());
        DnsMessage parsed = DnsMessage.parse(wire);
        assertEquals(1, parsed.getQuestions().size());
        assertEquals(2, parsed.getAnswers().size());
        assertEquals("host.example.com", parsed.getAnswers().get(0).getTargetName());
        assertEquals("mail.example.com", parsed.getAnswers().get(1).getMXExchange());
    }

    @Test
    public void paddingAlignsToBlockSize() throws Exception {
        DnsMessage query = DnsMessage.createQuery(1, "example.com", DnsType.A);
        ByteBuffer plain = query.serialize();
        ByteBuffer padded = DnsMessage.padToBlockSize(plain, 128);
        assertEquals(0, padded.remaining() % 128);
        assertEquals(plain.remaining(), query.serialize().remaining());
        DnsMessage parsed = DnsMessage.parse(padded);
        assertEquals(1, parsed.getAdditionals().size());
        assertEquals(DnsType.OPT, parsed.getAdditionals().get(0).getType());
    }
}
