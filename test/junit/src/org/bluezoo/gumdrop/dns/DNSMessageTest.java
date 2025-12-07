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
 * Unit tests for {@link DNSMessage}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSMessageTest {

    @Test
    public void testCreateQuery() {
        DNSMessage query = DNSMessage.createQuery(1234, "example.com", DNSType.A);
        
        assertEquals(1234, query.getId());
        assertTrue(query.isQuery());
        assertFalse(query.isResponse());
        assertEquals(DNSMessage.OPCODE_QUERY, query.getOpcode());
        assertTrue(query.isRecursionDesired());
        
        List<DNSQuestion> questions = query.getQuestions();
        assertEquals(1, questions.size());
        assertEquals("example.com", questions.get(0).getName());
        assertEquals(DNSType.A, questions.get(0).getType());
        assertEquals(DNSClass.IN, questions.get(0).getDNSClass());
        
        assertTrue(query.getAnswers().isEmpty());
        assertTrue(query.getAuthorities().isEmpty());
        assertTrue(query.getAdditionals().isEmpty());
    }
    
    @Test
    public void testSerializeAndParseQuery() throws Exception {
        DNSMessage original = DNSMessage.createQuery(5678, "www.example.org", DNSType.AAAA);
        
        ByteBuffer serialized = original.serialize();
        // serialize() returns a buffer ready for get, no need to flip
        
        DNSMessage parsed = DNSMessage.parse(serialized);
        
        assertEquals(original.getId(), parsed.getId());
        assertEquals(original.isQuery(), parsed.isQuery());
        assertEquals(original.isRecursionDesired(), parsed.isRecursionDesired());
        
        assertEquals(1, parsed.getQuestions().size());
        DNSQuestion question = parsed.getQuestions().get(0);
        assertEquals("www.example.org", question.getName());
        assertEquals(DNSType.AAAA, question.getType());
    }
    
    @Test
    public void testCreateResponse() throws Exception {
        DNSMessage query = DNSMessage.createQuery(1000, "example.com", DNSType.A);
        
        InetAddress addr = InetAddress.getByName("93.184.216.34");
        DNSResourceRecord answer = DNSResourceRecord.a("example.com", 300, addr);
        
        DNSMessage response = query.createResponse(Collections.singletonList(answer));
        
        assertTrue(response.isResponse());
        assertFalse(response.isQuery());
        assertEquals(1000, response.getId());
        assertTrue(response.isRecursionAvailable());
        assertEquals(DNSMessage.RCODE_NOERROR, response.getRcode());
        
        assertEquals(1, response.getAnswers().size());
        assertEquals("example.com", response.getAnswers().get(0).getName());
    }
    
    @Test
    public void testCreateErrorResponse() {
        DNSMessage query = DNSMessage.createQuery(2000, "nonexistent.invalid", DNSType.A);
        
        DNSMessage response = query.createErrorResponse(DNSMessage.RCODE_NXDOMAIN);
        
        assertTrue(response.isResponse());
        assertEquals(DNSMessage.RCODE_NXDOMAIN, response.getRcode());
        assertTrue(response.getAnswers().isEmpty());
    }
    
    @Test
    public void testFlags() {
        // Create message with specific flags
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_AA | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA;
        
        DNSMessage msg = new DNSMessage(1, flags,
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
        
        DNSQuestion question = new DNSQuestion("multi.example.com", DNSType.A);
        DNSResourceRecord answer1 = DNSResourceRecord.a("multi.example.com", 300, addr1);
        DNSResourceRecord answer2 = DNSResourceRecord.a("multi.example.com", 300, addr2);
        
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD | DNSMessage.FLAG_RA;
        DNSMessage original = new DNSMessage(100, flags,
                Collections.singletonList(question),
                Arrays.asList(answer1, answer2),
                Collections.emptyList(),
                Collections.emptyList());
        
        ByteBuffer serialized = original.serialize();
        // serialize() returns a buffer ready for get, no need to flip
        
        DNSMessage parsed = DNSMessage.parse(serialized);
        
        assertEquals(2, parsed.getAnswers().size());
        assertEquals("1.2.3.4", parsed.getAnswers().get(0).getAddress().getHostAddress());
        assertEquals("5.6.7.8", parsed.getAnswers().get(1).getAddress().getHostAddress());
    }
    
    @Test
    public void testEncodeName() {
        byte[] encoded = DNSMessage.encodeName("www.example.com");
        
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
        byte[] withDot = DNSMessage.encodeName("example.com.");
        byte[] withoutDot = DNSMessage.encodeName("example.com");
        
        assertArrayEquals(withDot, withoutDot);
    }
    
    @Test
    public void testEncodeEmptyName() {
        byte[] encoded = DNSMessage.encodeName("");
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
        String name = DNSMessage.decodeName(buf, buf);
        
        assertEquals("www.example.com", name);
    }
    
    @Test
    public void testIdMasking() {
        // ID should be masked to 16 bits
        DNSMessage msg = DNSMessage.createQuery(0x12345678, "test.com", DNSType.A);
        assertEquals(0x5678, msg.getId());
    }
    
    @Test(expected = DNSFormatException.class)
    public void testParseTooShort() throws DNSFormatException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[10]); // Less than 12 bytes header
        DNSMessage.parse(buf);
    }
    
    @Test
    public void testToString() {
        DNSMessage query = DNSMessage.createQuery(1, "test.com", DNSType.A);
        String str = query.toString();
        
        assertTrue(str.contains("QUERY"));
        assertTrue(str.contains("RD"));
        assertTrue(str.contains("questions=1"));
    }
}

