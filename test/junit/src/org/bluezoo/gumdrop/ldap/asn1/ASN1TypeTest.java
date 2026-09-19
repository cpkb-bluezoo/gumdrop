/*
 * ASN1TypeTest.java
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

/**
 * Unit tests for Asn1Type constants and utilities.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ASN1TypeTest {

    // Test tag class extraction
    
    @Test
    public void testGetTagClassUniversal() {
        assertEquals(Asn1Type.CLASS_UNIVERSAL, Asn1Type.getTagClass(Asn1Type.INTEGER));
        assertEquals(Asn1Type.CLASS_UNIVERSAL, Asn1Type.getTagClass(Asn1Type.BOOLEAN));
        assertEquals(Asn1Type.CLASS_UNIVERSAL, Asn1Type.getTagClass(Asn1Type.OCTET_STRING));
        assertEquals(Asn1Type.CLASS_UNIVERSAL, Asn1Type.getTagClass(Asn1Type.SEQUENCE));
    }

    @Test
    public void testGetTagClassApplication() {
        int appTag = Asn1Type.applicationTag(5, false);
        assertEquals(Asn1Type.CLASS_APPLICATION, Asn1Type.getTagClass(appTag));
    }

    @Test
    public void testGetTagClassContext() {
        int ctxTag = Asn1Type.contextTag(3, false);
        assertEquals(Asn1Type.CLASS_CONTEXT, Asn1Type.getTagClass(ctxTag));
    }

    // Test constructed flag

    @Test
    public void testIsConstructedPrimitive() {
        assertFalse(Asn1Type.isConstructed(Asn1Type.INTEGER));
        assertFalse(Asn1Type.isConstructed(Asn1Type.BOOLEAN));
        assertFalse(Asn1Type.isConstructed(Asn1Type.OCTET_STRING));
    }

    @Test
    public void testIsConstructedSequence() {
        assertTrue(Asn1Type.isConstructed(Asn1Type.SEQUENCE));
        assertTrue(Asn1Type.isConstructed(Asn1Type.SET));
    }

    @Test
    public void testIsConstructedContextTags() {
        int primitiveCtx = Asn1Type.contextTag(0, false);
        int constructedCtx = Asn1Type.contextTag(0, true);
        
        assertFalse(Asn1Type.isConstructed(primitiveCtx));
        assertTrue(Asn1Type.isConstructed(constructedCtx));
    }

    // Test tag number extraction

    @Test
    public void testGetTagNumber() {
        assertEquals(2, Asn1Type.getTagNumber(Asn1Type.INTEGER));
        assertEquals(1, Asn1Type.getTagNumber(Asn1Type.BOOLEAN));
        assertEquals(4, Asn1Type.getTagNumber(Asn1Type.OCTET_STRING));
        assertEquals(16, Asn1Type.getTagNumber(Asn1Type.SEQUENCE)); // 0x30 & 0x1F = 0x10 = 16
    }

    @Test
    public void testGetTagNumberContext() {
        int ctxTag5 = Asn1Type.contextTag(5, false);
        assertEquals(5, Asn1Type.getTagNumber(ctxTag5));
        
        int ctxTag0 = Asn1Type.contextTag(0, true);
        assertEquals(0, Asn1Type.getTagNumber(ctxTag0));
    }

    // Test tag creation

    @Test
    public void testContextTag() {
        // Context-specific primitive tag 0 should be 0x80
        assertEquals(0x80, Asn1Type.contextTag(0, false));
        
        // Context-specific constructed tag 0 should be 0xA0
        assertEquals(0xA0, Asn1Type.contextTag(0, true));
        
        // Context-specific primitive tag 3 should be 0x83
        assertEquals(0x83, Asn1Type.contextTag(3, false));
        
        // Context-specific constructed tag 7 should be 0xA7
        assertEquals(0xA7, Asn1Type.contextTag(7, true));
    }

    @Test
    public void testApplicationTag() {
        // Application primitive tag 0 should be 0x40
        assertEquals(0x40, Asn1Type.applicationTag(0, false));
        
        // Application constructed tag 0 should be 0x60
        assertEquals(0x60, Asn1Type.applicationTag(0, true));
        
        // Application primitive tag 3 should be 0x43 (BindRequest)
        assertEquals(0x43, Asn1Type.applicationTag(3, false));
    }

    // Test tag name conversion

    @Test
    public void testGetTagNameUniversal() {
        assertEquals("BOOLEAN", Asn1Type.getTagName(Asn1Type.BOOLEAN));
        assertEquals("INTEGER", Asn1Type.getTagName(Asn1Type.INTEGER));
        assertEquals("OCTET STRING", Asn1Type.getTagName(Asn1Type.OCTET_STRING));
        assertEquals("SEQUENCE", Asn1Type.getTagName(Asn1Type.SEQUENCE));
        assertEquals("SET", Asn1Type.getTagName(Asn1Type.SET));
        assertEquals("NULL", Asn1Type.getTagName(Asn1Type.NULL));
        assertEquals("ENUMERATED", Asn1Type.getTagName(Asn1Type.ENUMERATED));
    }

    @Test
    public void testGetTagNameContext() {
        String name = Asn1Type.getTagName(Asn1Type.contextTag(3, false));
        assertTrue(name.contains("CONTEXT"));
        assertTrue(name.contains("3"));
        assertTrue(name.contains("primitive"));
        
        String name2 = Asn1Type.getTagName(Asn1Type.contextTag(5, true));
        assertTrue(name2.contains("CONTEXT"));
        assertTrue(name2.contains("5"));
        assertTrue(name2.contains("constructed"));
    }

    @Test
    public void testGetTagNameApplication() {
        String name = Asn1Type.getTagName(Asn1Type.applicationTag(0, true));
        assertTrue(name.contains("APPLICATION"));
        assertTrue(name.contains("0"));
    }

    // Test universal type constants

    @Test
    public void testUniversalTypeConstants() {
        // Verify the values match ASN.1 universal tag numbers
        assertEquals(0x01, Asn1Type.BOOLEAN);
        assertEquals(0x02, Asn1Type.INTEGER);
        assertEquals(0x03, Asn1Type.BIT_STRING);
        assertEquals(0x04, Asn1Type.OCTET_STRING);
        assertEquals(0x05, Asn1Type.NULL);
        assertEquals(0x06, Asn1Type.OBJECT_IDENTIFIER);
        assertEquals(0x0A, Asn1Type.ENUMERATED);
        assertEquals(0x0C, Asn1Type.UTF8_STRING);
        assertEquals(0x30, Asn1Type.SEQUENCE);
        assertEquals(0x31, Asn1Type.SET);
    }

    // Test class constants

    @Test
    public void testClassConstants() {
        assertEquals(0x00, Asn1Type.CLASS_UNIVERSAL);
        assertEquals(0x40, Asn1Type.CLASS_APPLICATION);
        assertEquals(0x80, Asn1Type.CLASS_CONTEXT);
        assertEquals(0xC0, Asn1Type.CLASS_PRIVATE);
    }

    @Test
    public void testConstructedConstant() {
        assertEquals(0x20, Asn1Type.CONSTRUCTED);
        assertEquals(0x00, Asn1Type.PRIMITIVE);
    }
}

