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

package org.bluezoo.gumdrop.servlet.session;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for SessionSerializer.
 * 
 * Tests the session serialization utilities including:
 * - Hex encoding/decoding
 * - Session ID serialization
 * - Full session serialization/deserialization
 * - Delta serialization/deserialization
 * - Various attribute types
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SessionSerializerTest {

    private MockSessionContext context;
    private static final String TEST_SESSION_ID = "0123456789abcdef0123456789abcdef";

    @Before
    public void setUp() {
        context = new MockSessionContext();
        context.setSessionTimeout(1800);
    }

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

    // ===== Full Session Serialization Tests =====

    @Test
    public void testSerializeDeserializeEmptySession() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        original.creationTime = 1000L;
        original.lastAccessedTime = 2000L;
        original.maxInactiveInterval = 3600;

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        assertEquals(TEST_SESSION_ID, deserialized.getId());
        assertEquals(1000L, deserialized.getCreationTime());
        assertEquals(2000L, deserialized.getLastAccessedTime());
        assertEquals(3600, deserialized.getMaxInactiveInterval());
    }

    @Test
    public void testSerializeDeserializeWithStringAttribute() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        original.setAttribute("name", "John Doe");

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        assertEquals("John Doe", deserialized.getAttribute("name"));
    }

    @Test
    public void testSerializeDeserializeWithIntegerAttribute() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        original.setAttribute("count", 42);
        original.setAttribute("longVal", 123456789L);

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        // Protobuf stores all integers as int64/Long
        assertEquals(42L, deserialized.getAttribute("count"));
        assertEquals(123456789L, deserialized.getAttribute("longVal"));
    }

    @Test
    public void testSerializeDeserializeWithDoubleAttribute() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        original.setAttribute("price", 99.99);
        original.setAttribute("pi", 3.14159);

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        assertEquals(99.99, (Double) deserialized.getAttribute("price"), 0.001);
        assertEquals(3.14159, (Double) deserialized.getAttribute("pi"), 0.00001);
    }

    @Test
    public void testSerializeDeserializeWithBooleanAttribute() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        original.setAttribute("active", true);
        original.setAttribute("disabled", false);

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        assertEquals(true, deserialized.getAttribute("active"));
        assertEquals(false, deserialized.getAttribute("disabled"));
    }

    @Test
    public void testSerializeDeserializeWithByteArrayAttribute() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        byte[] data = new byte[] { 1, 2, 3, 4, 5 };
        original.setAttribute("binary", data);

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        byte[] result = (byte[]) deserialized.getAttribute("binary");
        assertArrayEquals(data, result);
    }

    @Test
    public void testSerializeDeserializeWithMultipleAttributes() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        original.setAttribute("string", "hello");
        original.setAttribute("int", 123);
        original.setAttribute("double", 1.5);
        original.setAttribute("bool", true);
        original.setAttribute("bytes", new byte[] { 0x0a, 0x0b });

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        assertEquals("hello", deserialized.getAttribute("string"));
        // Protobuf stores all integers as int64/Long
        assertEquals(123L, deserialized.getAttribute("int"));
        assertEquals(1.5, (Double) deserialized.getAttribute("double"), 0.001);
        assertEquals(true, deserialized.getAttribute("bool"));
        assertArrayEquals(new byte[] { 0x0a, 0x0b }, 
                         (byte[]) deserialized.getAttribute("bytes"));
    }

    @Test
    public void testSerializeDeserializeWithSerializableObject() throws IOException {
        Session original = new Session(context, TEST_SESSION_ID);
        TestSerializableObject obj = new TestSerializableObject("test", 42);
        original.setAttribute("object", obj);

        ByteBuffer buf = original.serialize();
        Session deserialized = Session.deserialize(context, buf);

        TestSerializableObject result = (TestSerializableObject) deserialized.getAttribute("object");
        assertNotNull(result);
        assertEquals("test", result.name);
        assertEquals(42, result.value);
    }

    // ===== Delta Serialization Tests =====

    @Test
    public void testSerializeDeltaWithUpdatedAttribute() throws IOException {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("existing", "old");
        session.setAttribute("unchanged", "keep");
        session.clearDirtyState();

        // Modify one attribute
        session.setAttribute("existing", "new");

        Set<String> dirty = session.getDirtyAttributes();
        Set<String> removed = session.getRemovedAttributes();

        ByteBuffer buf = SessionSerializer.serializeDelta(session, dirty, removed);
        SessionSerializer.DeltaUpdate delta = SessionSerializer.deserializeDelta(buf);

        assertEquals(TEST_SESSION_ID, delta.sessionId);
        assertEquals(1, delta.updatedAttributes.size());
        assertEquals("new", delta.updatedAttributes.get("existing"));
        assertTrue(delta.removedAttributes.isEmpty());
    }

    @Test
    public void testSerializeDeltaWithRemovedAttribute() throws IOException {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("toRemove", "value");
        session.setAttribute("unchanged", "keep");
        session.clearDirtyState();

        // Remove one attribute
        session.removeAttribute("toRemove");

        Set<String> dirty = session.getDirtyAttributes();
        Set<String> removed = session.getRemovedAttributes();

        ByteBuffer buf = SessionSerializer.serializeDelta(session, dirty, removed);
        SessionSerializer.DeltaUpdate delta = SessionSerializer.deserializeDelta(buf);

        assertEquals(TEST_SESSION_ID, delta.sessionId);
        assertTrue(delta.updatedAttributes.isEmpty());
        assertEquals(1, delta.removedAttributes.size());
        assertTrue(delta.removedAttributes.contains("toRemove"));
    }

    @Test
    public void testSerializeDeltaWithMixedChanges() throws IOException {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("update", "old");
        session.setAttribute("remove", "value");
        session.setAttribute("unchanged", "keep");
        session.clearDirtyState();

        // Update and remove
        session.setAttribute("update", "new");
        session.setAttribute("add", "added");
        session.removeAttribute("remove");

        Set<String> dirty = session.getDirtyAttributes();
        Set<String> removed = session.getRemovedAttributes();

        ByteBuffer buf = SessionSerializer.serializeDelta(session, dirty, removed);
        SessionSerializer.DeltaUpdate delta = SessionSerializer.deserializeDelta(buf);

        assertEquals(2, delta.updatedAttributes.size());
        assertEquals("new", delta.updatedAttributes.get("update"));
        assertEquals("added", delta.updatedAttributes.get("add"));
        assertEquals(1, delta.removedAttributes.size());
        assertTrue(delta.removedAttributes.contains("remove"));
    }

    @Test
    public void testDeltaIncludesVersion() throws IOException {
        Session session = new Session(context, TEST_SESSION_ID);
        session.setAttribute("attr", "value");
        long expectedVersion = session.version;

        Set<String> dirty = session.getDirtyAttributes();
        Set<String> removed = session.getRemovedAttributes();

        ByteBuffer buf = SessionSerializer.serializeDelta(session, dirty, removed);
        SessionSerializer.DeltaUpdate delta = SessionSerializer.deserializeDelta(buf);

        assertEquals(expectedVersion, delta.version);
    }

    @Test
    public void testSerializeDeltaWithEmptyChanges() throws IOException {
        Session session = new Session(context, TEST_SESSION_ID);
        session.clearDirtyState();

        Set<String> dirty = new HashSet<>();
        Set<String> removed = new HashSet<>();

        ByteBuffer buf = SessionSerializer.serializeDelta(session, dirty, removed);
        SessionSerializer.DeltaUpdate delta = SessionSerializer.deserializeDelta(buf);

        assertEquals(TEST_SESSION_ID, delta.sessionId);
        assertTrue(delta.updatedAttributes.isEmpty());
        assertTrue(delta.removedAttributes.isEmpty());
    }

    // ===== Session ID Serialization Round-Trip =====

    @Test
    public void testSessionIdSerializationRoundTrip() throws IOException {
        Session session = new Session(context, TEST_SESSION_ID);

        ByteBuffer buf = ByteBuffer.allocate(16);
        session.serializeId(buf);
        buf.flip();

        String deserialized = Session.deserializeId(buf);
        assertEquals(TEST_SESSION_ID, deserialized);
    }

    // ===== Helper class for serialization testing =====

    /**
     * Simple serializable object for testing.
     */
    static class TestSerializableObject implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name;
        final int value;

        TestSerializableObject(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

}

