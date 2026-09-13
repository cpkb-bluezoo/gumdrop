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
 * Unit tests for {@link RespValue}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPValueTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Null value tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testNullValue() {
        RespValue value = RespValue.nullValue();
        assertTrue(value.isNull());
        assertNull(value.getType());
    }

    @Test
    public void testNullValueSingleton() {
        assertSame(RespValue.nullValue(), RespValue.nullValue());
    }

    @Test
    public void testNullValueAsString() {
        RespValue value = RespValue.nullValue();
        assertNull(value.asString());
    }

    @Test
    public void testNullValueAsBytes() {
        RespValue value = RespValue.nullValue();
        assertNull(value.asBytes());
    }

    @Test
    public void testNullValueAsArray() {
        RespValue value = RespValue.nullValue();
        assertNull(value.asArray());
    }

    @Test
    public void testNullValueToString() {
        RespValue value = RespValue.nullValue();
        assertEquals("null", value.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Simple string tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testSimpleString() {
        RespValue value = RespValue.simpleString("OK");
        assertTrue(value.isSimpleString());
        assertEquals(RespType.SIMPLE_STRING, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testSimpleStringAsString() {
        RespValue value = RespValue.simpleString("PONG");
        assertEquals("PONG", value.asString());
    }

    @Test
    public void testSimpleStringAsBytes() {
        RespValue value = RespValue.simpleString("OK");
        assertArrayEquals("OK".getBytes(StandardCharsets.UTF_8), value.asBytes());
    }

    @Test
    public void testSimpleStringToString() {
        RespValue value = RespValue.simpleString("OK");
        assertEquals("+OK", value.toString());
    }

    @Test
    public void testEmptySimpleString() {
        RespValue value = RespValue.simpleString("");
        assertEquals("", value.asString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testError() {
        RespValue value = RespValue.error("ERR unknown command");
        assertTrue(value.isError());
        assertEquals(RespType.ERROR, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testErrorMessage() {
        RespValue value = RespValue.error("ERR unknown command 'foo'");
        assertEquals("ERR unknown command 'foo'", value.getErrorMessage());
    }

    @Test
    public void testErrorType() {
        RespValue value = RespValue.error("ERR unknown command");
        assertEquals("ERR", value.getErrorType());
    }

    @Test
    public void testErrorTypeWrongType() {
        RespValue value = RespValue.error("WRONGTYPE Operation against a key");
        assertEquals("WRONGTYPE", value.getErrorType());
    }

    @Test
    public void testErrorTypeNoSpace() {
        RespValue value = RespValue.error("NOSCRIPT");
        assertEquals("NOSCRIPT", value.getErrorType());
    }

    @Test
    public void testErrorAsString() {
        RespValue value = RespValue.error("ERR test");
        assertEquals("ERR test", value.asString());
    }

    @Test
    public void testErrorToString() {
        RespValue value = RespValue.error("ERR test");
        assertEquals("-ERR test", value.toString());
    }

    @Test
    public void testNonErrorHasNoErrorType() {
        RespValue value = RespValue.simpleString("OK");
        assertNull(value.getErrorType());
        assertNull(value.getErrorMessage());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integer tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testInteger() {
        RespValue value = RespValue.integer(42);
        assertTrue(value.isInteger());
        assertEquals(RespType.INTEGER, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testIntegerAsLong() {
        RespValue value = RespValue.integer(9876543210L);
        assertEquals(9876543210L, value.asLong());
    }

    @Test
    public void testIntegerAsInt() {
        RespValue value = RespValue.integer(123);
        assertEquals(123, value.asInt());
    }

    @Test
    public void testNegativeInteger() {
        RespValue value = RespValue.integer(-500);
        assertEquals(-500, value.asLong());
    }

    @Test
    public void testZeroInteger() {
        RespValue value = RespValue.integer(0);
        assertEquals(0, value.asLong());
    }

    @Test
    public void testIntegerAsString() {
        RespValue value = RespValue.integer(12345);
        assertEquals("12345", value.asString());
    }

    @Test
    public void testIntegerToString() {
        RespValue value = RespValue.integer(100);
        assertEquals(":100", value.toString());
    }

    @Test(expected = IllegalStateException.class)
    public void testNonIntegerAsLongThrows() {
        RespValue value = RespValue.simpleString("OK");
        value.asLong();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk string tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testBulkString() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        RespValue value = RespValue.bulkString(data);
        assertTrue(value.isBulkString());
        assertEquals(RespType.BULK_STRING, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testBulkStringAsBytes() {
        byte[] data = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF };
        RespValue value = RespValue.bulkString(data);
        assertArrayEquals(data, value.asBytes());
    }

    @Test
    public void testBulkStringAsString() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        RespValue value = RespValue.bulkString(data);
        assertEquals("Hello, World!", value.asString());
    }

    @Test
    public void testEmptyBulkString() {
        byte[] data = new byte[0];
        RespValue value = RespValue.bulkString(data);
        assertEquals("", value.asString());
        assertArrayEquals(new byte[0], value.asBytes());
    }

    @Test
    public void testBulkStringWithBinaryData() {
        byte[] data = new byte[] { 0x00, '\r', '\n', (byte) 0xFF };
        RespValue value = RespValue.bulkString(data);
        assertArrayEquals(data, value.asBytes());
    }

    @Test
    public void testBulkStringToString() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        RespValue value = RespValue.bulkString(data);
        assertEquals("$4:test", value.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Array tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testArray() {
        List<RespValue> elements = Arrays.asList(
            RespValue.simpleString("OK"),
            RespValue.integer(42)
        );
        RespValue value = RespValue.array(elements);
        assertTrue(value.isArray());
        assertEquals(RespType.ARRAY, value.getType());
        assertFalse(value.isNull());
    }

    @Test
    public void testArrayElements() {
        List<RespValue> elements = Arrays.asList(
            RespValue.simpleString("foo"),
            RespValue.simpleString("bar")
        );
        RespValue value = RespValue.array(elements);
        List<RespValue> result = value.asArray();
        assertEquals(2, result.size());
        assertEquals("foo", result.get(0).asString());
        assertEquals("bar", result.get(1).asString());
    }

    @Test
    public void testEmptyArray() {
        List<RespValue> elements = Arrays.asList();
        RespValue value = RespValue.array(elements);
        assertEquals(0, value.asArray().size());
    }

    @Test
    public void testNestedArray() {
        List<RespValue> inner = Arrays.asList(
            RespValue.integer(1),
            RespValue.integer(2)
        );
        List<RespValue> outer = Arrays.asList(
            RespValue.array(inner),
            RespValue.simpleString("OK")
        );
        RespValue value = RespValue.array(outer);

        List<RespValue> result = value.asArray();
        assertEquals(2, result.size());
        assertTrue(result.get(0).isArray());
        assertTrue(result.get(1).isSimpleString());

        List<RespValue> innerResult = result.get(0).asArray();
        assertEquals(2, innerResult.size());
        assertEquals(1, innerResult.get(0).asLong());
        assertEquals(2, innerResult.get(1).asLong());
    }

    @Test
    public void testArrayWithNullElement() {
        List<RespValue> elements = Arrays.asList(
            RespValue.simpleString("foo"),
            RespValue.nullValue(),
            RespValue.simpleString("bar")
        );
        RespValue value = RespValue.array(elements);
        List<RespValue> result = value.asArray();
        assertEquals(3, result.size());
        assertTrue(result.get(1).isNull());
    }

    @Test
    public void testArrayToString() {
        List<RespValue> elements = Arrays.asList(
            RespValue.simpleString("a"),
            RespValue.simpleString("b")
        );
        RespValue value = RespValue.array(elements);
        assertEquals("*2", value.toString());
    }

    @Test
    public void testNonArrayAsArray() {
        RespValue value = RespValue.simpleString("OK");
        assertNull(value.asArray());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type checking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testSimpleStringTypeChecks() {
        RespValue value = RespValue.simpleString("OK");
        assertTrue(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testErrorTypeChecks() {
        RespValue value = RespValue.error("ERR");
        assertFalse(value.isSimpleString());
        assertTrue(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testIntegerTypeChecks() {
        RespValue value = RespValue.integer(1);
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertTrue(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testBulkStringTypeChecks() {
        RespValue value = RespValue.bulkString(new byte[0]);
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertTrue(value.isBulkString());
        assertFalse(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testArrayTypeChecks() {
        RespValue value = RespValue.array(Arrays.asList());
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertTrue(value.isArray());
        assertFalse(value.isNull());
    }

    @Test
    public void testNullTypeChecks() {
        RespValue value = RespValue.nullValue();
        assertFalse(value.isSimpleString());
        assertFalse(value.isError());
        assertFalse(value.isInteger());
        assertFalse(value.isBulkString());
        assertFalse(value.isArray());
        assertTrue(value.isNull());
    }

}

