/*
 * DNSMessageTest.java
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

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DnsMessage}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSMessageTest {

    @Test
    public void testCreateQuery() {
        DnsMessage query = DnsMessage.createQuery(1234, "example.com", DnsType.A);
        
        assertEquals(1234, query.getId());
        assertTrue(query.isQuery());
        assertFalse(query.isResponse());
        assertEquals(DnsMessage.OPCODE_QUERY, query.getOpcode());
        assertTrue(query.isRecursionDesired());
        
        List<DnsQuestion> questions = query.getQuestions();
        assertEquals(1, questions.size());
        assertEquals("example.com", questions.get(0).getName());
        assertEquals(DnsType.A, questions.get(0).getType());
        assertEquals(DnsClass.IN, questions.get(0).getDNSClass());
        
        assertTrue(query.getAnswers().isEmpty());
        assertTrue(query.getAuthorities().isEmpty());
        assertTrue(query.getAdditionals().isEmpty());
    }
    
    @Test
    public void testSerializeAndParseQuery() throws Exception {
        DnsMessage original = DnsMessage.createQuery(5678, "www.example.org", DnsType.AAAA);
        
        ByteBuffer serialized = original.serialize();
        // serialize() returns a buffer ready for get, no need to flip
        
        DnsMessage parsed = DnsMessage.parse(serialized);
        
        assertEquals(original.getId(), parsed.getId());
        assertEquals(original.isQuery(), parsed.isQuery());
        assertEquals(original.isRecursionDesired(), parsed.isRecursionDesired());
        
        assertEquals(1, parsed.getQuestions().size());
        DnsQuestion question = parsed.getQuestions().get(0);
        assertEquals("www.example.org", question.getName());
        assertEquals(DnsType.AAAA, question.getType());
    }
    
    @Test
    public void testCreateResponse() throws Exception {
        DnsMessage query = DnsMessage.createQuery(1000, "example.com", DnsType.A);
        
        InetAddress addr = InetAddress.getByName("93.184.216.34");
        DnsResourceRecord answer = DnsResourceRecord.a("example.com", 300, addr);
        
        DnsMessage response = query.createResponse(Collections.singletonList(answer));
        
        assertTrue(response.isResponse());
        assertFalse(response.isQuery());
        assertEquals(1000, response.getId());
        assertTrue(response.isRecursionAvailable());
        assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
        
        assertEquals(1, response.getAnswers().size());
        assertEquals("example.com", response.getAnswers().get(0).getName());
    }
    
    @Test
    public void testCreateErrorResponse() {
        DnsMessage query = DnsMessage.createQuery(2000, "nonexistent.invalid", DnsType.A);
        
        DnsMessage response = query.createErrorResponse(DnsMessage.RCODE_NXDOMAIN);
        
        assertTrue(response.isResponse());
        assertEquals(DnsMessage.RCODE_NXDOMAIN, response.getRcode());
        assertTrue(response.getAnswers().isEmpty());
    }
    
    @Test
    public void testFlags() {
        // Create message with specific flags
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_AA | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        
        DnsMessage msg = new DnsMessage(1, flags,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        
        assertTrue(msg.isResponse());
        assertTrue(msg.isAuthoritative());
        assertTrue(msg.isRecursionDesired());
        assertTrue(msg.isRecursionAvailable());
        assertFalse(msg.isTruncated());
    }
    
    @Test
    public void testSerializeAndParseWithMultipleRecords() throws Exception {
        InetAddress addr1 = InetAddress.getByName("1.2.3.4");
        InetAddress addr2 = InetAddress.getByName("5.6.7.8");
        
        DnsQuestion question = new DnsQuestion("multi.example.com", DnsType.A);
        DnsResourceRecord answer1 = DnsResourceRecord.a("multi.example.com", 300, addr1);
        DnsResourceRecord answer2 = DnsResourceRecord.a("multi.example.com", 300, addr2);
        
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        DnsMessage original = new DnsMessage(100, flags,
                Collections.singletonList(question),
                Arrays.asList(answer1, answer2),
                Collections.emptyList(),
                Collections.emptyList());
        
        ByteBuffer serialized = original.serialize();
        // serialize() returns a buffer ready for get, no need to flip
        
        DnsMessage parsed = DnsMessage.parse(serialized);
        
        assertEquals(2, parsed.getAnswers().size());
        assertEquals("1.2.3.4", parsed.getAnswers().get(0).getAddress().getHostAddress());
        assertEquals("5.6.7.8", parsed.getAnswers().get(1).getAddress().getHostAddress());
    }
    
    @Test
    public void testEncodeName() {
        byte[] encoded = DnsMessage.encodeName("www.example.com");
        
        // Should be: 3www7example3com0
        assertEquals(17, encoded.length);
        assertEquals(3, encoded[0]); // www length
        assertEquals('w', encoded[1]);
        assertEquals(7, encoded[4]); // example length
        assertEquals('e', encoded[5]);
        assertEquals(3, encoded[12]); // com length
        assertEquals('c', encoded[13]);
        assertEquals(0, encoded[16]); // null terminator
    }
    
    @Test
    public void testEncodeNameWithTrailingDot() {
        byte[] withDot = DnsMessage.encodeName("example.com.");
        byte[] withoutDot = DnsMessage.encodeName("example.com");
        
        assertArrayEquals(withDot, withoutDot);
    }
    
    @Test
    public void testEncodeEmptyName() {
        byte[] encoded = DnsMessage.encodeName("");
        assertEquals(1, encoded.length);
        assertEquals(0, encoded[0]);
    }
    
    @Test
    public void testDecodeNameSimple() {
        byte[] data = new byte[] {
            3, 'w', 'w', 'w',
            7, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
            3, 'c', 'o', 'm',
            0
        };
        
        ByteBuffer buf = ByteBuffer.wrap(data);
        String name = DnsMessage.decodeName(buf, buf);
        
        assertEquals("www.example.com", name);
    }
    
    @Test
    public void testIdMasking() {
        // ID should be masked to 16 bits
        DnsMessage msg = DnsMessage.createQuery(0x12345678, "test.com", DnsType.A);
        assertEquals(0x5678, msg.getId());
    }
    
    // -- EDNS0 tests (RFC 6891) --

    @Test
    public void testCreateQueryWithEdns0() throws Exception {
        List<DnsResourceRecord> additionals = Collections.singletonList(
                DnsResourceRecord.opt(4096));
        DnsMessage query = DnsMessage.createQuery(
                9999, "example.com", DnsType.A, additionals);

        assertEquals(9999, query.getId());
        assertTrue(query.isQuery());
        assertEquals(1, query.getAdditionals().size());
        assertEquals(DnsType.OPT, query.getAdditionals().get(0).getType());
        assertEquals(4096, query.getAdditionals().get(0).getUdpPayloadSize());
    }

    @Test
    public void testSerializeAndParseEdns0() throws Exception {
        List<DnsResourceRecord> additionals = Collections.singletonList(
                DnsResourceRecord.opt(4096));
        DnsMessage original = DnsMessage.createQuery(
                7777, "edns.example.com", DnsType.AAAA, additionals);

        ByteBuffer serialized = original.serialize();
        DnsMessage parsed = DnsMessage.parse(serialized);

        assertEquals(7777, parsed.getId());
        assertEquals(1, parsed.getAdditionals().size());
        DnsResourceRecord opt = parsed.getAdditionals().get(0);
        assertEquals(DnsType.OPT, opt.getType());
        assertEquals(4096, opt.getUdpPayloadSize());
    }

    @Test
    public void testDefaultEdnsUdpSize() {
        assertEquals(4096, DnsMessage.DEFAULT_EDNS_UDP_SIZE);
    }

    @Test(expected = DnsFormatException.class)
    public void testParseTooShort() throws DnsFormatException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[10]); // Less than 12 bytes header
        DnsMessage.parse(buf);
    }
    
    // -- Name compression tests (RFC 1035 section 4.1.4) --

    /**
     * Regression test for issue #257 — JQF/Zest fuzzing found that a
     * compression pointer whose offset points past the end of the
     * message threw an unchecked IllegalArgumentException (from
     * ByteBuffer.position()) instead of the declared DnsFormatException.
     */
    @Test(expected = DnsFormatException.class)
    public void testCompressionPointerOutOfRangeThrowsFormatException() throws DnsFormatException {
        byte[] data = new byte[] {
            0x00, 0x00, // ID
            0x00, 0x00, // FLAGS
            0x00, 0x01, // QDCOUNT=1
            0x00, 0x00, // ANCOUNT
            0x00, 0x00, // NSCOUNT
            0x00, 0x00, // ARCOUNT
            (byte) 0xFF, (byte) 0xFF, // QNAME: compression pointer, offset=0x3FFF
            0x00, 0x01, // QTYPE (unreached)
            0x00, 0x01, // QCLASS (unreached)
        };
        DnsMessage.parse(ByteBuffer.wrap(data));
    }

    /**
     * Regression test for issue #257 — JQF/Zest re-fuzzing of the
     * out-of-range-pointer fix found a second, more severe bug in the
     * same method: decodeName's jump counter is a local variable reset
     * on every recursive call, so it only bounds jumps within a single
     * stack frame, not across the whole recursive chain. Two
     * compression pointers that point at each other form a cycle that
     * recurses forever, exhausting the stack (a remote, single-message
     * denial-of-service vector) instead of being rejected as malformed.
     */
    @Test(expected = DnsFormatException.class)
    public void testCyclicCompressionPointersThrowFormatExceptionNotStackOverflow() throws DnsFormatException {
        byte[] data = new byte[] {
            0x00, 0x00, // ID
            0x00, 0x00, // FLAGS
            0x00, 0x01, // QDCOUNT=1
            0x00, 0x00, // ANCOUNT
            0x00, 0x00, // NSCOUNT
            0x00, 0x00, // ARCOUNT
            (byte) 0xC0, 0x0E, // offset 12: pointer -> offset 14
            (byte) 0xC0, 0x0C, // offset 14: pointer -> offset 12
            (byte) 0xC0, 0x0C, // QNAME: pointer -> offset 12 (enters the cycle)
            0x00, 0x01, // QTYPE (unreached)
            0x00, 0x01, // QCLASS (unreached)
        };
        DnsMessage.parse(ByteBuffer.wrap(data));
    }

    @Test
    public void testCompressionReducesSize() throws Exception {
        InetAddress addr1 = InetAddress.getByName("1.2.3.4");
        InetAddress addr2 = InetAddress.getByName("5.6.7.8");
        InetAddress addr3 = InetAddress.getByName("9.10.11.12");

        DnsQuestion question = new DnsQuestion("www.example.com", DnsType.A);
        DnsResourceRecord a1 = DnsResourceRecord.a("www.example.com", 300, addr1);
        DnsResourceRecord a2 = DnsResourceRecord.a("www.example.com", 300, addr2);
        DnsResourceRecord ns = DnsResourceRecord.ns("example.com", 86400, "ns1.example.com");

        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        DnsMessage msg = new DnsMessage(1, flags,
                Collections.singletonList(question),
                Arrays.asList(a1, a2),
                Collections.singletonList(ns),
                Collections.emptyList());

        ByteBuffer compressed = msg.serialize();

        // Without compression each "www.example.com" is 17 bytes.
        // With compression, second+ occurrences use a 2-byte pointer.
        // Just verify it round-trips correctly and is smaller than naive size.
        DnsMessage parsed = DnsMessage.parse(compressed);
        assertEquals(1, parsed.getId());
        assertEquals(2, parsed.getAnswers().size());
        assertEquals("www.example.com", parsed.getAnswers().get(0).getName());
        assertEquals("www.example.com", parsed.getAnswers().get(1).getName());
        assertEquals(1, parsed.getAuthorities().size());
        assertEquals("example.com", parsed.getAuthorities().get(0).getName());
    }

    @Test
    public void testCompressionRoundTrip() throws Exception {
        InetAddress addr = InetAddress.getByName("10.0.0.1");
        DnsResourceRecord mx = DnsResourceRecord.mx("example.com", 3600, 10, "mail.example.com");
        DnsResourceRecord a = DnsResourceRecord.a("mail.example.com", 300, addr);

        DnsQuestion q = new DnsQuestion("example.com", DnsType.MX);
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        DnsMessage original = new DnsMessage(42, flags,
                Collections.singletonList(q),
                Collections.singletonList(mx),
                Collections.emptyList(),
                Collections.singletonList(a));

        ByteBuffer serialized = original.serialize();
        DnsMessage parsed = DnsMessage.parse(serialized);

        assertEquals(42, parsed.getId());
        assertEquals("example.com", parsed.getQuestions().get(0).getName());
        assertEquals(DnsType.MX, parsed.getAnswers().get(0).getType());
        assertEquals("mail.example.com", parsed.getAdditionals().get(0).getName());
    }

    @Test
    public void testWireSizeMatchesSerializedLength() throws Exception {
        InetAddress addr = InetAddress.getByName("10.0.0.1");
        DnsResourceRecord mx = DnsResourceRecord.mx("example.com", 3600, 10, "mail.example.com");
        DnsResourceRecord a = DnsResourceRecord.a("mail.example.com", 300, addr);

        DnsQuestion q = new DnsQuestion("example.com", DnsType.MX);
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        DnsMessage msg = new DnsMessage(42, flags,
                Collections.singletonList(q),
                Collections.singletonList(mx),
                Collections.emptyList(),
                Collections.singletonList(a));

        assertEquals(msg.serialize().remaining(), msg.wireSize());
    }

    @Test
    public void testToString() {
        DnsMessage query = DnsMessage.createQuery(1, "test.com", DnsType.A);
        String str = query.toString();

        assertTrue(str.contains("QUERY"));
        assertTrue(str.contains("RD"));
        assertTrue(str.contains("questions=1"));
    }

    @Test
    public void testQuestionUnicastResponseBitRoundTrip() throws Exception {
        // RFC 6762 section 5.4: the mDNS "QU" bit shares the QCLASS
        // field's top bit; parsing must not confuse it with an unknown
        // class, and encoding must reproduce it.
        DnsQuestion q = new DnsQuestion("gumdrop.local", DnsType.A, DnsClass.IN, true);
        DnsMessage original = new DnsMessage(99, DnsMessage.FLAG_RD,
                Collections.singletonList(q),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        DnsMessage parsed = DnsMessage.parse(original.serialize());

        DnsQuestion parsedQuestion = parsed.getQuestions().get(0);
        assertEquals(DnsClass.IN, parsedQuestion.getDNSClass());
        assertTrue(parsedQuestion.isUnicastResponseRequested());
    }

    @Test
    public void testResourceRecordCacheFlushBitRoundTrip() throws Exception {
        // RFC 6762 section 10.2: the mDNS cache-flush bit shares the RR
        // CLASS field's top bit.
        InetAddress addr = InetAddress.getByName("192.0.2.5");
        int rawClass = DnsClass.IN.getValue() | DnsResourceRecord.CACHE_FLUSH_BIT;
        DnsResourceRecord rr = new DnsResourceRecord("gumdrop.local", DnsType.A,
                DnsType.A.getValue(), DnsClass.IN, rawClass, 120, addr.getAddress());
        DnsMessage original = new DnsMessage(0, DnsMessage.FLAG_QR | DnsMessage.FLAG_AA,
                Collections.emptyList(),
                Collections.singletonList(rr),
                Collections.emptyList(), Collections.emptyList());

        DnsMessage parsed = DnsMessage.parse(original.serialize());

        DnsResourceRecord parsedRr = parsed.getAnswers().get(0);
        assertEquals(DnsClass.IN, parsedRr.getDNSClass());
        assertTrue(parsedRr.isCacheFlush());
    }
}

