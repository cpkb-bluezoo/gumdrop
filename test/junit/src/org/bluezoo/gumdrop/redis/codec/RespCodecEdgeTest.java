/*
 * RespCodecEdgeTest.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge-case tests for {@link RespDecoder}, {@link RespValue} and
 * {@link RespEncoder}: size and nesting limits, malformed lengths for every
 * length-prefixed type, byte-at-a-time delivery of every RESP3 type, buffer
 * growth and compaction, and every typed accessor on every value kind.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RespCodecEdgeTest {

    private static ByteBuffer wrap(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    private static RespValue decodeOne(String wire) throws RespException {
        RespDecoder decoder = new RespDecoder();
        decoder.receive(wrap(wire));
        return decoder.next();
    }

    private static void assertRejected(String wire) {
        RespDecoder decoder = new RespDecoder();
        try {
            decoder.receive(wrap(wire));
            RespValue v = decoder.next();
            fail("expected RespException but decoded " + v);
        } catch (RespException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static RespValue feedByteByByte(String wire) throws RespException {
        RespDecoder decoder = new RespDecoder();
        byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length - 1; i++) {
            decoder.receive(ByteBuffer.wrap(new byte[] { bytes[i] }));
            RespValue partial = decoder.next();
            assertNull("incomplete after " + (i + 1) + " bytes of " + wire, partial);
        }
        decoder.receive(ByteBuffer.wrap(new byte[] { bytes[bytes.length - 1] }));
        RespValue done = decoder.next();
        assertNotNull(done);
        return done;
    }

    @Test
    public void testCollectionCountLimitForEveryCollectionType() {
        assertRejected("*1025\r\n");
        assertRejected("%1025\r\n");
        assertRejected("~1025\r\n");
        assertRejected(">1025\r\n");
    }

    @Test
    public void testBulkLengthLimitForEveryBulkType() {
        assertRejected("$524289\r\n");
        assertRejected("=524289\r\n");
        assertRejected("!524289\r\n");
    }

    @Test
    public void testNestingDepthLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("*1\r\n");
        }
        sb.append(":1\r\n");
        assertRejected(sb.toString());
    }

    @Test
    public void testNestingAtLimitDecodes() throws RespException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            sb.append("*1\r\n");
        }
        sb.append(":7\r\n");
        RespValue v = decodeOne(sb.toString());
        for (int i = 0; i < 32; i++) {
            assertTrue(v.isArray());
            v = v.asArray().get(0);
        }
        assertEquals(7L, v.asLong());
    }

    @Test
    public void testMalformedNumbersAndCounts() {
        assertRejected(":abc\r\n");
        assertRejected("$x\r\n");
        assertRejected("*x\r\n");
        assertRejected("%x\r\n");
        assertRejected("~x\r\n");
        assertRejected(">x\r\n");
        assertRejected("=x\r\n");
        assertRejected("!x\r\n");
        assertRejected(",x\r\n");
        assertRejected("#maybe\r\n");
        assertRejected("?what\r\n");
    }

    @Test
    public void testNegativeLengthsDecodeToNull() throws RespException {
        String[] wires = { "$-1\r\n", "*-1\r\n", "%-1\r\n", "~-1\r\n", ">-1\r\n", "=-1\r\n", "!-1\r\n" };
        for (int i = 0; i < wires.length; i++) {
            RespValue v = decodeOne(wires[i]);
            assertNotNull(wires[i], v);
            assertTrue(wires[i], v.isNull());
        }
    }

    @Test
    public void testEmptyArray() throws RespException {
        RespValue v = decodeOne("*0\r\n");
        assertTrue(v.isArray());
        assertEquals(0, v.asArray().size());
    }

    @Test
    public void testMissingCrlfAfterPayloads() {
        assertRejected("$3\r\nabcXY");
        assertRejected("=7\r\ntxt:abcXY");
        assertRejected("!3\r\nabcXY");
    }

    @Test
    public void testLineTooLong() {
        StringBuilder sb = new StringBuilder("+");
        for (int i = 0; i < 65537; i++) {
            sb.append('a');
        }
        sb.append("\r\n");
        assertRejected(sb.toString());
    }

    @Test
    public void testAccumulatorLimit() {
        RespDecoder decoder = new RespDecoder();
        ByteBuffer huge = ByteBuffer.allocate(4 * 1024 * 1024 + 1);
        try {
            decoder.receive(huge);
            fail("expected RespException");
        } catch (RespException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testEmptyReceiveAndNextOnEmptyBuffer() throws RespException {
        RespDecoder decoder = new RespDecoder();
        decoder.receive(ByteBuffer.allocate(0));
        assertNull(decoder.next());
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    public void testBufferGrowsPastInitialCapacity() throws RespException {
        RespDecoder decoder = new RespDecoder(16);
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            payload.append('x');
        }
        decoder.receive(wrap("$100\r\n" + payload + "\r\n"));
        RespValue v = decoder.next();
        assertNotNull(v);
        assertEquals(payload.toString(), v.asString());
    }

    @Test
    public void testGrowWithPendingPartialValue() throws RespException {
        RespDecoder decoder = new RespDecoder(16);
        decoder.receive(wrap("$40\r\n0123456789"));
        assertNull(decoder.next());
        decoder.receive(wrap("012345678901234567890123456789\r\n"));
        RespValue v = decoder.next();
        assertNotNull(v);
        assertEquals(40, v.asString().length());
    }

    @Test
    public void testCompactionAfterConsumedValue() throws RespException {
        RespDecoder decoder = new RespDecoder();
        decoder.receive(wrap("+one\r\n+tw"));
        RespValue first = decoder.next();
        assertEquals("one", first.asString());
        assertNull(decoder.next());
        decoder.receive(wrap("o\r\n"));
        RespValue second = decoder.next();
        assertEquals("two", second.asString());
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    public void testResetDiscardsBufferedData() throws RespException {
        RespDecoder decoder = new RespDecoder();
        decoder.receive(wrap("+partial"));
        assertEquals(8, decoder.bufferedBytes());
        decoder.reset();
        assertEquals(0, decoder.bufferedBytes());
        decoder.receive(wrap("+fresh\r\n"));
        assertEquals("fresh", decoder.next().asString());
    }

    @Test
    public void testNestingDepthRecoversAfterError() throws RespException {
        RespDecoder decoder = new RespDecoder();
        try {
            decoder.receive(wrap("*2\r\n:1\r\n:zz\r\n"));
            decoder.next();
            fail("expected RespException");
        } catch (RespException expected) {
            assertNotNull(expected.getMessage());
        }
        decoder.reset();
        decoder.receive(wrap("*1\r\n*1\r\n:1\r\n"));
        RespValue v = decoder.next();
        assertNotNull(v);
        assertTrue(v.isArray());
    }

    @Test
    public void testByteAtATimeDelivery() throws RespException {
        RespValue arr = feedByteByByte("*2\r\n$3\r\nabc\r\n:5\r\n");
        assertEquals(2, arr.asArray().size());
        assertEquals("abc", arr.asArray().get(0).asString());

        RespValue map = feedByteByByte("%2\r\n+a\r\n:1\r\n+b\r\n:2\r\n");
        assertEquals(2, map.asMap().size());

        RespValue set = feedByteByByte("~2\r\n:1\r\n:2\r\n");
        assertTrue(set.isSet());
        assertEquals(2, set.asArray().size());

        RespValue push = feedByteByByte(">2\r\n+msg\r\n:1\r\n");
        assertTrue(push.isPush());
        assertEquals(2, push.asPush().size());

        RespValue verbatim = feedByteByByte("=8\r\nmkd:abcd\r\n");
        assertEquals("mkd", verbatim.getVerbatimEncoding());
        assertEquals("abcd", verbatim.asString());

        RespValue blob = feedByteByByte("!5\r\nERR x\r\n");
        assertTrue(blob.isBlobError());
        assertEquals("ERR x", blob.getErrorMessage());

        RespValue dbl = feedByteByByte(",1.5\r\n");
        assertEquals(1.5, dbl.asDouble(), 0.0);

        RespValue bool = feedByteByByte("#t\r\n");
        assertTrue(bool.asBoolean());

        RespValue nul = feedByteByByte("_\r\n");
        assertEquals(RespType.NULL, nul.getType());

        RespValue big = feedByteByByte("(12345678901234567890\r\n");
        assertTrue(big.isBigNumber());
        assertEquals("12345678901234567890", big.asString());

        RespValue err = feedByteByByte("-ERR boom\r\n");
        assertTrue(err.isError());

        RespValue integer = feedByteByByte(":42\r\n");
        assertEquals(42L, integer.asLong());
    }

    @Test
    public void testIncompleteNestedCollectionsReturnNull() throws RespException {
        String[] partials = { "*2\r\n:1\r\n", "%1\r\n:1\r\n", "~2\r\n:1\r\n", ">2\r\n:1\r\n", "$5\r\nab", "=9\r\ntxt:a" };
        for (int i = 0; i < partials.length; i++) {
            assertNull(partials[i], decodeOne(partials[i]));
        }
    }

    @Test
    public void testDoubleSpecialValuesAndFalseBoolean() throws RespException {
        assertEquals(Double.POSITIVE_INFINITY, decodeOne(",inf\r\n").asDouble(), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, decodeOne(",-inf\r\n").asDouble(), 0.0);
        assertTrue(Double.isNaN(decodeOne(",nan\r\n").asDouble()));
        assertFalse(decodeOne("#f\r\n").asBoolean());
    }

    @Test
    public void testVerbatimWithoutEncodingPrefix() throws RespException {
        RespValue v = decodeOne("=3\r\nabc\r\n");
        assertEquals("txt", v.getVerbatimEncoding());
        assertEquals("abc", v.asString());
    }

    @Test
    public void testValueAccessorsForEveryKind() {
        RespValue simple = RespValue.simpleString("OK");
        assertEquals("OK", simple.asString());
        assertEquals("OK", new String(simple.asBytes(), StandardCharsets.UTF_8));
        assertEquals("+OK", simple.toString());

        RespValue err = RespValue.error("WRONGTYPE bad kind");
        assertEquals("WRONGTYPE", err.getErrorType());
        assertEquals("WRONGTYPE bad kind", err.getErrorMessage());
        assertEquals("-WRONGTYPE bad kind", err.toString());
        assertEquals("ERR", RespValue.error("ERR").getErrorType());
        assertEquals("ERR", RespValue.blobError("ERR x".getBytes(StandardCharsets.UTF_8)).getErrorType());
        assertNull(simple.getErrorType());
        assertNull(simple.getErrorMessage());

        RespValue blob = RespValue.blobError("bad".getBytes(StandardCharsets.UTF_8));
        assertEquals("bad", blob.asString());
        assertEquals("!bad", blob.toString());
        assertNull(blob.asBytes());

        RespValue integer = RespValue.integer(7);
        assertEquals("7", integer.asString());
        assertEquals(7, integer.asInt());
        assertEquals(7.0, integer.asDouble(), 0.0);
        assertEquals(":7", integer.toString());
        assertNull(integer.asBytes());

        RespValue bulk = RespValue.bulkString("hey".getBytes(StandardCharsets.UTF_8));
        assertEquals("hey", bulk.asString());
        assertEquals(3, bulk.asBytes().length);
        assertEquals("$3:hey", bulk.toString());

        List<RespValue> elems = new ArrayList<RespValue>();
        elems.add(integer);
        RespValue array = RespValue.array(elems);
        assertEquals(1, array.asArray().size());
        assertNull(array.asString());
        assertNull(array.asPush());
        assertNull(array.asMap());
        assertEquals("*1", array.toString());

        RespValue set = RespValue.set(elems);
        assertEquals("~1", set.toString());
        assertEquals(1, set.asArray().size());
        RespValue push = RespValue.push(elems);
        assertEquals(">1", push.toString());
        assertEquals(1, push.asPush().size());

        Map<RespValue, RespValue> entries = new LinkedHashMap<RespValue, RespValue>();
        entries.put(simple, integer);
        RespValue map = RespValue.map(entries);
        assertEquals("%1", map.toString());
        assertNull(map.asArray());
        assertNull(map.asString());

        RespValue dbl = RespValue.doubleValue(2.5);
        assertEquals("2.5", dbl.asString());
        assertEquals(",2.5", dbl.toString());

        RespValue bool = RespValue.booleanValue(false);
        assertEquals("false", bool.asString());
        assertEquals("#f", bool.toString());
        assertEquals("#t", RespValue.booleanValue(true).toString());

        RespValue resp3Null = RespValue.resp3Null();
        assertNull(resp3Null.asString());
        assertEquals("_", resp3Null.toString());

        RespValue verbatim = RespValue.verbatimString("mkd", "# h".getBytes(StandardCharsets.UTF_8));
        assertEquals("# h", verbatim.asString());
        assertEquals("=mkd:# h", verbatim.toString());
        assertNull(verbatim.asBytes());
        assertNull(simple.getVerbatimEncoding());

        RespValue big = RespValue.bigNumber("123");
        assertEquals("123", big.asString());
        assertEquals("(123", big.toString());
    }

    @Test
    public void testNullValueAccessors() {
        RespValue nul = RespValue.nullValue();
        assertTrue(nul.isNull());
        assertNull(nul.asString());
        assertNull(nul.asBytes());
        assertNull(nul.asArray());
        assertNull(nul.getErrorType());
        assertEquals("null", nul.toString());
    }

    @Test
    public void testTypeCheckPredicates() {
        RespValue integer = RespValue.integer(1);
        assertFalse(integer.isMap());
        assertFalse(integer.isSet());
        assertFalse(integer.isDouble());
        assertFalse(integer.isBoolean());
        assertFalse(integer.isPush());
        assertFalse(integer.isVerbatimString());
        assertFalse(integer.isBigNumber());
        assertFalse(integer.isBlobError());
        assertFalse(integer.isBulkString());
        assertFalse(integer.isSimpleString());
        assertFalse(integer.isError());
        assertTrue(RespValue.doubleValue(1).isDouble());
        assertTrue(RespValue.booleanValue(true).isBoolean());
    }

    @Test
    public void testWrongTypeAccessThrows() {
        RespValue text = RespValue.simpleString("x");
        try {
            text.asLong();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            text.asDouble();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            text.asBoolean();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testEncoderRejectsEmbeddedCrlfAnywhere() {
        RespEncoder encoder = new RespEncoder();
        String[] bad = { "\r\nx", "x\r\n", "a\r\nb" };
        for (int i = 0; i < bad.length; i++) {
            try {
                encoder.encode("SET", "k", bad[i]);
                fail("expected IllegalArgumentException for index " + i);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("CRLF"));
            }
        }
    }

    @Test
    public void testEncoderAcceptsLoneCrOrLfAndNonStringArguments() {
        RespEncoder encoder = new RespEncoder();
        ByteBuffer buf = encoder.encode("SET", "k", "a\rb\nc", Integer.valueOf(5), null);
        String wire = StandardCharsets.UTF_8.decode(buf).toString();
        assertTrue(wire.startsWith("*5\r\n$3\r\nSET\r\n"));
        assertTrue(wire.contains("$1\r\n5\r\n"));
        assertTrue(wire.endsWith("$0\r\n\r\n"));
    }

    @Test
    public void testEncoderByteArrayArgument() {
        RespEncoder encoder = new RespEncoder();
        ByteBuffer buf = encoder.encode("GET", new byte[] { 'k', '\r' });
        String wire = StandardCharsets.UTF_8.decode(buf).toString();
        assertEquals("*2\r\n$3\r\nGET\r\n$2\r\nk\r\r\n", wire);
    }
}
