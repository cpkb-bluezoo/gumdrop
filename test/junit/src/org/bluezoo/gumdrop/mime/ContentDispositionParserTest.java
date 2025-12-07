/*
 * ContentDispositionParserTest.java
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
 * Unit tests for {@link ContentDispositionParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentDispositionParserTest {

    @Test
    public void testParseSimpleAttachment() {
        ContentDisposition cd = ContentDispositionParser.parse("attachment");
        
        assertNotNull(cd);
        assertTrue(cd.isDispositionType("attachment"));
        assertNull(cd.getParameters());
    }
    
    @Test
    public void testParseSimpleInline() {
        ContentDisposition cd = ContentDispositionParser.parse("inline");
        
        assertNotNull(cd);
        assertTrue(cd.isDispositionType("inline"));
    }
    
    @Test
    public void testParseFormData() {
        ContentDisposition cd = ContentDispositionParser.parse("form-data; name=\"field1\"");
        
        assertNotNull(cd);
        assertTrue(cd.isDispositionType("form-data"));
        assertEquals("field1", cd.getParameter("name"));
    }
    
    @Test
    public void testParseWithFilename() {
        ContentDisposition cd = ContentDispositionParser.parse("attachment; filename=\"document.pdf\"");
        
        assertNotNull(cd);
        assertTrue(cd.isDispositionType("attachment"));
        assertEquals("document.pdf", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseWithUnquotedFilename() {
        ContentDisposition cd = ContentDispositionParser.parse("attachment; filename=document.pdf");
        
        assertNotNull(cd);
        assertEquals("document.pdf", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseFormDataWithFilename() {
        ContentDisposition cd = ContentDispositionParser.parse("form-data; name=\"upload\"; filename=\"test.txt\"");
        
        assertNotNull(cd);
        assertTrue(cd.isDispositionType("form-data"));
        assertEquals("upload", cd.getParameter("name"));
        assertEquals("test.txt", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseWithWhitespace() {
        ContentDisposition cd = ContentDispositionParser.parse("  attachment  ;  filename=\"test.txt\"  ");
        
        assertNotNull(cd);
        assertTrue(cd.isDispositionType("attachment"));
        assertEquals("test.txt", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseQuotedFilenameWithSpaces() {
        ContentDisposition cd = ContentDispositionParser.parse("attachment; filename=\"my document.pdf\"");
        
        assertNotNull(cd);
        assertEquals("my document.pdf", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseQuotedFilenameWithEscapes() {
        ContentDisposition cd = ContentDispositionParser.parse("attachment; filename=\"test\\\"file.txt\"");
        
        assertNotNull(cd);
        assertEquals("test\"file.txt", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseNullReturnsNull() {
        assertNull(ContentDispositionParser.parse(null));
    }
    
    @Test
    public void testParseEmptyReturnsNull() {
        assertNull(ContentDispositionParser.parse(""));
    }
    
    @Test
    public void testParseMultipleParameters() {
        ContentDisposition cd = ContentDispositionParser.parse(
            "attachment; filename=\"report.pdf\"; creation-date=\"Wed, 12 Feb 1997 16:29:51 -0500\"; size=12345");
        
        assertNotNull(cd);
        assertEquals("report.pdf", cd.getParameter("filename"));
        assertNotNull(cd.getParameter("creation-date"));
        assertEquals("12345", cd.getParameter("size"));
    }
    
    @Test
    public void testParseRFC2047EncodedFilename() {
        // Test that RFC 2047 encoded words in filename are decoded
        ContentDisposition cd = ContentDispositionParser.parse("attachment; filename=\"=?UTF-8?B?dGVzdC50eHQ=?=\"");
        
        assertNotNull(cd);
        // The encoded "test.txt" should be decoded
        assertEquals("test.txt", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseCasePreservation() {
        ContentDisposition cd = ContentDispositionParser.parse("Attachment; FileName=\"Test.TXT\"");
        
        assertNotNull(cd);
        // Disposition type comparison should be case-insensitive
        assertTrue(cd.isDispositionType("attachment"));
        // Parameter value case should be preserved
        assertEquals("Test.TXT", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseFilenameWithPath() {
        // Some clients send full paths - backslashes are escape characters in quoted strings
        // So to get a literal backslash, the sender should double them
        ContentDisposition cd = ContentDispositionParser.parse("attachment; filename=\"C:\\\\Users\\\\test\\\\document.pdf\"");
        
        assertNotNull(cd);
        assertEquals("C:\\Users\\test\\document.pdf", cd.getParameter("filename"));
    }
    
    @Test
    public void testParseInternationalFilename() {
        // UTF-8 filename directly in value (common in modern implementations)
        ContentDisposition cd = ContentDispositionParser.parse("attachment; filename=\"日本語.txt\"");
        
        assertNotNull(cd);
        assertEquals("日本語.txt", cd.getParameter("filename"));
    }
}

