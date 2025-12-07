/*
 * SessionSerializerTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Unit tests for SessionSerializer.
 * 
 * Tests the session serialization utilities including:
 * - Hex encoding/decoding
 * - Session ID serialization
 * 
 * Note: Full serialize/deserialize tests require a Context which is
 * complex to mock. These tests focus on the utility methods.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionSerializerTest {

    // ===== Hex Utility Method Tests =====
    
    @Test
    public void testHexToBytes() throws Exception {
        Method hexToBytes = SessionSerializer.class.getDeclaredMethod("hexToBytes", String.class);
        hexToBytes.setAccessible(true);
        
        byte[] result = (byte[]) hexToBytes.invoke(null, "0123456789abcdef");
        
        assertEquals(8, result.length);
        assertEquals((byte) 0x01, result[0]);
        assertEquals((byte) 0x23, result[1]);
        assertEquals((byte) 0x45, result[2]);
        assertEquals((byte) 0x67, result[3]);
        assertEquals((byte) 0x89, result[4]);
        assertEquals((byte) 0xab, result[5]);
        assertEquals((byte) 0xcd, result[6]);
        assertEquals((byte) 0xef, result[7]);
    }

    @Test
    public void testHexToBytesUpperCase() throws Exception {
        Method hexToBytes = SessionSerializer.class.getDeclaredMethod("hexToBytes", String.class);
        hexToBytes.setAccessible(true);
        
        byte[] result = (byte[]) hexToBytes.invoke(null, "ABCDEF");
        
        assertEquals(3, result.length);
        assertEquals((byte) 0xAB, result[0]);
        assertEquals((byte) 0xCD, result[1]);
        assertEquals((byte) 0xEF, result[2]);
    }

    @Test
    public void testHexToBytesEmpty() throws Exception {
        Method hexToBytes = SessionSerializer.class.getDeclaredMethod("hexToBytes", String.class);
        hexToBytes.setAccessible(true);
        
        byte[] result = (byte[]) hexToBytes.invoke(null, "");
        
        assertEquals(0, result.length);
    }

    @Test
    public void testHexToBytesSessionId() throws Exception {
        // Typical 32-char session ID -> 16 bytes
        Method hexToBytes = SessionSerializer.class.getDeclaredMethod("hexToBytes", String.class);
        hexToBytes.setAccessible(true);
        
        String sessionId = "0123456789abcdef0123456789abcdef";
        byte[] result = (byte[]) hexToBytes.invoke(null, sessionId);
        
        assertEquals(16, result.length);
    }

    @Test
    public void testBytesToHex() throws Exception {
        Method bytesToHex = SessionSerializer.class.getDeclaredMethod("bytesToHex", byte[].class);
        bytesToHex.setAccessible(true);
        
        byte[] input = new byte[] { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef };
        String result = (String) bytesToHex.invoke(null, (Object) input);
        
        assertEquals("0123456789abcdef", result);
    }

    @Test
    public void testBytesToHexEmpty() throws Exception {
        Method bytesToHex = SessionSerializer.class.getDeclaredMethod("bytesToHex", byte[].class);
        bytesToHex.setAccessible(true);
        
        byte[] input = new byte[0];
        String result = (String) bytesToHex.invoke(null, (Object) input);
        
        assertEquals("", result);
    }

    @Test
    public void testBytesToHexSingleByte() throws Exception {
        Method bytesToHex = SessionSerializer.class.getDeclaredMethod("bytesToHex", byte[].class);
        bytesToHex.setAccessible(true);
        
        byte[] input = new byte[] { (byte) 0xff };
        String result = (String) bytesToHex.invoke(null, (Object) input);
        
        assertEquals("ff", result);
    }

    @Test
    public void testBytesToHexLeadingZero() throws Exception {
        Method bytesToHex = SessionSerializer.class.getDeclaredMethod("bytesToHex", byte[].class);
        bytesToHex.setAccessible(true);
        
        byte[] input = new byte[] { 0x00, 0x01, 0x0f };
        String result = (String) bytesToHex.invoke(null, (Object) input);
        
        assertEquals("00010f", result);
    }

    @Test
    public void testHexRoundTrip() throws Exception {
        Method hexToBytes = SessionSerializer.class.getDeclaredMethod("hexToBytes", String.class);
        Method bytesToHex = SessionSerializer.class.getDeclaredMethod("bytesToHex", byte[].class);
        hexToBytes.setAccessible(true);
        bytesToHex.setAccessible(true);
        
        String original = "deadbeefcafebabe";
        byte[] bytes = (byte[]) hexToBytes.invoke(null, original);
        String result = (String) bytesToHex.invoke(null, (Object) bytes);
        
        assertEquals(original, result);
    }

    @Test
    public void testHexRoundTripSessionId() throws Exception {
        Method hexToBytes = SessionSerializer.class.getDeclaredMethod("hexToBytes", String.class);
        Method bytesToHex = SessionSerializer.class.getDeclaredMethod("bytesToHex", byte[].class);
        hexToBytes.setAccessible(true);
        bytesToHex.setAccessible(true);
        
        // Test with typical session ID format
        String original = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
        byte[] bytes = (byte[]) hexToBytes.invoke(null, original);
        String result = (String) bytesToHex.invoke(null, (Object) bytes);
        
        assertEquals(original, result);
    }

    // ===== Session ID Serialization Tests =====

    @Test
    public void testSessionSerializeId() throws Exception {
        // Create a minimal session using reflection to bypass Context requirement
        // We test the serializeId method directly
        
        String sessionId = "0123456789abcdef0123456789abcdef";
        ByteBuffer buf = ByteBuffer.allocate(16);
        
        // Test the static deserializeId method
        Method hexToBytes = SessionSerializer.class.getDeclaredMethod("hexToBytes", String.class);
        hexToBytes.setAccessible(true);
        byte[] bytes = (byte[]) hexToBytes.invoke(null, sessionId);
        buf.put(bytes);
        buf.flip();
        
        String result = Session.deserializeId(buf);
        assertEquals(sessionId, result);
    }

    @Test
    public void testSessionDeserializeId() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Write 16 bytes representing "0123456789abcdef0123456789abcdef"
        buf.put(new byte[] { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef,
                             0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef });
        buf.flip();
        
        String result = Session.deserializeId(buf);
        
        assertEquals("0123456789abcdef0123456789abcdef", result);
    }

    @Test
    public void testSessionDeserializeIdAllZeros() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.put(new byte[16]); // All zeros
        buf.flip();
        
        String result = Session.deserializeId(buf);
        
        assertEquals("00000000000000000000000000000000", result);
    }

    @Test
    public void testSessionDeserializeIdAllOnes() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(16);
        byte[] ones = new byte[16];
        for (int i = 0; i < 16; i++) {
            ones[i] = (byte) 0xff;
        }
        buf.put(ones);
        buf.flip();
        
        String result = Session.deserializeId(buf);
        
        assertEquals("ffffffffffffffffffffffffffffffff", result);
    }

    // ===== Serialization Field Number Constants =====

    @Test
    public void testFieldNumberConstants() throws Exception {
        // Verify field number constants are accessible and have expected values
        java.lang.reflect.Field fieldId = SessionSerializer.class.getDeclaredField("FIELD_ID");
        java.lang.reflect.Field fieldCreationTime = SessionSerializer.class.getDeclaredField("FIELD_CREATION_TIME");
        java.lang.reflect.Field fieldLastAccessedTime = SessionSerializer.class.getDeclaredField("FIELD_LAST_ACCESSED_TIME");
        java.lang.reflect.Field fieldMaxInactiveInterval = SessionSerializer.class.getDeclaredField("FIELD_MAX_INACTIVE_INTERVAL");
        java.lang.reflect.Field fieldAttributes = SessionSerializer.class.getDeclaredField("FIELD_ATTRIBUTES");
        
        fieldId.setAccessible(true);
        fieldCreationTime.setAccessible(true);
        fieldLastAccessedTime.setAccessible(true);
        fieldMaxInactiveInterval.setAccessible(true);
        fieldAttributes.setAccessible(true);
        
        assertEquals(1, fieldId.getInt(null));
        assertEquals(2, fieldCreationTime.getInt(null));
        assertEquals(3, fieldLastAccessedTime.getInt(null));
        assertEquals(4, fieldMaxInactiveInterval.getInt(null));
        assertEquals(5, fieldAttributes.getInt(null));
    }

    @Test
    public void testAttributeFieldNumberConstants() throws Exception {
        java.lang.reflect.Field attrKey = SessionSerializer.class.getDeclaredField("ATTR_KEY");
        java.lang.reflect.Field attrStringValue = SessionSerializer.class.getDeclaredField("ATTR_STRING_VALUE");
        java.lang.reflect.Field attrBoolValue = SessionSerializer.class.getDeclaredField("ATTR_BOOL_VALUE");
        java.lang.reflect.Field attrIntValue = SessionSerializer.class.getDeclaredField("ATTR_INT_VALUE");
        java.lang.reflect.Field attrDoubleValue = SessionSerializer.class.getDeclaredField("ATTR_DOUBLE_VALUE");
        java.lang.reflect.Field attrBytesValue = SessionSerializer.class.getDeclaredField("ATTR_BYTES_VALUE");
        
        attrKey.setAccessible(true);
        attrStringValue.setAccessible(true);
        attrBoolValue.setAccessible(true);
        attrIntValue.setAccessible(true);
        attrDoubleValue.setAccessible(true);
        attrBytesValue.setAccessible(true);
        
        assertEquals(1, attrKey.getInt(null));
        assertEquals(2, attrStringValue.getInt(null));
        assertEquals(3, attrBoolValue.getInt(null));
        assertEquals(4, attrIntValue.getInt(null));
        assertEquals(5, attrDoubleValue.getInt(null));
        assertEquals(6, attrBytesValue.getInt(null));
    }

}

