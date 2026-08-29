/*
 * FieldTableTest.java
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

package org.bluezoo.gumdrop.amqp.client;

import org.junit.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;

public class FieldTableTest {

    private static FieldTable roundTrip(FieldTable original) throws AMQPProtocolException {
        ByteBuffer encoded = original.encode();
        assertEquals("encodedContentSize must match actual encode() output",
                encoded.remaining(), original.encodedContentSize());
        return FieldTable.decode(encoded, encoded.remaining());
    }

    @Test
    public void testEmptyTable() throws AMQPProtocolException {
        FieldTable table = new FieldTable();
        FieldTable decoded = roundTrip(table);
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void testScalarTypes() throws AMQPProtocolException {
        FieldTable table = new FieldTable()
                .put("bool", Boolean.TRUE)
                .put("byte", (byte) -12)
                .put("short", (short) 1234)
                .put("int", 123456789)
                .put("long", 9876543210L)
                .put("float", 3.25f)
                .put("double", 6.5d)
                .put("string", "hello world")
                .put("nullval", null);

        FieldTable decoded = roundTrip(table);

        assertEquals(Boolean.TRUE, decoded.get("bool"));
        assertEquals((byte) -12, decoded.get("byte"));
        assertEquals((short) 1234, decoded.get("short"));
        assertEquals(123456789, decoded.get("int"));
        assertEquals(9876543210L, decoded.get("long"));
        assertEquals(3.25f, (Float) decoded.get("float"), 0.0f);
        assertEquals(6.5d, (Double) decoded.get("double"), 0.0d);
        assertEquals("hello world", decoded.get("string"));
        assertTrue(decoded.containsKey("nullval"));
        assertNull(decoded.get("nullval"));
    }

    @Test
    public void testByteArray() throws AMQPProtocolException {
        byte[] data = { 1, 2, 3, 4, 5 };
        FieldTable table = new FieldTable().put("bytes", data);
        FieldTable decoded = roundTrip(table);
        assertArrayEquals(data, (byte[]) decoded.get("bytes"));
    }

    @Test
    public void testTimestamp() throws AMQPProtocolException {
        // Truncate to the second — AMQP timestamps have 1-second resolution.
        Date now = new Date((System.currentTimeMillis() / 1000L) * 1000L);
        FieldTable table = new FieldTable().put("ts", now);
        FieldTable decoded = roundTrip(table);
        assertEquals(now, decoded.get("ts"));
    }

    @Test
    public void testDecimal() throws AMQPProtocolException {
        BigDecimal d = new BigDecimal("123.45");
        FieldTable table = new FieldTable().put("price", d);
        FieldTable decoded = roundTrip(table);
        BigDecimal result = (BigDecimal) decoded.get("price");
        assertEquals(0, d.compareTo(result));
    }

    @Test
    public void testNestedTable() throws AMQPProtocolException {
        FieldTable inner = new FieldTable().put("x-match", "all").put("count", 3);
        FieldTable outer = new FieldTable().put("arguments", inner);

        FieldTable decoded = roundTrip(outer);
        FieldTable innerDecoded = (FieldTable) decoded.get("arguments");
        assertEquals("all", innerDecoded.get("x-match"));
        assertEquals(3, innerDecoded.get("count"));
    }

    @Test
    public void testArray() throws AMQPProtocolException {
        FieldTable table = new FieldTable().put("list", Arrays.asList(1, 2, 3, "four"));
        FieldTable decoded = roundTrip(table);
        List<?> list = (List<?>) decoded.get("list");
        assertEquals(Arrays.asList(1, 2, 3, "four"), list);
    }

    @Test
    public void testOrderIsPreserved() throws AMQPProtocolException {
        FieldTable table = new FieldTable().put("z", 1).put("a", 2).put("m", 3);
        FieldTable decoded = roundTrip(table);
        assertEquals(Arrays.asList("z", "a", "m"),
                new java.util.ArrayList<String>(decoded.asMap().keySet()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnsupportedTypeRejected() {
        new FieldTable().put("bad", new Object());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Issue #310: encode() must UTF-8 encode each distinct string exactly
    // once, not once to compute the size and again to write it.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testEncodeUtf8sEachDistinctStringExactlyOnce() {
        FieldTable table = new FieldTable()
                .put("key1", "value1")
                .put("key2", "value2")
                .put("key3", 42)
                .put("key4", "value4");
        // 4 keys + 3 string values = 7 distinct strings.
        FieldTable.utf8EncodeCountForTesting.set(0);
        table.encode();
        assertEquals("encode() should UTF-8 encode each distinct string exactly once, "
                + "not once for sizing and again for writing",
                7, FieldTable.utf8EncodeCountForTesting.get());
    }

    @Test
    public void testEncodeNestedTableUtf8sEachStringOnce() {
        FieldTable inner = new FieldTable().put("innerKey", "innerValue");
        FieldTable outer = new FieldTable().put("outerKey", inner);
        // outer key (1) + inner key (1) + inner string value (1) = 3.
        FieldTable.utf8EncodeCountForTesting.set(0);
        outer.encode();
        assertEquals("nested field-table strings must also be encoded exactly once",
                3, FieldTable.utf8EncodeCountForTesting.get());
    }

    @Test
    public void testEncodeArrayOfStringsUtf8sEachOnce() {
        FieldTable table = new FieldTable()
                .put("list", Arrays.asList("a", "b", "c"));
        // 1 key + 3 string array elements = 4.
        FieldTable.utf8EncodeCountForTesting.set(0);
        table.encode();
        assertEquals(4, FieldTable.utf8EncodeCountForTesting.get());
    }

    @Test
    public void testMultipleTablesInSequence() throws AMQPProtocolException {
        // Simulates two field-tables back to back in one buffer, as would
        // occur e.g. reading successive method arguments.
        FieldTable t1 = new FieldTable().put("a", 1);
        FieldTable t2 = new FieldTable().put("b", 2);
        ByteBuffer e1 = t1.encode();
        ByteBuffer e2 = t2.encode();
        ByteBuffer combined = ByteBuffer.allocate(e1.remaining() + e2.remaining());
        combined.put(e1);
        combined.put(e2);
        combined.flip();

        FieldTable d1 = FieldTable.decode(combined, t1.encodedContentSize());
        FieldTable d2 = FieldTable.decode(combined, t2.encodedContentSize());
        assertEquals(1, d1.get("a"));
        assertEquals(2, d2.get("b"));
        assertFalse(combined.hasRemaining());
    }
}
