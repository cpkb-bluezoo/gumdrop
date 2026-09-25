/*
 * Amqp1TypesTest.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

/**
 * Tests for {@link Amqp1Encoder} and {@link Amqp1Decoder}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1TypesTest {

    static byte[] hex(String s) {
        String t = s.replace(" ", "");
        byte[] b = new byte[t.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static Object decode(String hex) throws Exception {
        return Amqp1Decoder.read(ByteBuffer.wrap(hex(hex)));
    }

    private static Object roundTrip(Object o) throws Exception {
        Amqp1Encoder enc = new Amqp1Encoder();
        enc.writeObject(o);
        ByteBuffer buf = enc.toByteBuffer();
        Object result = Amqp1Decoder.read(buf);
        assertFalse("decoder must consume the whole value", buf.hasRemaining());
        return result;
    }

    @Test
    public void testUintUsesCompactestForm() {
        Amqp1Encoder e = new Amqp1Encoder();
        e.writeUint(0);
        e.writeUint(200);
        e.writeUint(300);
        assertEquals("43" + "52c8" + "700000012c", toHex(e.toByteArray()));
    }

    @Test
    public void testUlongUsesCompactestForm() {
        Amqp1Encoder e = new Amqp1Encoder();
        e.writeUlong(0);
        e.writeUlong(0x10);
        e.writeUlong(0x1234);
        e.writeUlong(-1L);
        assertEquals("44" + "5310" + "800000000000001234" + "80ffffffffffffffff",
                toHex(e.toByteArray()));
    }

    @Test
    public void testUnsignedDecodeWidening() throws Exception {
        assertEquals(Short.valueOf((short) 200), decode("50c8"));
        assertEquals(Integer.valueOf(65535), decode("60ffff"));
        assertEquals(Long.valueOf(4294967295L), decode("70ffffffff"));
        assertEquals(Long.valueOf(0L), decode("43"));
        assertEquals(Long.valueOf(0L), decode("44"));
        assertEquals(Long.valueOf(255L), decode("52ff"));
        assertEquals(Long.valueOf(255L), decode("53ff"));
        assertEquals(Long.valueOf(-1L), decode("80ffffffffffffffff"));
    }

    @Test
    public void testSignedIntegers() throws Exception {
        assertEquals(Byte.valueOf((byte) -3), decode("51fd"));
        assertEquals(Short.valueOf((short) -300), decode("61fed4"));
        assertEquals(Integer.valueOf(-5), decode("54fb"));
        assertEquals(Integer.valueOf(100000), decode("71000186a0"));
        assertEquals(Long.valueOf(-5L), decode("55fb"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), decode("818000000000000000"));

        assertEquals(Byte.valueOf((byte) 7), roundTrip(Byte.valueOf((byte) 7)));
        assertEquals(Short.valueOf((short) -300), roundTrip(Short.valueOf((short) -300)));
        assertEquals(Integer.valueOf(1), roundTrip(Integer.valueOf(1)));
        assertEquals(Integer.valueOf(-100000), roundTrip(Integer.valueOf(-100000)));
        assertEquals(Long.valueOf(Long.MAX_VALUE), roundTrip(Long.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.valueOf(-1L), roundTrip(Long.valueOf(-1L)));
    }

    @Test
    public void testNullAndBooleans() throws Exception {
        assertNull(decode("40"));
        assertEquals(Boolean.TRUE, decode("41"));
        assertEquals(Boolean.FALSE, decode("42"));
        assertEquals(Boolean.TRUE, decode("5601"));
        assertEquals(Boolean.FALSE, decode("5600"));
        assertNull(roundTrip(null));
        assertEquals(Boolean.TRUE, roundTrip(Boolean.TRUE));
        assertEquals(Boolean.FALSE, roundTrip(Boolean.FALSE));
    }

    @Test
    public void testFloatingPoint() throws Exception {
        assertEquals(Float.valueOf(1.5f), roundTrip(Float.valueOf(1.5f)));
        assertEquals(Double.valueOf(-2.25), roundTrip(Double.valueOf(-2.25)));
        assertEquals("723fc00000", toHex(enc(Float.valueOf(1.5f))));
    }

    @Test
    public void testCharTimestampUuid() throws Exception {
        assertEquals(Character.valueOf('x'), roundTrip(Character.valueOf('x')));
        Date d = new Date(1700000000123L);
        assertEquals(d, roundTrip(d));
        UUID u = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        assertEquals(u, roundTrip(u));
    }

    @Test
    public void testCharOutsideBmpRejected() {
        try {
            decode("730001f600");
            fail("expected rejection");
        } catch (Exception e) {
            assertTrue(e instanceof Amqp1ProtocolException);
        }
    }

    @Test
    public void testDecimalsSurviveRoundTrip() throws Exception {
        Amqp1Decimal d32 = new Amqp1Decimal(new byte[] {1, 2, 3, 4});
        Amqp1Decimal d64 = new Amqp1Decimal(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Amqp1Decimal d128 = new Amqp1Decimal(new byte[16]);
        assertEquals(d32, roundTrip(d32));
        assertEquals(d64, roundTrip(d64));
        assertEquals(d128, roundTrip(d128));
    }

    @Test
    public void testStringAndSymbolFormats() throws Exception {
        assertEquals("hi", decode("a10268" + "69"));
        assertEquals(new Amqp1Symbol("hi"), decode("a30268" + "69"));
        assertEquals("a1026869", toHex(enc("hi")));
        assertEquals("a3026869", toHex(enc(new Amqp1Symbol("hi"))));

        // string32 form for 256+ characters
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('x');
        }
        byte[] wire = enc(sb.toString());
        assertEquals(0xB1, wire[0] & 0xFF);
        assertEquals(sb.toString(), roundTrip(sb.toString()));
    }

    @Test
    public void testStringIsUtf8() throws Exception {
        String s = "café 中文";
        assertEquals(s, roundTrip(s));
    }

    @Test
    public void testSymbolMustBeAscii() {
        try {
            new Amqp1Encoder().writeSymbol("café");
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void testBinary() throws Exception {
        byte[] b = new byte[] {0, 1, (byte) 0xFF};
        assertEquals("a0030001ff", toHex(enc(b)));
        assertArrayEquals(b, (byte[]) roundTrip(b));
        byte[] big = new byte[70000];
        Arrays.fill(big, (byte) 7);
        assertArrayEquals(big, (byte[]) roundTrip(big));
    }

    @Test
    public void testList() throws Exception {
        assertEquals("45", toHex(enc(new ArrayList<Object>())));
        List<Object> l = new ArrayList<Object>();
        l.add("a");
        l.add(null);
        l.add(Long.valueOf(2));
        assertEquals("c0" + "07" + "03" + "a10161" + "40" + "5502",
                toHex(enc(l)));
        assertEquals(l, roundTrip(l));
    }

    @Test
    public void testLargeListUsesList32() throws Exception {
        List<Object> l = new ArrayList<Object>();
        for (int i = 0; i < 300; i++) {
            l.add(Long.valueOf(i));
        }
        byte[] wire = enc(l);
        assertEquals(0xD0, wire[0] & 0xFF);
        assertEquals(l, roundTrip(l));
    }

    @Test
    public void testMapPreservesOrder() throws Exception {
        Map<Object, Object> m = new LinkedHashMap<Object, Object>();
        m.put(new Amqp1Symbol("z"), "1");
        m.put(new Amqp1Symbol("a"), Long.valueOf(2));
        Object result = roundTrip(m);
        assertEquals(m, result);
        assertEquals(new ArrayList<Object>(m.keySet()),
                new ArrayList<Object>(((Map<?, ?>) result).keySet()));
        assertEquals("c1" + "0c" + "04" + "a3017a" + "a10131" + "a30161" + "5502",
                toHex(enc(m)));
    }

    @Test
    public void testDescribed() throws Exception {
        Amqp1Described d = new Amqp1Described(Long.valueOf(0x70),
                Arrays.asList((Object) Long.valueOf(1)));
        assertEquals(d, roundTrip(d));
        assertEquals("00" + "5370" + "c0" + "03" + "01" + "5501", toHex(enc(d)));
        Amqp1Described sym = new Amqp1Described(new Amqp1Symbol("x"), "v");
        assertEquals(sym, roundTrip(sym));
    }

    @Test
    public void testSymbolArray() throws Exception {
        Amqp1Encoder e = new Amqp1Encoder();
        e.writeSymbolArray(Arrays.asList("a", "bc"));
        assertEquals("e0" + "07" + "02" + "a3" + "0161" + "026263", toHex(e.toByteArray()));
        Object decoded = Amqp1Decoder.read(e.toByteBuffer());
        assertEquals(Arrays.asList((Object) new Amqp1Symbol("a"), new Amqp1Symbol("bc")),
                decoded);
    }

    @Test
    public void testLargeSymbolArrayUsesArray32() throws Exception {
        List<String> symbols = new ArrayList<String>();
        for (int i = 0; i < 300; i++) {
            symbols.add("s" + i);
        }
        Amqp1Encoder e = new Amqp1Encoder();
        e.writeSymbolArray(symbols);
        assertEquals(0xF0, e.toByteArray()[0] & 0xFF);
        List<?> decoded = (List<?>) Amqp1Decoder.read(e.toByteBuffer());
        assertEquals(300, decoded.size());
        assertEquals(new Amqp1Symbol("s299"), decoded.get(299));
    }

    @Test
    public void testDescribedArrayElements() throws Exception {
        // array8 of described(0x01, ubyte): constructor 00 53 01 50, values 05 06
        Object decoded = decode("e0" + "07" + "02" + "005301" + "50" + "0506");
        List<?> list = (List<?>) decoded;
        assertEquals(2, list.size());
        assertEquals(new Amqp1Described(Long.valueOf(1), Short.valueOf((short) 5)), list.get(0));
    }

    @Test
    public void testDecodeConsumesOnlyOneValue() throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(hex("5501" + "5502"));
        assertEquals(Long.valueOf(1), Amqp1Decoder.read(buf));
        assertEquals(Long.valueOf(2), Amqp1Decoder.read(buf));
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void testTruncatedValuesRejected() {
        String[] truncated = {
            "", "70000000", "a105", "a10561", "c0", "c00301", "d000000003",
            "e0", "81000000", "83", "98", "00", "0053",
        };
        for (int i = 0; i < truncated.length; i++) {
            assertProtocolError(truncated[i]);
        }
    }

    @Test
    public void testUnknownFormatCodeRejected() {
        assertProtocolError("ff");
        assertProtocolError("46");
    }

    @Test
    public void testCompoundCountBeyondDataRejected() {
        // list8 claiming 200 elements in 2 bytes of data
        assertProtocolError("c003c84040");
        // map with an odd element count
        assertProtocolError("c1" + "03" + "01" + "4040");
        // array count beyond data
        assertProtocolError("e0" + "03" + "ff" + "4040");
    }

    @Test
    public void testCompoundSizeSmallerThanCountFieldRejected() {
        assertProtocolError("d0" + "00000002" + "0000");
    }

    @Test
    public void testHugeSizeRejected() {
        assertProtocolError("b0ffffffff");
        assertProtocolError("d0ffffffff");
    }

    @Test
    public void testExcessiveNestingRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("00").append("5301");
        }
        sb.append("40");
        assertProtocolError(sb.toString());
    }

    @Test
    public void testCompoundBodyBoundsElements() {
        // list of size 2 (count + one byte) whose element wants 4 bytes;
        // trailing bytes outside the list must not be consumed as its data
        assertProtocolError("c0" + "02" + "01" + "70" + "00000001");
    }

    @Test
    public void testListBuilderDropsTrailingNulls() {
        Amqp1Encoder out = new Amqp1Encoder();
        new Amqp1ListBuilder().addString("a").addNull().addUint(null).writeTo(out, 0x10);
        assertEquals("005310" + "c0" + "04" + "01" + "a10161", toHex(out.toByteArray()));
    }

    @Test
    public void testListBuilderKeepsInteriorNulls() {
        Amqp1Encoder out = new Amqp1Encoder();
        new Amqp1ListBuilder().addString("a").addNull().addUint(Long.valueOf(1))
                .writeTo(out, 0x10);
        assertEquals("005310" + "c0" + "07" + "03" + "a10161" + "40" + "5201",
                toHex(out.toByteArray()));
    }

    @Test
    public void testListBuilderEmptyIsList0() {
        Amqp1Encoder out = new Amqp1Encoder();
        new Amqp1ListBuilder().addNull().writeTo(out, 0x17);
        assertEquals("005317" + "45", toHex(out.toByteArray()));
    }

    @Test
    public void testUnsupportedJavaTypeRejected() {
        try {
            new Amqp1Encoder().writeObject(new Object());
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static byte[] enc(Object o) {
        Amqp1Encoder e = new Amqp1Encoder();
        e.writeObject(o);
        return e.toByteArray();
    }

    private static void assertProtocolError(String hex) {
        try {
            decode(hex);
            fail("expected Amqp1ProtocolException for " + hex);
        } catch (Amqp1ProtocolException expected) {
            // ok
        } catch (Exception e) {
            fail("expected Amqp1ProtocolException for " + hex + " but got " + e);
        }
    }
}
