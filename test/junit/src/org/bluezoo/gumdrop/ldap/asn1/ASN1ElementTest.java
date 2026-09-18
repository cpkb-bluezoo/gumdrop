/*
 * ASN1ElementTest.java
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

package org.bluezoo.gumdrop.ldap.asn1;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for Asn1Element.
 */
public class ASN1ElementTest {

    // Test primitive element construction

    @Test
    public void testPrimitiveElement() {
        byte[] value = {0x01, 0x02, 0x03};
        Asn1Element element = new Asn1Element(Asn1Type.OCTET_STRING, value);
        
        assertEquals(Asn1Type.OCTET_STRING, element.getTag());
        assertArrayEquals(value, element.getValue());
        assertNull(element.getChildren());
        assertFalse(element.isConstructed());
    }

    @Test
    public void testPrimitiveElementEmptyValue() {
        Asn1Element element = new Asn1Element(Asn1Type.NULL, new byte[0]);
        
        assertEquals(Asn1Type.NULL, element.getTag());
        assertEquals(0, element.getValue().length);
    }

    // Test constructed element construction

    @Test
    public void testConstructedElement() {
        List<Asn1Element> children = new ArrayList<Asn1Element>();
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01}));
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x02}));
        
        Asn1Element element = new Asn1Element(Asn1Type.SEQUENCE, children);
        
        assertEquals(Asn1Type.SEQUENCE, element.getTag());
        assertTrue(element.isConstructed());
        assertNull(element.getValue());
        assertNotNull(element.getChildren());
        assertEquals(2, element.getChildCount());
    }

    @Test
    public void testConstructedElementEmptyChildren() {
        List<Asn1Element> children = new ArrayList<Asn1Element>();
        Asn1Element element = new Asn1Element(Asn1Type.SEQUENCE, children);
        
        assertEquals(0, element.getChildCount());
    }

    // Test tag class extraction

    @Test
    public void testGetTagClassUniversal() {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01});
        assertEquals(Asn1Type.CLASS_UNIVERSAL, element.getTagClass());
    }

    @Test
    public void testGetTagClassContext() {
        int contextTag = Asn1Type.contextTag(5, false);
        Asn1Element element = new Asn1Element(contextTag, new byte[] {0x01});
        assertEquals(Asn1Type.CLASS_CONTEXT, element.getTagClass());
    }

    @Test
    public void testGetTagClassApplication() {
        int appTag = Asn1Type.applicationTag(3, true);
        Asn1Element element = new Asn1Element(appTag, new ArrayList<Asn1Element>());
        assertEquals(Asn1Type.CLASS_APPLICATION, element.getTagClass());
    }

    // Test tag number extraction

    @Test
    public void testGetTagNumber() {
        Asn1Element intElement = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01});
        assertEquals(2, intElement.getTagNumber());  // INTEGER = 0x02
        
        int ctxTag7 = Asn1Type.contextTag(7, false);
        Asn1Element ctxElement = new Asn1Element(ctxTag7, new byte[] {0x01});
        assertEquals(7, ctxElement.getTagNumber());
    }

    // Test value accessors

    @Test
    public void testAsBooleanTrue() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.BOOLEAN, new byte[] {(byte) 0xFF});
        assertTrue(element.asBoolean());
    }

    @Test
    public void testAsBooleanFalse() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.BOOLEAN, new byte[] {0x00});
        assertFalse(element.asBoolean());
    }

    @Test
    public void testAsBooleanNonZero() throws Asn1Exception {
        // Any non-zero value is true
        Asn1Element element = new Asn1Element(Asn1Type.BOOLEAN, new byte[] {0x01});
        assertTrue(element.asBoolean());
    }

    @Test(expected = Asn1Exception.class)
    public void testAsBooleanInvalidLength() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.BOOLEAN, new byte[] {0x00, 0x01});
        element.asBoolean();
    }

    @Test
    public void testAsIntPositive() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x2A});
        assertEquals(42, element.asInt());
    }

    @Test
    public void testAsIntNegative() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {(byte) 0xFF});
        assertEquals(-1, element.asInt());
    }

    @Test
    public void testAsIntTwoBytes() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01, 0x00});
        assertEquals(256, element.asInt());
    }

    @Test
    public void testAsIntFourBytes() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x12, 0x34, 0x56, 0x78});
        assertEquals(0x12345678, element.asInt());
    }

    @Test
    public void testAsIntNegativeTwoBytes() throws Asn1Exception {
        // -256 = 0xFF00
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {(byte) 0xFF, 0x00});
        assertEquals(-256, element.asInt());
    }

    @Test(expected = Asn1Exception.class)
    public void testAsIntEmpty() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[0]);
        element.asInt();
    }

    @Test
    public void testAsLong() throws Asn1Exception {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, 
                new byte[] {0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00});
        assertEquals(0x100000000L, element.asLong());
    }

    @Test
    public void testAsString() {
        byte[] value = "hello".getBytes();
        Asn1Element element = new Asn1Element(Asn1Type.OCTET_STRING, value);
        assertEquals("hello", element.asString());
    }

    @Test
    public void testAsStringEmpty() {
        Asn1Element element = new Asn1Element(Asn1Type.OCTET_STRING, new byte[0]);
        assertEquals("", element.asString());
    }

    @Test
    public void testAsStringNull() {
        List<Asn1Element> children = new ArrayList<Asn1Element>();
        Asn1Element element = new Asn1Element(Asn1Type.SEQUENCE, children);
        assertNull(element.asString());
    }

    @Test
    public void testAsOctetString() {
        byte[] value = {0x01, 0x02, 0x03};
        Asn1Element element = new Asn1Element(Asn1Type.OCTET_STRING, value);
        assertArrayEquals(value, element.asOctetString());
    }

    // Test child access

    @Test
    public void testGetChild() throws Asn1Exception {
        List<Asn1Element> children = new ArrayList<Asn1Element>();
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01}));
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x02}));
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x03}));
        
        Asn1Element element = new Asn1Element(Asn1Type.SEQUENCE, children);
        
        assertEquals(1, element.getChild(0).asInt());
        assertEquals(2, element.getChild(1).asInt());
        assertEquals(3, element.getChild(2).asInt());
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetChildOutOfBounds() {
        List<Asn1Element> children = new ArrayList<Asn1Element>();
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01}));
        
        Asn1Element element = new Asn1Element(Asn1Type.SEQUENCE, children);
        element.getChild(5);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetChildFromPrimitive() {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01});
        element.getChild(0);
    }

    @Test
    public void testGetChildCountPrimitive() {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01});
        assertEquals(0, element.getChildCount());
    }

    // Test toString

    @Test
    public void testToStringPrimitive() {
        Asn1Element element = new Asn1Element(Asn1Type.INTEGER, new byte[] {0x2A});
        String str = element.toString();
        
        assertTrue(str.contains("INTEGER"));
    }

    @Test
    public void testToStringOctetString() {
        Asn1Element element = new Asn1Element(Asn1Type.OCTET_STRING, "test".getBytes());
        String str = element.toString();
        
        assertTrue(str.contains("OCTET STRING"));
        assertTrue(str.contains("test"));
    }

    @Test
    public void testToStringConstructed() {
        List<Asn1Element> children = new ArrayList<Asn1Element>();
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01}));
        
        Asn1Element element = new Asn1Element(Asn1Type.SEQUENCE, children);
        String str = element.toString();
        
        assertTrue(str.contains("SEQUENCE"));
        assertTrue(str.contains("INTEGER"));
    }

    @Test
    public void testToStringContextTag() {
        int ctxTag = Asn1Type.contextTag(3, false);
        Asn1Element element = new Asn1Element(ctxTag, new byte[] {0x01});
        String str = element.toString();
        
        assertTrue(str.contains("CONTEXT"));
        assertTrue(str.contains("3"));
    }

    // Test immutability of children list

    @Test
    public void testChildrenListImmutable() {
        List<Asn1Element> children = new ArrayList<Asn1Element>();
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x01}));
        
        Asn1Element element = new Asn1Element(Asn1Type.SEQUENCE, children);
        
        // Modifying original list shouldn't affect element
        children.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x02}));
        assertEquals(1, element.getChildCount());
        
        // Returned list should be unmodifiable
        List<Asn1Element> returnedChildren = element.getChildren();
        try {
            returnedChildren.add(new Asn1Element(Asn1Type.INTEGER, new byte[] {0x03}));
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }
}

