/*
 * RESPValueTest.java
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

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link RESPValue}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPValueTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Null value tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testNullValue() {
        RESPValue value = RESPValue.nullValue();
        assertTrue(value.isNull());
        assertNull(value.getType());
    }

    @Test
    public void testNullValueSingleton() {
        assertSame(RESPValue.nullValue(), RESPValue.nullValue());
    }

    @Test
    public void testNullValueAsString() {
        RESPValue value = RESPValue.nullValue();
        assertNull(value.asString());
    }

    @Test
    public void testNullValueAsBytes() {
        RESPValue value = RESPValue.nullValue();
        assertNull(value.asBytes());
    }

    @Test
    public void testNullValueAsArray() {
        RESPValue value = RESPValue.nullValue();
        assertNull(value.asArray());
    }

    @Test
    public void testNullValueToString() {
        RESPValue value = RESPValue.nullValue();
        assertEquals("null", value.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Simple string tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testSimpleString() {
        RESPValue value = RESPValue.simpleString("OK");
        assertTrue(value.isSimpleString());
        assertEquals(RESPType.SIMPLE_STRING, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testSimpleStringAsString() {
        RESPValue value = RESPValue.simpleString("PONG");
        assertEquals("PONG", value.asString());
    }

    @Test
    public void testSimpleStringAsBytes() {
        RESPValue value = RESPValue.simpleString("OK");
        assertArrayEquals("OK".getBytes(StandardCharsets.UTF_8), value.asBytes());
    }

    @Test
    public void testSimpleStringToString() {
        RESPValue value = RESPValue.simpleString("OK");
        assertEquals("+OK", value.toString());
    }

    @Test
    public void testEmptySimpleString() {
        RESPValue value = RESPValue.simpleString("");
        assertEquals("", value.asString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testError() {
        RESPValue value = RESPValue.error("ERR unknown command");
        assertTrue(value.isError());
        assertEquals(RESPType.ERROR, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testErrorMessage() {
        RESPValue value = RESPValue.error("ERR unknown command 'foo'");
        assertEquals("ERR unknown command 'foo'", value.getErrorMessage());
    }

    @Test
    public void testErrorType() {
        RESPValue value = RESPValue.error("ERR unknown command");
        assertEquals("ERR", value.getErrorType());
    }

    @Test
    public void testErrorTypeWrongType() {
        RESPValue value = RESPValue.error("WRONGTYPE Operation against a key");
        assertEquals("WRONGTYPE", value.getErrorType());
    }

    @Test
    public void testErrorTypeNoSpace() {
        RESPValue value = RESPValue.error("NOSCRIPT");
        assertEquals("NOSCRIPT", value.getErrorType());
    }

    @Test
    public void testErrorAsString() {
        RESPValue value = RESPValue.error("ERR test");
        assertEquals("ERR test", value.asString());
    }

    @Test
    public void testErrorToString() {
        RESPValue value = RESPValue.error("ERR test");
        assertEquals("-ERR test", value.toString());
    }

    @Test
    public void testNonErrorHasNoErrorType() {
        RESPValue value = RESPValue.simpleString("OK");
        assertNull(value.getErrorType());
        assertNull(value.getErrorMessage());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integer tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testInteger() {
        RESPValue value = RESPValue.integer(42);
        assertTrue(value.isInteger());
        assertEquals(RESPType.INTEGER, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testIntegerAsLong() {
        RESPValue value = RESPValue.integer(9876543210L);
        assertEquals(9876543210L, value.asLong());
    }

    @Test
    public void testIntegerAsInt() {
        RESPValue value = RESPValue.integer(123);
        assertEquals(123, value.asInt());
    }

    @Test
    public void testNegativeInteger() {
        RESPValue value = RESPValue.integer(-500);
        assertEquals(-500, value.asLong());
    }

    @Test
    public void testZeroInteger() {
        RESPValue value = RESPValue.integer(0);
        assertEquals(0, value.asLong());
    }

    @Test
    public void testIntegerAsString() {
        RESPValue value = RESPValue.integer(12345);
        assertEquals("12345", value.asString());
    }

    @Test
    public void testIntegerToString() {
        RESPValue value = RESPValue.integer(100);
        assertEquals(":100", value.toString());
    }

    @Test(expected = IllegalStateException.class)
    public void testNonIntegerAsLongThrows() {
        RESPValue value = RESPValue.simpleString("OK");
        value.asLong();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk string tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testBulkString() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        RESPValue value = RESPValue.bulkString(data);
        assertTrue(value.isBulkString());
        assertEquals(RESPType.BULK_STRING, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testBulkStringAsBytes() {
        byte[] data = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF };
        RESPValue value = RESPValue.bulkString(data);
        assertArrayEquals(data, value.asBytes());
    }

    @Test
    public void testBulkStringAsString() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        RESPValue value = RESPValue.bulkString(data);
        assertEquals("Hello, World!", value.asString());
    }

    @Test
    public void testEmptyBulkString() {
        byte[] data = new byte[0];
        RESPValue value = RESPValue.bulkString(data);
        assertEquals("", value.asString());
        assertArrayEquals(new byte[0], value.asBytes());
    }

    @Test
    public void testBulkStringWithBinaryData() {
        byte[] data = new byte[] { 0x00, '\r', '\n', (byte) 0xFF };
        RESPValue value = RESPValue.bulkString(data);
        assertArrayEquals(data, value.asBytes());
    }

    @Test
    public void testBulkStringToString() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        RESPValue value = RESPValue.bulkString(data);
        assertEquals("$4:test", value.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Array tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testArray() {
        List<RESPValue> elements = Arrays.asList(
            RESPValue.simpleString("OK"),
            RESPValue.integer(42)
        );
        RESPValue value = RESPValue.array(elements);
        assertTrue(value.isArray());
        assertEquals(RESPType.ARRAY, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testArrayElements() {
        List<RESPValue> elements = Arrays.asList(
            RESPValue.simpleString("foo"),
            RESPValue.simpleString("bar")
        );
        RESPValue value = RESPValue.array(elements);
        List<RESPValue> result = value.asArray();
        assertEquals(2, result.size());
        assertEquals("foo", result.get(0).asString());
        assertEquals("bar", result.get(1).asString());
    }

    @Test
    public void testEmptyArray() {
        List<RESPValue> elements = Arrays.asList();
        RESPValue value = RESPValue.array(elements);
        assertEquals(0, value.asArray().size());
    }

    @Test
    public void testNestedArray() {
        List<RESPValue> inner = Arrays.asList(
            RESPValue.integer(1),
            RESPValue.integer(2)
        );
        List<RESPValue> outer = Arrays.asList(
            RESPValue.array(inner),
            RESPValue.simpleString("OK")
        );
        RESPValue value = RESPValue.array(outer);

        List<RESPValue> result = value.asArray();
        assertEquals(2, result.size());
        assertTrue(result.get(0).isArray());
        assertTrue(result.get(1).isSimpleString());

        List<RESPValue> innerResult = result.get(0).asArray();
        assertEquals(2, innerResult.size());
        assertEquals(1, innerResult.get(0).asLong());
        assertEquals(2, innerResult.get(1).asLong());
    }

    @Test
    public void testArrayWithNullElement() {
        List<RESPValue> elements = Arrays.asList(
            RESPValue.simpleString("foo"),
            RESPValue.nullValue(),
            RESPValue.simpleString("bar")
        );
        RESPValue value = RESPValue.array(elements);
        List<RESPValue> result = value.asArray();
        assertEquals(3, result.size());
        assertTrue(result.get(1).isNull());
    }

    @Test
    public void testArrayToString() {
        List<RESPValue> elements = Arrays.asList(
            RESPValue.simpleString("a"),
            RESPValue.simpleString("b")
        );
        RESPValue value = RESPValue.array(elements);
        assertEquals("*2", value.toString());
    }

    @Test
    public void testNonArrayAsArray() {
        RESPValue value = RESPValue.simpleString("OK");
        assertNull(value.asArray());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type checking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testSimpleStringTypeChecks() {
        RESPValue value = RESPValue.simpleString("OK");
        assertTrue(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testErrorTypeChecks() {
        RESPValue value = RESPValue.error("ERR");
        assertFalse(value.isSimpleString());
        assertTrue(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testIntegerTypeChecks() {
        RESPValue value = RESPValue.integer(1);
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertTrue(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testBulkStringTypeChecks() {
        RESPValue value = RESPValue.bulkString(new byte[0]);
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertTrue(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testArrayTypeChecks() {
        RESPValue value = RESPValue.array(Arrays.asList());
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertTrue(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testNullTypeChecks() {
        RESPValue value = RESPValue.nullValue();
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertTrue(value.isNull());
    }

}

