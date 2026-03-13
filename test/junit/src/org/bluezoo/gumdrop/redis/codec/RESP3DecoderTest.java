/*
 * RESP3DecoderTest.java
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

package org.bluezoo.gumdrop.redis.codec;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for RESP3 type decoding in {@link RESPDecoder}.
 */
public class RESP3DecoderTest {

    private RESPDecoder decoder;

    @Before
    public void setUp() {
        decoder = new RESPDecoder();
    }

    private ByteBuffer wrap(String data) {
        return ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
    }

    // Map type

    @Test
    public void testDecodeMap() throws RESPException {
        decoder.receive(wrap("%2\r\n+first\r\n:1\r\n+second\r\n:2\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isMap());
        Map<RESPValue, RESPValue> map = value.asMap();
        assertEquals(2, map.size());
    }

    @Test
    public void testDecodeEmptyMap() throws RESPException {
        decoder.receive(wrap("%0\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isMap());
        assertEquals(0, value.asMap().size());
    }

    // Set type

    @Test
    public void testDecodeSet() throws RESPException {
        decoder.receive(wrap("~3\r\n+a\r\n+b\r\n+c\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isSet());
        List<RESPValue> elements = value.asArray();
        assertEquals(3, elements.size());
    }

    // Double type

    @Test
    public void testDecodeDouble() throws RESPException {
        decoder.receive(wrap(",3.14\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isDouble());
        assertEquals(3.14, value.asDouble(), 0.001);
    }

    @Test
    public void testDecodeDoubleInfinity() throws RESPException {
        decoder.receive(wrap(",inf\r\n"));
        RESPValue value = decoder.next();

        assertTrue(Double.isInfinite(value.asDouble()));
        assertTrue(value.asDouble() > 0);
    }

    @Test
    public void testDecodeDoubleNegativeInfinity() throws RESPException {
        decoder.receive(wrap(",-inf\r\n"));
        RESPValue value = decoder.next();

        assertTrue(Double.isInfinite(value.asDouble()));
        assertTrue(value.asDouble() < 0);
    }

    @Test
    public void testDecodeDoubleNaN() throws RESPException {
        decoder.receive(wrap(",nan\r\n"));
        RESPValue value = decoder.next();

        assertTrue(Double.isNaN(value.asDouble()));
    }

    // Boolean type

    @Test
    public void testDecodeBooleanTrue() throws RESPException {
        decoder.receive(wrap("#t\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBoolean());
        assertTrue(value.asBoolean());
    }

    @Test
    public void testDecodeBooleanFalse() throws RESPException {
        decoder.receive(wrap("#f\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBoolean());
        assertFalse(value.asBoolean());
    }

    // Null type

    @Test
    public void testDecodeNull() throws RESPException {
        decoder.receive(wrap("_\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertEquals(RESPType.NULL, value.getType());
        assertNull(value.asString());
    }

    // Push type

    @Test
    public void testDecodePush() throws RESPException {
        decoder.receive(wrap(">3\r\n+message\r\n+channel\r\n$5\r\nhello\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isPush());
        List<RESPValue> elements = value.asPush();
        assertEquals(3, elements.size());
        assertEquals("message", elements.get(0).asString());
        assertEquals("channel", elements.get(1).asString());
    }

    // Verbatim string type

    @Test
    public void testDecodeVerbatimString() throws RESPException {
        decoder.receive(wrap("=15\r\ntxt:Some string\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isVerbatimString());
        assertEquals("txt", value.getVerbatimEncoding());
        assertEquals("Some string", value.asString());
    }

    // Big number type

    @Test
    public void testDecodeBigNumber() throws RESPException {
        decoder.receive(wrap("(3492890328409238509324850943850943825024385\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBigNumber());
        assertEquals("3492890328409238509324850943850943825024385", value.asString());
    }

    // Blob error type

    @Test
    public void testDecodeBlobError() throws RESPException {
        decoder.receive(wrap("!11\r\nERR unknown\r\n"));
        RESPValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBlobError());
        assertEquals("ERR unknown", value.asString());
        assertEquals("ERR", value.getErrorType());
    }

    // Type prefix roundtrip

    @Test
    public void testResp3TypePrefixes() throws RESPException {
        assertEquals(RESPType.MAP, RESPType.fromPrefix((byte) '%'));
        assertEquals(RESPType.SET, RESPType.fromPrefix((byte) '~'));
        assertEquals(RESPType.DOUBLE, RESPType.fromPrefix((byte) ','));
        assertEquals(RESPType.BOOLEAN, RESPType.fromPrefix((byte) '#'));
        assertEquals(RESPType.NULL, RESPType.fromPrefix((byte) '_'));
        assertEquals(RESPType.PUSH, RESPType.fromPrefix((byte) '>'));
        assertEquals(RESPType.VERBATIM_STRING, RESPType.fromPrefix((byte) '='));
        assertEquals(RESPType.BIG_NUMBER, RESPType.fromPrefix((byte) '('));
        assertEquals(RESPType.BLOB_ERROR, RESPType.fromPrefix((byte) '!'));
    }

    // Incomplete data returns null

    @Test
    public void testIncompleteMapReturnsNull() throws RESPException {
        decoder.receive(wrap("%2\r\n+first\r\n"));
        assertNull(decoder.next());
    }

    @Test
    public void testIncompleteDoubleReturnsNull() throws RESPException {
        decoder.receive(wrap(",3.14"));
        assertNull(decoder.next());
    }

    // toString coverage

    @Test
    public void testResp3ToString() {
        assertEquals(",3.14", RESPValue.doubleValue(3.14).toString());
        assertEquals("#t", RESPValue.booleanValue(true).toString());
        assertEquals("#f", RESPValue.booleanValue(false).toString());
        assertEquals("_", RESPValue.resp3Null().toString());
        assertEquals("(12345", RESPValue.bigNumber("12345").toString());
    }

}
