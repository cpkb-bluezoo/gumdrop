/*
 * RESPDecoderTest.java
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

import org.junit.Before;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link RESPDecoder}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPDecoderTest {

    private RESPDecoder decoder;

    @Before
    public void setUp() {
        decoder = new RESPDecoder();
    }

    private ByteBuffer wrap(String data) {
        return ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
    }

    private void recv(String data) {
        try {
            decoder.receive(wrap(data));
        } catch (RESPException e) {
            throw new AssertionError(e);
        }
    }

    private void recv(ByteBuffer data) {
        try {
            decoder.receive(data);
        } catch (RESPException e) {
            throw new AssertionError(e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Simple string decoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testDecodeSimpleString() throws RESPException {
        recv("+OK\r\n");
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isSimpleString());
        assertEquals("OK", value.asString());
    }

    @Test
    public void testDecodeSimpleStringPong() throws RESPException {
        recv("+PONG\r\n");
        RESPValue value = decoder.next();

        assertEquals("PONG", value.asString());
    }

    @Test
    public void testDecodeEmptySimpleString() throws RESPException {
        recv("+\r\n");
        RESPValue value = decoder.next();

        assertEquals("", value.asString());
    }

    @Test
    public void testDecodeSimpleStringWithSpaces() throws RESPException {
        recv("+hello world\r\n");
        RESPValue value = decoder.next();

        assertEquals("hello world", value.asString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error decoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testDecodeError() throws RESPException {
        recv("-ERR unknown command\r\n");
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isError());
        assertEquals("ERR unknown command", value.getErrorMessage());
        assertEquals("ERR", value.getErrorType());
    }

    @Test
    public void testDecodeWrongTypeError() throws RESPException {
        recv("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");
        RESPValue value = decoder.next();

        assertEquals("WRONGTYPE", value.getErrorType());
    }

    @Test
    public void testDecodeMovedError() throws RESPException {
        recv("-MOVED 3999 127.0.0.1:6381\r\n");
        RESPValue value = decoder.next();

        assertEquals("MOVED", value.getErrorType());
        assertEquals("MOVED 3999 127.0.0.1:6381", value.getErrorMessage());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integer decoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testDecodeInteger() throws RESPException {
        recv(":1000\r\n");
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isInteger());
        assertEquals(1000, value.asLong());
    }

    @Test
    public void testDecodeZero() throws RESPException {
        recv(":0\r\n");
        RESPValue value = decoder.next();

        assertEquals(0, value.asLong());
    }

    @Test
    public void testDecodeNegativeInteger() throws RESPException {
        recv(":-500\r\n");
        RESPValue value = decoder.next();

        assertEquals(-500, value.asLong());
    }

    @Test
    public void testDecodeLargeInteger() throws RESPException {
        recv(":9223372036854775807\r\n"); // Long.MAX_VALUE
        RESPValue value = decoder.next();

        assertEquals(Long.MAX_VALUE, value.asLong());
    }

    @Test(expected = RESPException.class)
    public void testDecodeInvalidInteger() throws RESPException {
        recv(":abc\r\n");
        decoder.next();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk string decoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testDecodeBulkString() throws RESPException {
        recv("$5\r\nhello\r\n");
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBulkString());
        assertEquals("hello", value.asString());
    }

    @Test
    public void testDecodeEmptyBulkString() throws RESPException {
        recv("$0\r\n\r\n");
        RESPValue value = decoder.next();

        assertEquals("", value.asString());
    }

    @Test
    public void testDecodeNullBulkString() throws RESPException {
        recv("$-1\r\n");
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isNull());
    }

    @Test
    public void testDecodeBulkStringWithCRLF() throws RESPException {
        // "foo\r\nbar" is 8 bytes: f, o, o, \r, \n, b, a, r
        recv("$8\r\nfoo\r\nbar\r\n");
        RESPValue value = decoder.next();

        assertEquals("foo\r\nbar", value.asString());
    }

    @Test
    public void testDecodeBulkStringWithBinaryData() throws RESPException {
        // Create a buffer with binary data
        ByteBuffer buf = ByteBuffer.allocate(20);
        buf.put("$4\r\n".getBytes(StandardCharsets.UTF_8));
        buf.put(new byte[] { 0x00, 0x01, (byte) 0xFF, 0x7F });
        buf.put("\r\n".getBytes(StandardCharsets.UTF_8));
        buf.flip();

        recv(buf);
        RESPValue value = decoder.next();

        byte[] bytes = value.asBytes();
        assertArrayEquals(new byte[] { 0x00, 0x01, (byte) 0xFF, 0x7F }, bytes);
    }

    @Test(expected = RESPException.class)
    public void testDecodeInvalidBulkStringLength() throws RESPException {
        recv("$abc\r\nhello\r\n");
        decoder.next();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Array decoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testDecodeEmptyArray() throws RESPException {
        recv("*0\r\n");
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isArray());
        assertEquals(0, value.asArray().size());
    }

    @Test
    public void testDecodeNullArray() throws RESPException {
        recv("*-1\r\n");
        RESPValue value = decoder.next();

        assertTrue(value.isNull());
    }

    @Test
    public void testDecodeArrayOfBulkStrings() throws RESPException {
        recv("*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
        RESPValue value = decoder.next();

        assertTrue(value.isArray());
        List<RESPValue> elements = value.asArray();
        assertEquals(2, elements.size());
        assertEquals("foo", elements.get(0).asString());
        assertEquals("bar", elements.get(1).asString());
    }

    @Test
    public void testDecodeArrayOfIntegers() throws RESPException {
        recv("*3\r\n:1\r\n:2\r\n:3\r\n");
        RESPValue value = decoder.next();

        List<RESPValue> elements = value.asArray();
        assertEquals(3, elements.size());
        assertEquals(1, elements.get(0).asLong());
        assertEquals(2, elements.get(1).asLong());
        assertEquals(3, elements.get(2).asLong());
    }

    @Test
    public void testDecodeMixedArray() throws RESPException {
        recv("*4\r\n+OK\r\n:100\r\n$5\r\nhello\r\n*0\r\n");
        RESPValue value = decoder.next();

        List<RESPValue> elements = value.asArray();
        assertEquals(4, elements.size());
        assertTrue(elements.get(0).isSimpleString());
        assertTrue(elements.get(1).isInteger());
        assertTrue(elements.get(2).isBulkString());
        assertTrue(elements.get(3).isArray());
    }

    @Test
    public void testDecodeArrayWithNullElement() throws RESPException {
        recv("*3\r\n$3\r\nfoo\r\n$-1\r\n$3\r\nbar\r\n");
        RESPValue value = decoder.next();

        List<RESPValue> elements = value.asArray();
        assertEquals(3, elements.size());
        assertEquals("foo", elements.get(0).asString());
        assertTrue(elements.get(1).isNull());
        assertEquals("bar", elements.get(2).asString());
    }

    @Test
    public void testDecodeNestedArray() throws RESPException {
        recv("*2\r\n*2\r\n:1\r\n:2\r\n*2\r\n:3\r\n:4\r\n");
        RESPValue value = decoder.next();

        List<RESPValue> outer = value.asArray();
        assertEquals(2, outer.size());

        List<RESPValue> inner1 = outer.get(0).asArray();
        assertEquals(2, inner1.size());
        assertEquals(1, inner1.get(0).asLong());
        assertEquals(2, inner1.get(1).asLong());

        List<RESPValue> inner2 = outer.get(1).asArray();
        assertEquals(2, inner2.size());
        assertEquals(3, inner2.get(0).asLong());
        assertEquals(4, inner2.get(1).asLong());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Incremental decoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testIncompleteSimpleString() throws RESPException {
        recv("+OK");
        assertNull(decoder.next());

        recv("\r\n");
        RESPValue value = decoder.next();
        assertEquals("OK", value.asString());
    }

    @Test
    public void testIncompleteBulkString() throws RESPException {
        recv("$5\r\nhel");
        assertNull(decoder.next());

        recv("lo\r\n");
        RESPValue value = decoder.next();
        assertEquals("hello", value.asString());
    }

    @Test
    public void testIncompleteArray() throws RESPException {
        recv("*2\r\n$3\r\nfoo\r\n");
        assertNull(decoder.next());

        recv("$3\r\nbar\r\n");
        RESPValue value = decoder.next();
        List<RESPValue> elements = value.asArray();
        assertEquals(2, elements.size());
    }

    @Test
    public void testMultipleCompleteValuesInOneReceive() throws RESPException {
        recv("+OK\r\n:100\r\n$3\r\nfoo\r\n");

        RESPValue v1 = decoder.next();
        assertEquals("OK", v1.asString());

        RESPValue v2 = decoder.next();
        assertEquals(100, v2.asLong());

        RESPValue v3 = decoder.next();
        assertEquals("foo", v3.asString());

        assertNull(decoder.next());
    }

    @Test
    public void testByteByByteDecoding() throws RESPException {
        String data = "+OK\r\n";
        for (int i = 0; i < data.length() - 1; i++) {
            recv(data.substring(i, i + 1));
            assertNull(decoder.next());
        }

        recv(data.substring(data.length() - 1));
        RESPValue value = decoder.next();
        assertEquals("OK", value.asString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reset and buffer management
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testReset() throws RESPException {
        recv("+partial");
        decoder.reset();
        assertEquals(0, decoder.bufferedBytes());

        recv("+OK\r\n");
        RESPValue value = decoder.next();
        assertEquals("OK", value.asString());
    }

    @Test
    public void testBufferedBytes() {
        assertEquals(0, decoder.bufferedBytes());

        recv("+OK");
        assertEquals(3, decoder.bufferedBytes());

        recv("\r\n");
        assertEquals(5, decoder.bufferedBytes());
    }

    @Test
    public void testBufferGrowth() throws RESPException {
        // Create a decoder with small initial capacity
        decoder = new RESPDecoder(8);

        // Send data larger than initial capacity
        recv("$20\r\n12345678901234567890\r\n");
        RESPValue value = decoder.next();
        assertEquals("12345678901234567890", value.asString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test(expected = RESPException.class)
    public void testUnknownTypePrefix() throws RESPException {
        recv("X123\r\n");
        decoder.next();
    }

    @Test(expected = RESPException.class)
    public void testInvalidArrayCount() throws RESPException {
        recv("*abc\r\n");
        decoder.next();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Empty buffer cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testNextOnEmptyBuffer() throws RESPException {
        assertNull(decoder.next());
    }

    @Test
    public void testReceiveEmptyBuffer() throws RESPException {
        recv(ByteBuffer.allocate(0));
        assertNull(decoder.next());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Roundtrip tests (encode then decode)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testRoundtripCommand() throws RESPException {
        RESPEncoder encoder = new RESPEncoder();
        ByteBuffer encoded = encoder.encodeCommand("SET", new String[] { "key", "value" });

        // Decode as array
        recv(encoded);
        RESPValue value = decoder.next();

        assertTrue(value.isArray());
        List<RESPValue> elements = value.asArray();
        assertEquals(3, elements.size());
        assertEquals("SET", elements.get(0).asString());
        assertEquals("key", elements.get(1).asString());
        assertEquals("value", elements.get(2).asString());
    }

    @Test(expected = RESPException.class)
    public void testHugeArrayCountRejected() throws RESPException {
        StringBuilder sb = new StringBuilder();
        sb.append('*');
        sb.append(RESPDecoder.MAX_COLLECTION_ELEMENTS + 1);
        sb.append("\r\n");
        recv(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
        decoder.next();
    }

    @Test(expected = RESPException.class)
    public void testDeepNestingRejected() throws RESPException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= RESPDecoder.MAX_NESTING_DEPTH; i++) {
            sb.append("*1\r\n");
        }
        recv(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
        decoder.next();
    }

}

