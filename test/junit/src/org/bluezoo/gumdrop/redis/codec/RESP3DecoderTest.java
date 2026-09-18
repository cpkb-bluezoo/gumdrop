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
 * Unit tests for RESP3 type decoding in {@link RespDecoder}.
 */
public class RESP3DecoderTest {

    private RespDecoder decoder;

    @Before
    public void setUp() {
        decoder = new RespDecoder();
    }

    private ByteBuffer wrap(String data) {
        return ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
    }

    private void recv(String data) {
        try {
            decoder.receive(wrap(data));
        } catch (RespException e) {
            throw new AssertionError(e);
        }
    }

    // Map type

    @Test
    public void testDecodeMap() throws RespException {
        recv("%2\r\n+first\r\n:1\r\n+second\r\n:2\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isMap());
        Map<RespValue, RespValue> map = value.asMap();
        assertEquals(2, map.size());
    }

    @Test
    public void testDecodeEmptyMap() throws RespException {
        recv("%0\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isMap());
        assertEquals(0, value.asMap().size());
    }

    // Set type

    @Test
    public void testDecodeSet() throws RespException {
        recv("~3\r\n+a\r\n+b\r\n+c\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isSet());
        List<RespValue> elements = value.asArray();
        assertEquals(3, elements.size());
    }

    // Double type

    @Test
    public void testDecodeDouble() throws RespException {
        recv(",3.14\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isDouble());
        assertEquals(3.14, value.asDouble(), 0.001);
    }

    @Test
    public void testDecodeDoubleInfinity() throws RespException {
        recv(",inf\r\n");
        RespValue value = decoder.next();

        assertTrue(Double.isInfinite(value.asDouble()));
        assertTrue(value.asDouble() > 0);
    }

    @Test
    public void testDecodeDoubleNegativeInfinity() throws RespException {
        recv(",-inf\r\n");
        RespValue value = decoder.next();

        assertTrue(Double.isInfinite(value.asDouble()));
        assertTrue(value.asDouble() < 0);
    }

    @Test
    public void testDecodeDoubleNaN() throws RespException {
        recv(",nan\r\n");
        RespValue value = decoder.next();

        assertTrue(Double.isNaN(value.asDouble()));
    }

    // Boolean type

    @Test
    public void testDecodeBooleanTrue() throws RespException {
        recv("#t\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBoolean());
        assertTrue(value.asBoolean());
    }

    @Test
    public void testDecodeBooleanFalse() throws RespException {
        recv("#f\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBoolean());
        assertFalse(value.asBoolean());
    }

    // Null type

    @Test
    public void testDecodeNull() throws RespException {
        recv("_\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertEquals(RespType.NULL, value.getType());
        assertNull(value.asString());
    }

    // Push type

    @Test
    public void testDecodePush() throws RespException {
        recv(">3\r\n+message\r\n+channel\r\n$5\r\nhello\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isPush());
        List<RespValue> elements = value.asPush();
        assertEquals(3, elements.size());
        assertEquals("message", elements.get(0).asString());
        assertEquals("channel", elements.get(1).asString());
    }

    // Verbatim string type

    @Test
    public void testDecodeVerbatimString() throws RespException {
        recv("=15\r\ntxt:Some string\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isVerbatimString());
        assertEquals("txt", value.getVerbatimEncoding());
        assertEquals("Some string", value.asString());
    }

    // Big number type

    @Test
    public void testDecodeBigNumber() throws RespException {
        recv("(3492890328409238509324850943850943825024385\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBigNumber());
        assertEquals("3492890328409238509324850943850943825024385", value.asString());
    }

    // Blob error type

    @Test
    public void testDecodeBlobError() throws RespException {
        recv("!11\r\nERR unknown\r\n");
        RespValue value = decoder.next();

        assertNotNull(value);
        assertTrue(value.isBlobError());
        assertEquals("ERR unknown", value.asString());
        assertEquals("ERR", value.getErrorType());
    }

    // Type prefix roundtrip

    @Test
    public void testResp3TypePrefixes() throws RespException {
        assertEquals(RespType.MAP, RespType.fromPrefix((byte) '%'));
        assertEquals(RespType.SET, RespType.fromPrefix((byte) '~'));
        assertEquals(RespType.DOUBLE, RespType.fromPrefix((byte) ','));
        assertEquals(RespType.BOOLEAN, RespType.fromPrefix((byte) '#'));
        assertEquals(RespType.NULL, RespType.fromPrefix((byte) '_'));
        assertEquals(RespType.PUSH, RespType.fromPrefix((byte) '>'));
        assertEquals(RespType.VERBATIM_STRING, RespType.fromPrefix((byte) '='));
        assertEquals(RespType.BIG_NUMBER, RespType.fromPrefix((byte) '('));
        assertEquals(RespType.BLOB_ERROR, RespType.fromPrefix((byte) '!'));
    }

    // Incomplete data returns null

    @Test
    public void testIncompleteMapReturnsNull() throws RespException {
        recv("%2\r\n+first\r\n");
        assertNull(decoder.next());
    }

    @Test
    public void testIncompleteDoubleReturnsNull() throws RespException {
        recv(",3.14");
        assertNull(decoder.next());
    }

    // toString coverage

    @Test
    public void testResp3ToString() {
        assertEquals(",3.14", RespValue.doubleValue(3.14).toString());
        assertEquals("#t", RespValue.booleanValue(true).toString());
        assertEquals("#f", RespValue.booleanValue(false).toString());
        assertEquals("_", RespValue.resp3Null().toString());
        assertEquals("(12345", RespValue.bigNumber("12345").toString());
    }

    @Test(expected = RespException.class)
    public void testHugeMapCountRejected() throws RespException {
        StringBuilder sb = new StringBuilder();
        sb.append('%').append(RespDecoder.MAX_COLLECTION_ELEMENTS + 1).append("\r\n");
        recv(sb.toString());
        decoder.next();
    }

}
