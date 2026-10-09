/*
 * BerCodecBranchTest.java
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


package org.bluezoo.gumdrop.asn1;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Branch-oriented tests for the BER encoder, decoder, element and tag
 * helpers: long-form lengths, multi-byte tags, malformed input, chunked
 * feeding, and every tag name.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BerCodecBranchTest {

    private static Asn1Element decodeOne(byte[] data) throws Asn1Exception {
        BerDecoder decoder = new BerDecoder();
        decoder.receive(ByteBuffer.wrap(data));
        Asn1Element e = decoder.next();
        assertNotNull(e);
        return e;
    }

    private static byte[] octets(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i & 0x7F);
        }
        return b;
    }

    @Test
    public void lengthFormsRoundTrip() throws Exception {
        int[] sizes = {0, 127, 128, 255, 256, 65535, 65536};
        int[] headerLen = {2, 2, 3, 3, 4, 4, 5};
        for (int i = 0; i < sizes.length; i++) {
            BerEncoder enc = new BerEncoder();
            enc.writeOctetString(octets(sizes[i]));
            byte[] out = enc.toByteArray();
            assertEquals(sizes[i] + headerLen[i], out.length);
            Asn1Element e = decodeOne(out);
            assertEquals(sizes[i], e.asOctetString().length);
        }
    }

    @Test
    public void lengthFourByteHeaderEncoded() throws Exception {
        BerEncoder enc = new BerEncoder();
        enc.writeOctetString(octets(16777216));
        byte[] out = enc.toByteArray();
        assertEquals(0x84, out[1] & 0xFF);
        assertEquals(0x01, out[2] & 0xFF);
        assertEquals(16777216 + 6, out.length);
    }

    @Test
    public void writeIntegerIntWidths() throws Exception {
        int[] values = {0, -128, 127, 128, -129, 32767, 32768, -32768, -32769,
            8388607, 8388608, -8388608, -8388609, Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] widths = {1, 1, 1, 2, 2, 2, 3, 2, 3, 3, 4, 3, 4, 4, 4};
        for (int i = 0; i < values.length; i++) {
            BerEncoder enc = new BerEncoder();
            enc.writeInteger(values[i]);
            byte[] out = enc.toByteArray();
            assertEquals("width of " + values[i], widths[i], out[1] & 0xFF);
            assertEquals(values[i], decodeOne(out).asInt());
        }
    }

    @Test
    public void writeIntegerLongWidths() throws Exception {
        long[] values = {0L, 1L, -1L, 255L, -256L, 65535L, 4294967295L, 4294967296L,
            Long.MAX_VALUE, Long.MIN_VALUE, -4294967296L, 1099511627776L};
        for (int i = 0; i < values.length; i++) {
            BerEncoder enc = new BerEncoder();
            enc.writeInteger(values[i]);
            byte[] out = enc.toByteArray();
            assertEquals("value " + values[i], values[i], decodeOne(out).asLong());
        }
    }

    @Test
    public void writeEnumeratedNullAndStrings() throws Exception {
        BerEncoder enc = new BerEncoder();
        enc.beginSequence();
        enc.writeEnumerated(7);
        enc.writeNull();
        enc.writeOctetString("hello");
        enc.endSequence();
        Asn1Element seq = decodeOne(enc.toByteArray());
        assertEquals(3, seq.getChildCount());
        assertEquals(Asn1Type.ENUMERATED, seq.getChild(0).getTag());
        assertEquals(7, seq.getChild(0).asInt());
        assertEquals(Asn1Type.NULL, seq.getChild(1).getTag());
        assertEquals(0, seq.getChild(1).getValue().length);
        assertEquals("hello", seq.getChild(2).asString());
    }

    @Test
    public void setContextAndApplicationConstructs() throws Exception {
        BerEncoder enc = new BerEncoder();
        enc.beginApplication(3, true);
        enc.beginSet();
        enc.beginContext(1, true);
        enc.writeInteger(5);
        enc.endContext();
        enc.endSet();
        enc.writeApplication(4, new byte[] {1, 2});
        enc.endApplication();
        enc.writeContext(2, new byte[] {9});
        enc.writeContext(5, "abc");
        byte[] out = enc.toByteArray();
        BerDecoder dec = new BerDecoder();
        dec.receive(ByteBuffer.wrap(out));
        Asn1Element app = dec.next();
        assertEquals(Asn1Type.CLASS_APPLICATION, app.getTagClass());
        assertEquals(3, app.getTagNumber());
        assertTrue(app.isConstructed());
        assertEquals(2, app.getChildCount());
        Asn1Element set = app.getChild(0);
        assertEquals(Asn1Type.SET, set.getTag());
        assertEquals(Asn1Type.contextTag(1, true), set.getChild(0).getTag());
        Asn1Element ctx = dec.next();
        assertEquals(Asn1Type.contextTag(2, false), ctx.getTag());
        Asn1Element ctxStr = dec.next();
        assertEquals("abc", ctxStr.asString());
        assertNull(dec.next());
        assertFalse(dec.hasPartialData());
    }

    @Test
    public void writeElementPrimitiveConstructedAndNullValue() throws Exception {
        List<Asn1Element> kids = new ArrayList<Asn1Element>();
        kids.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {5}));
        kids.add(new Asn1Element(Asn1Type.OCTET_STRING, (byte[]) null));
        Asn1Element constructed = new Asn1Element(Asn1Type.SEQUENCE, kids);
        BerEncoder enc = new BerEncoder();
        enc.write(constructed);
        Asn1Element back = decodeOne(enc.toByteArray());
        assertEquals(2, back.getChildCount());
        assertEquals(5, back.getChild(0).asInt());
        assertEquals(0, back.getChild(1).getValue().length);
    }

    @Test
    public void writeElementConstructedWithNullChildren() throws Exception {
        Asn1Element e = new Asn1Element(Asn1Type.SEQUENCE, new ArrayList<Asn1Element>());
        BerEncoder enc = new BerEncoder();
        enc.write(e);
        byte[] out = enc.toByteArray();
        assertArrayEquals(new byte[] {0x30, 0x00}, out);
        ByteBuffer bb = enc.toByteBuffer();
        assertEquals(2, bb.remaining());
    }

    @Test
    public void writeRawMultiByteTagAndReset() throws Exception {
        Asn1Element e = new Asn1Element(0x1F81, new byte[] {1});
        BerEncoder enc = new BerEncoder();
        enc.write(e);
        byte[] out = enc.toByteArray();
        assertEquals(6, out.length);
        enc.reset();
        assertEquals(0, enc.toByteArray().length);
    }

    @Test
    public void nestingTooDeepRejected() {
        BerEncoder enc = new BerEncoder();
        try {
            for (int i = 0; i < 33; i++) {
                enc.beginSequence();
            }
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("Nesting too deep", expected.getMessage());
        }
    }

    @Test
    public void endWithoutBeginRejected() {
        BerEncoder enc = new BerEncoder();
        try {
            enc.endSequence();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("No construct to end", expected.getMessage());
        }
    }

    @Test
    public void decoderMultiByteTag() throws Exception {
        byte[] data = {(byte) 0x5F, (byte) 0x81, 0x01, 0x01, 0x42};
        Asn1Element e = decodeOne(data);
        assertEquals(0x5F8101, e.getTag());
        assertEquals(0x42, e.getValue()[0]);
    }

    @Test
    public void decoderMultiByteTagInChunks() throws Exception {
        byte[] data = {(byte) 0x5F, (byte) 0x81, 0x01, 0x01, 0x42};
        BerDecoder dec = new BerDecoder();
        for (int i = 0; i < data.length - 1; i++) {
            dec.receive(ByteBuffer.wrap(new byte[] {data[i]}));
            assertNull(dec.next());
            assertTrue(dec.hasPartialData());
        }
        dec.receive(ByteBuffer.wrap(new byte[] {data[data.length - 1]}));
        assertNotNull(dec.next());
        assertFalse(dec.hasPartialData());
    }

    @Test
    public void decoderLongFormLengthInChunks() throws Exception {
        BerEncoder enc = new BerEncoder();
        enc.writeOctetString(octets(300));
        byte[] data = enc.toByteArray();
        BerDecoder dec = new BerDecoder(8);
        for (int i = 0; i < data.length; i++) {
            dec.receive(ByteBuffer.wrap(new byte[] {data[i]}));
        }
        Asn1Element e = dec.next();
        assertNotNull(e);
        assertEquals(300, e.getValue().length);
    }

    @Test
    public void decoderTwoElementsInOneBufferAndReset() throws Exception {
        byte[] data = {0x02, 0x01, 0x05, 0x02, 0x01, 0x06, 0x02};
        BerDecoder dec = new BerDecoder();
        dec.receive(ByteBuffer.wrap(data));
        assertEquals(5, dec.next().asInt());
        assertEquals(6, dec.next().asInt());
        assertNull(dec.next());
        assertTrue(dec.hasPartialData());
        dec.reset();
        assertFalse(dec.hasPartialData());
        assertNull(dec.next());
    }

    @Test
    public void decoderGrowsBuffer() throws Exception {
        BerEncoder enc = new BerEncoder();
        enc.writeOctetString(octets(5000));
        BerDecoder dec = new BerDecoder(16);
        dec.receive(ByteBuffer.wrap(enc.toByteArray()));
        assertEquals(5000, dec.next().getValue().length);
    }

    @Test
    public void decoderZeroLengthElement() throws Exception {
        Asn1Element e = decodeOne(new byte[] {0x05, 0x00});
        assertEquals(0, e.getValue().length);
    }

    @Test
    public void decoderRejectsIndefiniteLength() {
        try {
            decodeOne(new byte[] {0x30, (byte) 0x80, 0x00, 0x00});
            fail("expected Asn1Exception");
        } catch (Asn1Exception expected) {
            assertTrue(expected.getMessage().contains("Indefinite"));
        }
    }

    @Test
    public void decoderRejectsHugeLengthOctetCount() {
        try {
            decodeOne(new byte[] {0x04, (byte) 0x85, 1, 2, 3, 4, 5});
            fail("expected Asn1Exception");
        } catch (Asn1Exception expected) {
            assertTrue(expected.getMessage().contains("Length too large"));
        }
    }

    @Test
    public void decoderRejectsOversizedValue() {
        try {
            decodeOne(new byte[] {0x04, (byte) 0x84, 0x7F, 0, 0, 0});
            fail("expected Asn1Exception");
        } catch (Asn1Exception expected) {
            assertTrue(expected.getMessage().contains("Value too large"));
        }
    }

    @Test
    public void decoderRejectsNegativeWrappedLength() {
        try {
            decodeOne(new byte[] {0x04, (byte) 0x84, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            fail("expected Asn1Exception");
        } catch (Asn1Exception expected) {
            assertTrue(expected.getMessage().contains("Value too large"));
        }
    }

    @Test
    public void childParsingMultiByteTagAndLongLength() throws Exception {
        BerEncoder inner = new BerEncoder();
        inner.writeOctetString(octets(200));
        byte[] innerBytes = inner.toByteArray();
        byte[] multiTag = {(byte) 0x5F, (byte) 0x81, 0x01, 0x01, 0x07};
        byte[] content = new byte[innerBytes.length + multiTag.length];
        System.arraycopy(innerBytes, 0, content, 0, innerBytes.length);
        System.arraycopy(multiTag, 0, content, innerBytes.length, multiTag.length);
        byte[] data = new byte[content.length + 3];
        data[0] = 0x30;
        data[1] = (byte) 0x81;
        data[2] = (byte) content.length;
        System.arraycopy(content, 0, data, 3, content.length);
        Asn1Element e = decodeOne(data);
        assertEquals(2, e.getChildCount());
        assertEquals(200, e.getChild(0).getValue().length);
        assertEquals(0x5F8101, e.getChild(1).getTag());
    }

    @Test
    public void childParsingNestedConstructed() throws Exception {
        byte[] data = {0x30, 0x07, (byte) 0xA1, 0x05, 0x30, 0x03, 0x02, 0x01, 0x09};
        Asn1Element e = decodeOne(data);
        Asn1Element inner = e.getChild(0).getChild(0);
        assertEquals(9, inner.getChild(0).asInt());
    }

    private void assertChildFails(byte[] data, String fragment) {
        try {
            decodeOne(data);
            fail("expected Asn1Exception containing " + fragment);
        } catch (Asn1Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(fragment));
        }
    }

    @Test
    public void childParsingTruncatedTag() {
        assertChildFails(new byte[] {0x30, 0x02, (byte) 0x5F, (byte) 0x81}, "Truncated tag");
    }

    @Test
    public void childParsingTruncatedLength() {
        assertChildFails(new byte[] {0x30, 0x01, 0x04}, "Truncated length");
    }

    @Test
    public void childParsingTruncatedLongLength() {
        assertChildFails(new byte[] {0x30, 0x02, 0x04, (byte) 0x82}, "Truncated length");
    }

    @Test
    public void childParsingIndefiniteLength() {
        assertChildFails(new byte[] {0x30, 0x02, 0x04, (byte) 0x80}, "Indefinite");
    }

    @Test
    public void childParsingLengthTooLarge() {
        assertChildFails(new byte[] {0x30, 0x03, 0x04, (byte) 0x85, 0x01}, "Length too large");
    }

    @Test
    public void childParsingValueTooLarge() {
        assertChildFails(new byte[] {0x30, 0x06, 0x04, (byte) 0x84, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF}, "Value too large");
    }

    @Test
    public void childParsingIncompleteChild() {
        assertChildFails(new byte[] {0x30, 0x03, 0x04, 0x05, 0x01}, "Incomplete child");
    }

    @Test
    public void elementAccessorsRejectBadEncodings() {
        Asn1Element nullValue = new Asn1Element(Asn1Type.INTEGER, (byte[]) null);
        Asn1Element empty = new Asn1Element(Asn1Type.INTEGER, new byte[0]);
        Asn1Element five = new Asn1Element(Asn1Type.INTEGER, new byte[5]);
        Asn1Element nine = new Asn1Element(Asn1Type.INTEGER, new byte[9]);
        Asn1Element two = new Asn1Element(Asn1Type.BOOLEAN, new byte[2]);
        Asn1Element[] bad = {nullValue, empty, five, nine, two};
        for (int i = 0; i < bad.length; i++) {
            try {
                if (i < 2) {
                    bad[i].asInt();
                } else if (i == 2) {
                    bad[i].asInt();
                } else if (i == 3) {
                    bad[i].asLong();
                } else {
                    bad[i].asBoolean();
                }
                fail("expected failure " + i);
            } catch (Asn1Exception expected) {
                assertNotNull(expected.getMessage());
            }
        }
        try {
            nullValue.asLong();
            fail();
        } catch (Asn1Exception expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            empty.asLong();
            fail();
        } catch (Asn1Exception expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            nullValue.asBoolean();
            fail();
        } catch (Asn1Exception expected) {
            assertNotNull(expected.getMessage());
        }
        assertNull(nullValue.asString());
        assertNull(nullValue.asOctetString());
    }

    @Test
    public void elementSignExtensionAndBoolean() throws Exception {
        assertEquals(-2, new Asn1Element(Asn1Type.INTEGER, new byte[] {(byte) 0xFE}).asInt());
        assertEquals(-2L, new Asn1Element(Asn1Type.INTEGER, new byte[] {(byte) 0xFE}).asLong());
        assertEquals(-256, new Asn1Element(Asn1Type.INTEGER, new byte[] {(byte) 0xFF, 0x00}).asInt());
        assertEquals(255, new Asn1Element(Asn1Type.INTEGER, new byte[] {0x00, (byte) 0xFF}).asInt());
        assertTrue(new Asn1Element(Asn1Type.BOOLEAN, new byte[] {1}).asBoolean());
        assertFalse(new Asn1Element(Asn1Type.BOOLEAN, new byte[] {0}).asBoolean());
    }

    @Test
    public void childAccessOnPrimitiveFails() {
        Asn1Element prim = new Asn1Element(Asn1Type.INTEGER, new byte[] {1});
        assertEquals(0, prim.getChildCount());
        assertNull(prim.getChildren());
        try {
            prim.getChild(0);
            fail("expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void toStringRendersAllShapes() {
        List<Asn1Element> kids = new ArrayList<Asn1Element>();
        kids.add(new Asn1Element(Asn1Type.OCTET_STRING, "text".getBytes(StandardCharsets.UTF_8)));
        kids.add(new Asn1Element(Asn1Type.OCTET_STRING, new byte[] {0x01, (byte) 0xFF}));
        kids.add(new Asn1Element(Asn1Type.OCTET_STRING, new byte[40]));
        kids.add(new Asn1Element(Asn1Type.OCTET_STRING, (byte[]) null));
        kids.add(new Asn1Element(Asn1Type.contextTag(1, true), new ArrayList<Asn1Element>()));
        Asn1Element seq = new Asn1Element(Asn1Type.SEQUENCE, kids);
        String s = seq.toString();
        assertTrue(s, s.contains("SEQUENCE {"));
        assertTrue(s, s.contains("OCTET STRING = \"text\""));
        assertTrue(s, s.contains("01 FF"));
        assertTrue(s, s.contains("[40 bytes]"));
        assertTrue(s, s.contains("CONTEXT 1 (constructed) {"));
    }

    @Test
    public void tagNamesAllUniversalTypes() {
        int[] tags = {Asn1Type.BOOLEAN, Asn1Type.INTEGER, Asn1Type.BIT_STRING, Asn1Type.OCTET_STRING,
            Asn1Type.NULL, Asn1Type.OBJECT_IDENTIFIER, Asn1Type.ENUMERATED, Asn1Type.UTF8_STRING,
            Asn1Type.SEQUENCE, Asn1Type.SET, Asn1Type.PRINTABLE_STRING, Asn1Type.IA5_STRING,
            Asn1Type.UTC_TIME, Asn1Type.GENERALIZED_TIME, Asn1Type.REAL};
        String[] names = {"BOOLEAN", "INTEGER", "BIT STRING", "OCTET STRING", "NULL",
            "OBJECT IDENTIFIER", "ENUMERATED", "UTF8String", "SEQUENCE", "SET",
            "PrintableString", "IA5String", "UTCTime", "GeneralizedTime", "UNIVERSAL 9"};
        for (int i = 0; i < tags.length; i++) {
            assertEquals(names[i], Asn1Type.getTagName(tags[i]));
        }
    }

    @Test
    public void tagNamesNonUniversalClasses() {
        assertEquals("APPLICATION 2 (primitive)", Asn1Type.getTagName(Asn1Type.applicationTag(2, false)));
        assertEquals("APPLICATION 2 (constructed)", Asn1Type.getTagName(Asn1Type.applicationTag(2, true)));
        assertEquals("CONTEXT 0 (primitive)", Asn1Type.getTagName(Asn1Type.contextTag(0, false)));
        assertEquals("PRIVATE 4 (primitive)", Asn1Type.getTagName(Asn1Type.CLASS_PRIVATE | 4));
    }

    @Test
    public void tagHelpers() {
        assertEquals(Asn1Type.CLASS_CONTEXT, Asn1Type.getTagClass(0xA3));
        assertTrue(Asn1Type.isConstructed(0xA3));
        assertFalse(Asn1Type.isConstructed(0x83));
        assertEquals(3, Asn1Type.getTagNumber(0xA3));
    }

    @Test
    public void exceptionConstructors() {
        Throwable cause = new RuntimeException("boom");
        Asn1Exception a = new Asn1Exception("msg");
        Asn1Exception b = new Asn1Exception("msg2", cause);
        assertEquals("msg", a.getMessage());
        assertSame(cause, b.getCause());
    }
}
