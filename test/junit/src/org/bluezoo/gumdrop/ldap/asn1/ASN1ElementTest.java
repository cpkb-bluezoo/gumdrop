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
 * Unit tests for ASN1Element.
 */
public class ASN1ElementTest {

    // Test primitive element construction

    @Test
    public void testPrimitiveElement() {
        byte[] value = {0x01, 0x02, 0x03};
        ASN1Element element = new ASN1Element(ASN1Type.OCTET_STRING, value);
        
        assertEquals(ASN1Type.OCTET_STRING, element.getTag());
        assertArrayEquals(value, element.getValue());
        assertNull(element.getChildren());
        assertFalse(element.isConstructed());
    }

    @Test
    public void testPrimitiveElementEmptyValue() {
        ASN1Element element = new ASN1Element(ASN1Type.NULL, new byte[0]);
        
        assertEquals(ASN1Type.NULL, element.getTag());
        assertEquals(0, element.getValue().length);
    }

    // Test constructed element construction

    @Test
    public void testConstructedElement() {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01}));
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x02}));
        
        ASN1Element element = new ASN1Element(ASN1Type.SEQUENCE, children);
        
        assertEquals(ASN1Type.SEQUENCE, element.getTag());
        assertTrue(element.isConstructed());
        assertNull(element.getValue());
        assertNotNull(element.getChildren());
        assertEquals(2, element.getChildCount());
    }

    @Test
    public void testConstructedElementEmptyChildren() {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        ASN1Element element = new ASN1Element(ASN1Type.SEQUENCE, children);
        
        assertEquals(0, element.getChildCount());
    }

    // Test tag class extraction

    @Test
    public void testGetTagClassUniversal() {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01});
        assertEquals(ASN1Type.CLASS_UNIVERSAL, element.getTagClass());
    }

    @Test
    public void testGetTagClassContext() {
        int contextTag = ASN1Type.contextTag(5, false);
        ASN1Element element = new ASN1Element(contextTag, new byte[] {0x01});
        assertEquals(ASN1Type.CLASS_CONTEXT, element.getTagClass());
    }

    @Test
    public void testGetTagClassApplication() {
        int appTag = ASN1Type.applicationTag(3, true);
        ASN1Element element = new ASN1Element(appTag, new ArrayList<ASN1Element>());
        assertEquals(ASN1Type.CLASS_APPLICATION, element.getTagClass());
    }

    // Test tag number extraction

    @Test
    public void testGetTagNumber() {
        ASN1Element intElement = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01});
        assertEquals(2, intElement.getTagNumber());  // INTEGER = 0x02
        
        int ctxTag7 = ASN1Type.contextTag(7, false);
        ASN1Element ctxElement = new ASN1Element(ctxTag7, new byte[] {0x01});
        assertEquals(7, ctxElement.getTagNumber());
    }

    // Test value accessors

    @Test
    public void testAsBooleanTrue() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.BOOLEAN, new byte[] {(byte) 0xFF});
        assertTrue(element.asBoolean());
    }

    @Test
    public void testAsBooleanFalse() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.BOOLEAN, new byte[] {0x00});
        assertFalse(element.asBoolean());
    }

    @Test
    public void testAsBooleanNonZero() throws ASN1Exception {
        // Any non-zero value is true
        ASN1Element element = new ASN1Element(ASN1Type.BOOLEAN, new byte[] {0x01});
        assertTrue(element.asBoolean());
    }

    @Test(expected = ASN1Exception.class)
    public void testAsBooleanInvalidLength() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.BOOLEAN, new byte[] {0x00, 0x01});
        element.asBoolean();
    }

    @Test
    public void testAsIntPositive() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x2A});
        assertEquals(42, element.asInt());
    }

    @Test
    public void testAsIntNegative() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {(byte) 0xFF});
        assertEquals(-1, element.asInt());
    }

    @Test
    public void testAsIntTwoBytes() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01, 0x00});
        assertEquals(256, element.asInt());
    }

    @Test
    public void testAsIntFourBytes() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x12, 0x34, 0x56, 0x78});
        assertEquals(0x12345678, element.asInt());
    }

    @Test
    public void testAsIntNegativeTwoBytes() throws ASN1Exception {
        // -256 = 0xFF00
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {(byte) 0xFF, 0x00});
        assertEquals(-256, element.asInt());
    }

    @Test(expected = ASN1Exception.class)
    public void testAsIntEmpty() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[0]);
        element.asInt();
    }

    @Test
    public void testAsLong() throws ASN1Exception {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, 
                new byte[] {0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00});
        assertEquals(0x100000000L, element.asLong());
    }

    @Test
    public void testAsString() {
        byte[] value = "hello".getBytes();
        ASN1Element element = new ASN1Element(ASN1Type.OCTET_STRING, value);
        assertEquals("hello", element.asString());
    }

    @Test
    public void testAsStringEmpty() {
        ASN1Element element = new ASN1Element(ASN1Type.OCTET_STRING, new byte[0]);
        assertEquals("", element.asString());
    }

    @Test
    public void testAsStringNull() {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        ASN1Element element = new ASN1Element(ASN1Type.SEQUENCE, children);
        assertNull(element.asString());
    }

    @Test
    public void testAsOctetString() {
        byte[] value = {0x01, 0x02, 0x03};
        ASN1Element element = new ASN1Element(ASN1Type.OCTET_STRING, value);
        assertArrayEquals(value, element.asOctetString());
    }

    // Test child access

    @Test
    public void testGetChild() throws ASN1Exception {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01}));
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x02}));
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x03}));
        
        ASN1Element element = new ASN1Element(ASN1Type.SEQUENCE, children);
        
        assertEquals(1, element.getChild(0).asInt());
        assertEquals(2, element.getChild(1).asInt());
        assertEquals(3, element.getChild(2).asInt());
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetChildOutOfBounds() {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01}));
        
        ASN1Element element = new ASN1Element(ASN1Type.SEQUENCE, children);
        element.getChild(5);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetChildFromPrimitive() {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01});
        element.getChild(0);
    }

    @Test
    public void testGetChildCountPrimitive() {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01});
        assertEquals(0, element.getChildCount());
    }

    // Test toString

    @Test
    public void testToStringPrimitive() {
        ASN1Element element = new ASN1Element(ASN1Type.INTEGER, new byte[] {0x2A});
        String str = element.toString();
        
        assertTrue(str.contains("INTEGER"));
    }

    @Test
    public void testToStringOctetString() {
        ASN1Element element = new ASN1Element(ASN1Type.OCTET_STRING, "test".getBytes());
        String str = element.toString();
        
        assertTrue(str.contains("OCTET STRING"));
        assertTrue(str.contains("test"));
    }

    @Test
    public void testToStringConstructed() {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01}));
        
        ASN1Element element = new ASN1Element(ASN1Type.SEQUENCE, children);
        String str = element.toString();
        
        assertTrue(str.contains("SEQUENCE"));
        assertTrue(str.contains("INTEGER"));
    }

    @Test
    public void testToStringContextTag() {
        int ctxTag = ASN1Type.contextTag(3, false);
        ASN1Element element = new ASN1Element(ctxTag, new byte[] {0x01});
        String str = element.toString();
        
        assertTrue(str.contains("CONTEXT"));
        assertTrue(str.contains("3"));
    }

    // Test immutability of children list

    @Test
    public void testChildrenListImmutable() {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x01}));
        
        ASN1Element element = new ASN1Element(ASN1Type.SEQUENCE, children);
        
        // Modifying original list shouldn't affect element
        children.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x02}));
        assertEquals(1, element.getChildCount());
        
        // Returned list should be unmodifiable
        List<ASN1Element> returnedChildren = element.getChildren();
        try {
            returnedChildren.add(new ASN1Element(ASN1Type.INTEGER, new byte[] {0x03}));
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }
}

