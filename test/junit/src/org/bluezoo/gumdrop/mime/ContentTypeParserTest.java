/*
 * ContentTypeParserTest.java
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

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ContentTypeParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentTypeParserTest {

    @Test
    public void testParseSimple() {
        ContentType ct = ContentTypeParser.parse("text/plain");
        
        assertNotNull(ct);
        assertEquals("text", ct.getPrimaryType());
        assertEquals("plain", ct.getSubType());
        assertNull(ct.getParameters());
    }
    
    @Test
    public void testParseWithCharset() {
        ContentType ct = ContentTypeParser.parse("text/html; charset=utf-8");
        
        assertNotNull(ct);
        assertEquals("text", ct.getPrimaryType());
        assertEquals("html", ct.getSubType());
        assertEquals("utf-8", ct.getParameter("charset"));
    }
    
    @Test
    public void testParseWithQuotedParameter() {
        ContentType ct = ContentTypeParser.parse("multipart/form-data; boundary=\"----=_Part_123\"");
        
        assertNotNull(ct);
        assertEquals("multipart", ct.getPrimaryType());
        assertEquals("form-data", ct.getSubType());
        assertEquals("----=_Part_123", ct.getParameter("boundary"));
    }
    
    @Test
    public void testParseMultipleParameters() {
        ContentType ct = ContentTypeParser.parse("text/plain; charset=utf-8; format=flowed; delsp=yes");
        
        assertNotNull(ct);
        assertEquals("text", ct.getPrimaryType());
        assertEquals("plain", ct.getSubType());
        assertEquals("utf-8", ct.getParameter("charset"));
        assertEquals("flowed", ct.getParameter("format"));
        assertEquals("yes", ct.getParameter("delsp"));
    }
    
    @Test
    public void testParseWithWhitespace() {
        ContentType ct = ContentTypeParser.parse("  text/plain  ;  charset = utf-8  ");
        
        assertNotNull(ct);
        assertEquals("text", ct.getPrimaryType());
        assertEquals("plain", ct.getSubType());
        assertEquals("utf-8", ct.getParameter("charset"));
    }
    
    @Test
    public void testParseQuotedParameterWithEscapes() {
        ContentType ct = ContentTypeParser.parse("text/plain; filename=\"test\\\"file\\\\.txt\"");
        
        assertNotNull(ct);
        assertEquals("test\"file\\.txt", ct.getParameter("filename"));
    }
    
    @Test
    public void testParseNullReturnsNull() {
        assertNull(ContentTypeParser.parse(null));
    }
    
    @Test
    public void testParseEmptyReturnsNull() {
        assertNull(ContentTypeParser.parse(""));
    }
    
    @Test
    public void testParseMissingSubtype() {
        assertNull(ContentTypeParser.parse("text"));
    }
    
    @Test
    public void testParseMissingSlash() {
        assertNull(ContentTypeParser.parse("textplain"));
    }
    
    @Test
    public void testParseCasePreservation() {
        // Type and subtype should be preserved as-is (though comparisons are case-insensitive)
        ContentType ct = ContentTypeParser.parse("Text/HTML; Charset=UTF-8");
        
        assertNotNull(ct);
        // The parser may normalize case or preserve it - test the isMimeType method
        assertTrue(ct.isMimeType("text", "html"));
        assertEquals("UTF-8", ct.getParameter("Charset"));
    }
    
    @Test
    public void testParseMultipartMixed() {
        ContentType ct = ContentTypeParser.parse("multipart/mixed; boundary=\"----=_NextPart_000_0000_01D12345.6789ABCD\"");
        
        assertNotNull(ct);
        assertTrue(ct.isMimeType("multipart", "mixed"));
        assertEquals("----=_NextPart_000_0000_01D12345.6789ABCD", ct.getParameter("boundary"));
    }
    
    @Test
    public void testParseApplicationJson() {
        ContentType ct = ContentTypeParser.parse("application/json; charset=utf-8");
        
        assertNotNull(ct);
        assertTrue(ct.isMimeType("application", "json"));
        assertEquals("utf-8", ct.getParameter("charset"));
    }
    
    @Test
    public void testParseImageWithMetadata() {
        ContentType ct = ContentTypeParser.parse("image/jpeg; name=\"photo.jpg\"");
        
        assertNotNull(ct);
        assertTrue(ct.isMimeType("image", "jpeg"));
        assertEquals("photo.jpg", ct.getParameter("name"));
    }
    
    @Test
    public void testParseSubtypeWithPlus() {
        ContentType ct = ContentTypeParser.parse("application/vnd.api+json");
        
        assertNotNull(ct);
        assertEquals("application", ct.getPrimaryType());
        assertEquals("vnd.api+json", ct.getSubType());
    }
    
    @Test
    public void testParseSubtypeWithDot() {
        ContentType ct = ContentTypeParser.parse("application/vnd.ms-excel");
        
        assertNotNull(ct);
        assertEquals("application", ct.getPrimaryType());
        assertEquals("vnd.ms-excel", ct.getSubType());
    }
    
    @Test
    public void testParseRFC2047EncodedParameter() {
        // Test that RFC 2047 encoded words in parameters are decoded
        ContentType ct = ContentTypeParser.parse("text/plain; name=\"=?UTF-8?B?dGVzdC50eHQ=?=\"");
        
        assertNotNull(ct);
        // The encoded "test.txt" should be decoded
        assertEquals("test.txt", ct.getParameter("name"));
    }
    
    @Test
    public void testParseUnquotedBoundary() {
        ContentType ct = ContentTypeParser.parse("multipart/form-data; boundary=simpleboundary");
        
        assertNotNull(ct);
        assertEquals("simpleboundary", ct.getParameter("boundary"));
    }
}

