/*
 * BERDecoderTest.java
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

/**
 * Unit tests for BERDecoder.
 */
public class BERDecoderTest {

    // Test decoding primitive types

    @Test
    public void testDecodeBoolean() throws ASN1Exception {
        // Boolean TRUE: 01 01 FF
        byte[] data = {0x01, 0x01, (byte) 0xFF};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.BOOLEAN, element.getTag());
        assertTrue(element.asBoolean());
    }

    @Test
    public void testDecodeBooleanFalse() throws ASN1Exception {
        // Boolean FALSE: 01 01 00
        byte[] data = {0x01, 0x01, 0x00};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertFalse(element.asBoolean());
    }

    @Test
    public void testDecodeIntegerSmall() throws ASN1Exception {
        // Integer 42: 02 01 2A
        byte[] data = {0x02, 0x01, 0x2A};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.INTEGER, element.getTag());
        assertEquals(42, element.asInt());
    }

    @Test
    public void testDecodeIntegerNegative() throws ASN1Exception {
        // Integer -1: 02 01 FF
        byte[] data = {0x02, 0x01, (byte) 0xFF};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertEquals(-1, element.asInt());
    }

    @Test
    public void testDecodeIntegerTwoBytes() throws ASN1Exception {
        // Integer 256: 02 02 01 00
        byte[] data = {0x02, 0x02, 0x01, 0x00};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertEquals(256, element.asInt());
    }

    @Test
    public void testDecodeOctetString() throws ASN1Exception {
        // Octet string "test": 04 04 74 65 73 74
        byte[] data = {0x04, 0x04, 0x74, 0x65, 0x73, 0x74};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.OCTET_STRING, element.getTag());
        assertEquals("test", element.asString());
    }

    @Test
    public void testDecodeNull() throws ASN1Exception {
        // NULL: 05 00
        byte[] data = {0x05, 0x00};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.NULL, element.getTag());
        assertEquals(0, element.getValue().length);
    }

    @Test
    public void testDecodeEnumerated() throws ASN1Exception {
        // Enumerated 2: 0A 01 02
        byte[] data = {0x0A, 0x01, 0x02};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertEquals(ASN1Type.ENUMERATED, element.getTag());
        assertEquals(2, element.asInt());
    }

    // Test constructed types

    @Test
    public void testDecodeSequence() throws ASN1Exception {
        // Sequence containing integer 1: 30 03 02 01 01
        byte[] data = {0x30, 0x03, 0x02, 0x01, 0x01};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.SEQUENCE, element.getTag());
        assertTrue(element.isConstructed());
        assertEquals(1, element.getChildCount());
        
        ASN1Element child = element.getChild(0);
        assertEquals(1, child.asInt());
    }

    @Test
    public void testDecodeNestedSequence() throws ASN1Exception {
        // Sequence containing sequence containing integer
        // 30 05 30 03 02 01 02
        byte[] data = {0x30, 0x05, 0x30, 0x03, 0x02, 0x01, 0x02};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element outer = decoder.next();
        assertNotNull(outer);
        assertEquals(1, outer.getChildCount());
        
        ASN1Element inner = outer.getChild(0);
        assertEquals(ASN1Type.SEQUENCE, inner.getTag());
        assertEquals(1, inner.getChildCount());
        
        ASN1Element value = inner.getChild(0);
        assertEquals(2, value.asInt());
    }

    // Test long-form length encoding

    @Test
    public void testDecodeLongFormLengthOneByte() throws ASN1Exception {
        // Octet string with 200 bytes: 04 81 C8 ...
        byte[] data = new byte[3 + 200];
        data[0] = 0x04;
        data[1] = (byte) 0x81;
        data[2] = (byte) 200;
        for (int i = 0; i < 200; i++) {
            data[3 + i] = (byte) i;
        }
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(200, element.getValue().length);
    }

    @Test
    public void testDecodeLongFormLengthTwoBytes() throws ASN1Exception {
        // Octet string with 1000 bytes: 04 82 03 E8 ...
        byte[] data = new byte[4 + 1000];
        data[0] = 0x04;
        data[1] = (byte) 0x82;
        data[2] = 0x03;
        data[3] = (byte) 0xE8;
        for (int i = 0; i < 1000; i++) {
            data[4 + i] = (byte) i;
        }
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(1000, element.getValue().length);
    }

    // Test streaming/incremental decoding

    @Test
    public void testIncrementalDecode() throws ASN1Exception {
        // Send data in chunks
        byte[] data = {0x02, 0x01, 0x2A};
        
        BERDecoder decoder = new BERDecoder();
        
        // Send one byte at a time
        decoder.receive(ByteBuffer.wrap(new byte[] {data[0]}));
        assertNull(decoder.next());
        assertTrue(decoder.hasPartialData());
        
        decoder.receive(ByteBuffer.wrap(new byte[] {data[1]}));
        assertNull(decoder.next());
        
        decoder.receive(ByteBuffer.wrap(new byte[] {data[2]}));
        ASN1Element element = decoder.next();
        
        assertNotNull(element);
        assertEquals(42, element.asInt());
        assertFalse(decoder.hasPartialData());
    }

    @Test
    public void testMultipleElements() throws ASN1Exception {
        // Two integers: 02 01 01 02 01 02
        byte[] data = {0x02, 0x01, 0x01, 0x02, 0x01, 0x02};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element elem1 = decoder.next();
        assertNotNull(elem1);
        assertEquals(1, elem1.asInt());
        
        ASN1Element elem2 = decoder.next();
        assertNotNull(elem2);
        assertEquals(2, elem2.asInt());
        
        assertNull(decoder.next());
    }

    // Test context-specific tags

    @Test
    public void testDecodeContextPrimitive() throws ASN1Exception {
        // Context [0] primitive with value 0x01 0x02: 80 02 01 02
        byte[] data = {(byte) 0x80, 0x02, 0x01, 0x02};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.CLASS_CONTEXT, element.getTagClass());
        assertEquals(0, element.getTagNumber());
        assertFalse(element.isConstructed());
        assertEquals(2, element.getValue().length);
    }

    @Test
    public void testDecodeContextConstructed() throws ASN1Exception {
        // Context [3] constructed containing integer 1: A3 03 02 01 01
        byte[] data = {(byte) 0xA3, 0x03, 0x02, 0x01, 0x01};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.CLASS_CONTEXT, element.getTagClass());
        assertEquals(3, element.getTagNumber());
        assertTrue(element.isConstructed());
        assertEquals(1, element.getChildCount());
    }

    // Test application tags

    @Test
    public void testDecodeApplicationTag() throws ASN1Exception {
        // Application [0] constructed (BindRequest-like): 60 03 02 01 03
        byte[] data = {0x60, 0x03, 0x02, 0x01, 0x03};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(ASN1Type.CLASS_APPLICATION, element.getTagClass());
        assertEquals(0, element.getTagNumber());
        assertTrue(element.isConstructed());
    }

    // Test reset

    @Test
    public void testReset() throws ASN1Exception {
        BERDecoder decoder = new BERDecoder();
        
        // Partial data
        decoder.receive(ByteBuffer.wrap(new byte[] {0x02, 0x01}));
        assertTrue(decoder.hasPartialData());
        
        decoder.reset();
        assertFalse(decoder.hasPartialData());
        
        // Now decode a complete element
        decoder.receive(ByteBuffer.wrap(new byte[] {0x02, 0x01, 0x2A}));
        ASN1Element element = decoder.next();
        assertNotNull(element);
        assertEquals(42, element.asInt());
    }

    // Test error handling

    @Test(expected = ASN1Exception.class)
    public void testIndefiniteLengthRejected() throws ASN1Exception {
        // Indefinite length: 30 80 02 01 01 00 00
        byte[] data = {0x30, (byte) 0x80, 0x02, 0x01, 0x01, 0x00, 0x00};
        
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(data));
    }

    // Test round-trip with BEREncoder

    @Test
    public void testRoundTrip() throws ASN1Exception {
        // Encode a structure
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(42);
        encoder.writeOctetString("hello");
        encoder.writeBoolean(true);
        encoder.endSequence();
        
        byte[] encoded = encoder.toByteArray();
        
        // Decode it back
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(encoded));
        
        ASN1Element seq = decoder.next();
        assertNotNull(seq);
        assertEquals(ASN1Type.SEQUENCE, seq.getTag());
        assertEquals(3, seq.getChildCount());
        
        assertEquals(42, seq.getChild(0).asInt());
        assertEquals("hello", seq.getChild(1).asString());
        assertTrue(seq.getChild(2).asBoolean());
    }

    @Test
    public void testRoundTripNestedStructure() throws ASN1Exception {
        // Encode nested structure
        BEREncoder encoder = new BEREncoder();
        encoder.beginSequence();
        encoder.writeInteger(1);
        encoder.beginSequence();
        encoder.writeInteger(2);
        encoder.writeInteger(3);
        encoder.endSequence();
        encoder.endSequence();
        
        byte[] encoded = encoder.toByteArray();
        
        // Decode
        BERDecoder decoder = new BERDecoder();
        decoder.receive(ByteBuffer.wrap(encoded));
        
        ASN1Element outer = decoder.next();
        assertEquals(2, outer.getChildCount());
        assertEquals(1, outer.getChild(0).asInt());
        
        ASN1Element inner = outer.getChild(1);
        assertEquals(2, inner.getChildCount());
        assertEquals(2, inner.getChild(0).asInt());
        assertEquals(3, inner.getChild(1).asInt());
    }
}

