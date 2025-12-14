/*
 * BEREncoderTest.java
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

package org.bluezoo.gumdrop.ldap.asn1;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for BEREncoder.
 */
public class BEREncoderTest {

    // Test primitive type encoding

    @Test
    public void testWriteBoolean() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeBoolean(true);
        byte[] data = encoder.toByteArray();
        
        assertEquals(3, data.length);
        assertEquals(ASN1Type.BOOLEAN, data[0] & 0xFF);
        assertEquals(1, data[1]); // length
        assertEquals((byte) 0xFF, data[2]); // true = 0xFF
        
        encoder.reset();
        encoder.writeBoolean(false);
        data = encoder.toByteArray();
        
        assertEquals(3, data.length);
        assertEquals(0x00, data[2]); // false = 0x00
    }

    @Test
    public void testWriteIntegerSmall() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeInteger(127);
        byte[] data = encoder.toByteArray();
        
        assertEquals(3, data.length);
        assertEquals(ASN1Type.INTEGER, data[0] & 0xFF);
        assertEquals(1, data[1]); // length
        assertEquals(127, data[2]);
    }

    @Test
    public void testWriteIntegerNegative() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeInteger(-1);
        byte[] data = encoder.toByteArray();
        
        assertEquals(3, data.length);
        assertEquals(ASN1Type.INTEGER, data[0] & 0xFF);
        assertEquals(1, data[1]); // length
        assertEquals((byte) 0xFF, data[2]); // -1 = 0xFF
    }

    @Test
    public void testWriteIntegerTwoBytes() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeInteger(256);
        byte[] data = encoder.toByteArray();
        
        assertEquals(4, data.length);
        assertEquals(ASN1Type.INTEGER, data[0] & 0xFF);
        assertEquals(2, data[1]); // length
        assertEquals(0x01, data[2]);
        assertEquals(0x00, data[3]);
    }

    @Test
    public void testWriteIntegerFourBytes() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeInteger(0x12345678);
        byte[] data = encoder.toByteArray();
        
        assertEquals(6, data.length);
        assertEquals(ASN1Type.INTEGER, data[0] & 0xFF);
        assertEquals(4, data[1]); // length
        assertEquals(0x12, data[2]);
        assertEquals(0x34, data[3]);
        assertEquals(0x56, data[4]);
        assertEquals(0x78, data[5]);
    }

    @Test
    public void testWriteEnumerated() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeEnumerated(2);
        byte[] data = encoder.toByteArray();
        
        assertEquals(3, data.length);
        assertEquals(ASN1Type.ENUMERATED, data[0] & 0xFF);
        assertEquals(1, data[1]);
        assertEquals(2, data[2]);
    }

    @Test
    public void testWriteOctetStringBytes() {
        BEREncoder encoder = new BEREncoder();
        byte[] value = {0x01, 0x02, 0x03, 0x04};
        encoder.writeOctetString(value);
        byte[] data = encoder.toByteArray();
        
        assertEquals(6, data.length);
        assertEquals(ASN1Type.OCTET_STRING, data[0] & 0xFF);
        assertEquals(4, data[1]); // length
        assertEquals(0x01, data[2]);
        assertEquals(0x02, data[3]);
        assertEquals(0x03, data[4]);
        assertEquals(0x04, data[5]);
    }

    @Test
    public void testWriteOctetStringString() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeOctetString("test");
        byte[] data = encoder.toByteArray();
        
        assertEquals(6, data.length);
        assertEquals(ASN1Type.OCTET_STRING, data[0] & 0xFF);
        assertEquals(4, data[1]); // length
        assertEquals('t', data[2]);
        assertEquals('e', data[3]);
        assertEquals('s', data[4]);
        assertEquals('t', data[5]);
    }

    @Test
    public void testWriteNull() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeNull();
        byte[] data = encoder.toByteArray();
        
        assertEquals(2, data.length);
        assertEquals(ASN1Type.NULL, data[0] & 0xFF);
        assertEquals(0, data[1]); // length
    }

    // Test length encoding

    @Test
    public void testLengthEncodingShortForm() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeOctetString(new byte[127]);
        byte[] data = encoder.toByteArray();
        
        assertEquals(127, data[1]); // Short form length
    }

    @Test
    public void testLengthEncodingLongFormOneByte() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeOctetString(new byte[200]);
        byte[] data = encoder.toByteArray();
        
        assertEquals((byte) 0x81, data[1]); // Long form, 1 byte follows
        assertEquals(200, data[2] & 0xFF);  // Use & 0xFF to get unsigned value
    }

    @Test
    public void testLengthEncodingLongFormTwoBytes() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeOctetString(new byte[1000]);
        byte[] data = encoder.toByteArray();
        
        assertEquals((byte) 0x82, data[1]); // Long form, 2 bytes follow
        assertEquals(0x03, data[2]); // 1000 >> 8 = 3
        assertEquals((byte) 0xE8, data[3]); // 1000 & 0xFF = 232
    }

    // Test constructed types

    @Test
    public void testSequence() {
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(1);
        encoder.writeOctetString("test");
        encoder.endSequence();
        
        byte[] data = encoder.toByteArray();
        
        assertEquals(ASN1Type.SEQUENCE, data[0] & 0xFF);
        // Total content: 3 (integer) + 6 (octet string) = 9 bytes
        assertEquals(9, data[1]);
    }

    @Test
    public void testNestedSequences() {
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(1);
        encoder.beginSequence();
        encoder.writeInteger(2);
        encoder.endSequence();
        encoder.endSequence();
        
        byte[] data = encoder.toByteArray();
        
        assertEquals(ASN1Type.SEQUENCE, data[0] & 0xFF);
        // Outer sequence contains: integer(3) + inner sequence(5)
    }

    @Test
    public void testSet() {
        BEREncoder encoder = new BEREncoder();
        encoder.beginSet();
        encoder.writeBoolean(true);
        encoder.endSet();
        
        byte[] data = encoder.toByteArray();
        
        assertEquals(ASN1Type.SET, data[0] & 0xFF);
    }

    // Test context-specific tags

    @Test
    public void testContextPrimitive() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeContext(0, new byte[] {0x01, 0x02});
        
        byte[] data = encoder.toByteArray();
        
        assertEquals(0x80, data[0] & 0xFF); // Context [0] primitive
        assertEquals(2, data[1]);
        assertEquals(0x01, data[2]);
        assertEquals(0x02, data[3]);
    }

    @Test
    public void testContextConstructed() {
        BEREncoder encoder = new BEREncoder();
        encoder.beginContext(3, true);
        encoder.writeInteger(42);
        encoder.endContext();
        
        byte[] data = encoder.toByteArray();
        
        assertEquals(0xA3, data[0] & 0xFF); // Context [3] constructed
    }

    // Test application tags

    @Test
    public void testApplicationTag() {
        BEREncoder encoder = new BEREncoder();
        encoder.beginApplication(0, true);  // BindRequest
        encoder.writeInteger(3);  // LDAP version
        encoder.endApplication();
        
        byte[] data = encoder.toByteArray();
        
        assertEquals(0x60, data[0] & 0xFF); // Application [0] constructed
    }

    // Test reset and reuse

    @Test
    public void testReset() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeInteger(1);
        
        byte[] data1 = encoder.toByteArray();
        assertEquals(3, data1.length);
        
        encoder.reset();
        encoder.writeBoolean(false);
        
        byte[] data2 = encoder.toByteArray();
        assertEquals(3, data2.length);
        assertEquals(ASN1Type.BOOLEAN, data2[0] & 0xFF);
    }

    @Test
    public void testToByteBuffer() {
        BEREncoder encoder = new BEREncoder();
        encoder.writeInteger(42);
        
        ByteBuffer buf = encoder.toByteBuffer();
        assertNotNull(buf);
        assertEquals(3, buf.remaining());
    }

    // Test LDAP-like message structure

    @Test
    public void testLDAPBindRequest() {
        BEREncoder encoder = new BEREncoder();
        
        // LDAPMessage
        encoder.beginSequence();
        encoder.writeInteger(1);  // messageId
        
        // BindRequest (application tag 0)
        encoder.beginApplication(0, true);
        encoder.writeInteger(3);  // version
        encoder.writeOctetString("cn=admin,dc=example,dc=com");  // name
        encoder.writeContext(0, "secret".getBytes(StandardCharsets.UTF_8));  // simple auth
        encoder.endApplication();
        
        encoder.endSequence();
        
        byte[] data = encoder.toByteArray();
        
        // Verify structure
        assertEquals(ASN1Type.SEQUENCE, data[0] & 0xFF);
        assertTrue(data.length > 10);
    }
}

