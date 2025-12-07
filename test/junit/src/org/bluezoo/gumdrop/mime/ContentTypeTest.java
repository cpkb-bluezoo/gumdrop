/*
 * ContentTypeTest.java
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ContentType}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentTypeTest {

    @Test
    public void testSimpleContentType() {
        ContentType ct = new ContentType("text", "plain", null);
        
        assertEquals("text", ct.getPrimaryType());
        assertEquals("plain", ct.getSubType());
        assertNull(ct.getParameters());
    }
    
    @Test
    public void testContentTypeWithCharset() {
        Parameter charset = new Parameter("charset", "utf-8");
        ContentType ct = new ContentType("text", "plain", Collections.singletonList(charset));
        
        assertEquals("text", ct.getPrimaryType());
        assertEquals("plain", ct.getSubType());
        assertEquals("utf-8", ct.getParameter("charset"));
    }
    
    @Test
    public void testIsPrimaryType() {
        ContentType ct = new ContentType("text", "html", null);
        
        assertTrue(ct.isPrimaryType("text"));
        assertTrue(ct.isPrimaryType("TEXT"));
        assertTrue(ct.isPrimaryType("Text"));
        assertFalse(ct.isPrimaryType("image"));
    }
    
    @Test
    public void testIsSubType() {
        ContentType ct = new ContentType("text", "html", null);
        
        assertTrue(ct.isSubType("html"));
        assertTrue(ct.isSubType("HTML"));
        assertTrue(ct.isSubType("Html"));
        assertFalse(ct.isSubType("plain"));
    }
    
    @Test
    public void testIsMimeTypeTwoArgs() {
        ContentType ct = new ContentType("application", "json", null);
        
        assertTrue(ct.isMimeType("application", "json"));
        assertTrue(ct.isMimeType("APPLICATION", "JSON"));
        assertFalse(ct.isMimeType("application", "xml"));
        assertFalse(ct.isMimeType("text", "json"));
    }
    
    @Test
    public void testIsMimeTypeOneArg() {
        ContentType ct = new ContentType("multipart", "form-data", null);
        
        assertTrue(ct.isMimeType("multipart/form-data"));
        assertTrue(ct.isMimeType("MULTIPART/FORM-DATA"));
        assertTrue(ct.isMimeType("Multipart/Form-Data"));
        assertFalse(ct.isMimeType("multipart/mixed"));
        assertFalse(ct.isMimeType("text/plain"));
    }
    
    @Test
    public void testIsMimeTypeInvalidFormat() {
        ContentType ct = new ContentType("text", "plain", null);
        
        assertFalse(ct.isMimeType("textplain")); // no slash
        assertFalse(ct.isMimeType("text/")); // empty subtype
        assertFalse(ct.isMimeType("/plain")); // empty primary type
    }
    
    @Test
    public void testMultipleParameters() {
        List<Parameter> params = Arrays.asList(
            new Parameter("charset", "utf-8"),
            new Parameter("boundary", "----=_Part_123"),
            new Parameter("format", "flowed")
        );
        ContentType ct = new ContentType("multipart", "mixed", params);
        
        assertEquals("utf-8", ct.getParameter("charset"));
        assertEquals("----=_Part_123", ct.getParameter("boundary"));
        assertEquals("flowed", ct.getParameter("format"));
    }
    
    @Test
    public void testParameterCaseInsensitive() {
        Parameter charset = new Parameter("CharSet", "utf-8");
        ContentType ct = new ContentType("text", "plain", Collections.singletonList(charset));
        
        assertEquals("utf-8", ct.getParameter("charset"));
        assertEquals("utf-8", ct.getParameter("CHARSET"));
        assertEquals("utf-8", ct.getParameter("CharSet"));
    }
    
    @Test
    public void testHasParameter() {
        Parameter charset = new Parameter("charset", "utf-8");
        ContentType ct = new ContentType("text", "plain", Collections.singletonList(charset));
        
        assertTrue(ct.hasParameter("charset"));
        assertTrue(ct.hasParameter("CHARSET"));
        assertFalse(ct.hasParameter("boundary"));
    }
    
    @Test
    public void testGetMissingParameter() {
        ContentType ct = new ContentType("text", "plain", null);
        
        assertNull(ct.getParameter("charset"));
    }
    
    @Test
    public void testToString() {
        ContentType ct = new ContentType("text", "plain", null);
        assertEquals("text/plain", ct.toString());
    }
    
    @Test
    public void testToStringWithParameters() {
        List<Parameter> params = Arrays.asList(
            new Parameter("charset", "utf-8"),
            new Parameter("format", "flowed")
        );
        ContentType ct = new ContentType("text", "plain", params);
        
        String str = ct.toString();
        assertTrue(str.startsWith("text/plain"));
        assertTrue(str.contains("charset=utf-8") || str.contains("charset=\"utf-8\""));
    }
    
    @Test
    public void testEquals() {
        ContentType ct1 = new ContentType("text", "plain", null);
        ContentType ct2 = new ContentType("text", "plain", null);
        ContentType ct3 = new ContentType("TEXT", "PLAIN", null);
        
        assertEquals(ct1, ct2);
        assertEquals(ct1, ct3); // Case insensitive
    }
    
    @Test
    public void testNotEquals() {
        ContentType ct1 = new ContentType("text", "plain", null);
        ContentType ct2 = new ContentType("text", "html", null);
        ContentType ct3 = new ContentType("image", "plain", null);
        
        assertNotEquals(ct1, ct2);
        assertNotEquals(ct1, ct3);
    }
    
    @Test
    public void testEqualsWithParameters() {
        Parameter p1 = new Parameter("charset", "utf-8");
        Parameter p2 = new Parameter("charset", "utf-8");
        
        ContentType ct1 = new ContentType("text", "plain", Collections.singletonList(p1));
        ContentType ct2 = new ContentType("text", "plain", Collections.singletonList(p2));
        
        assertEquals(ct1, ct2);
    }
    
    @Test
    public void testHashCode() {
        ContentType ct1 = new ContentType("text", "plain", null);
        ContentType ct2 = new ContentType("text", "plain", null);
        
        assertEquals(ct1.hashCode(), ct2.hashCode());
    }
    
    @Test
    public void testCommonTypes() {
        // Test various common content types
        ContentType textPlain = new ContentType("text", "plain", null);
        assertTrue(textPlain.isMimeType("text", "plain"));
        
        ContentType textHtml = new ContentType("text", "html", null);
        assertTrue(textHtml.isPrimaryType("text"));
        
        ContentType appJson = new ContentType("application", "json", null);
        assertTrue(appJson.isPrimaryType("application"));
        
        ContentType multipart = new ContentType("multipart", "mixed", null);
        assertTrue(multipart.isPrimaryType("multipart"));
        
        ContentType imagePng = new ContentType("image", "png", null);
        assertTrue(imagePng.isPrimaryType("image"));
    }
    
    @Test
    public void testDuplicateParameters() {
        // First parameter with same name should be used
        List<Parameter> params = Arrays.asList(
            new Parameter("charset", "utf-8"),
            new Parameter("charset", "iso-8859-1")
        );
        ContentType ct = new ContentType("text", "plain", params);
        
        assertEquals("utf-8", ct.getParameter("charset"));
    }
    
    @Test
    public void testGetParametersReturnsUnmodifiableList() {
        Parameter p = new Parameter("charset", "utf-8");
        ContentType ct = new ContentType("text", "plain", Collections.singletonList(p));
        
        List<Parameter> params = ct.getParameters();
        assertNotNull(params);
        
        try {
            params.add(new Parameter("test", "value"));
            fail("Should not be able to modify parameters list");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }
}

