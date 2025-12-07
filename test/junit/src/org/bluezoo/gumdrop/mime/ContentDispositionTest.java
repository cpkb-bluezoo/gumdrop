/*
 * ContentDispositionTest.java
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
 * Unit tests for {@link ContentDisposition}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentDispositionTest {

    @Test
    public void testSimpleDisposition() {
        ContentDisposition cd = new ContentDisposition("attachment", null);
        
        assertEquals("attachment", cd.getDispositionType());
        assertNull(cd.getParameters());
    }
    
    @Test
    public void testDispositionWithFilename() {
        Parameter filename = new Parameter("filename", "document.pdf");
        ContentDisposition cd = new ContentDisposition("attachment", Collections.singletonList(filename));
        
        assertEquals("attachment", cd.getDispositionType());
        assertEquals("document.pdf", cd.getParameter("filename"));
    }
    
    @Test
    public void testFormDataDisposition() {
        List<Parameter> params = Arrays.asList(
            new Parameter("name", "field1"),
            new Parameter("filename", "upload.txt")
        );
        ContentDisposition cd = new ContentDisposition("form-data", params);
        
        assertEquals("form-data", cd.getDispositionType());
        assertEquals("field1", cd.getParameter("name"));
        assertEquals("upload.txt", cd.getParameter("filename"));
    }
    
    @Test
    public void testIsDispositionType() {
        ContentDisposition cd = new ContentDisposition("attachment", null);
        
        assertTrue(cd.isDispositionType("attachment"));
        assertTrue(cd.isDispositionType("ATTACHMENT"));
        assertTrue(cd.isDispositionType("Attachment"));
        assertFalse(cd.isDispositionType("inline"));
    }
    
    @Test
    public void testInlineDisposition() {
        ContentDisposition cd = new ContentDisposition("inline", null);
        
        assertTrue(cd.isDispositionType("inline"));
        assertFalse(cd.isDispositionType("attachment"));
    }
    
    @Test
    public void testParameterCaseInsensitive() {
        Parameter filename = new Parameter("FileName", "test.txt");
        ContentDisposition cd = new ContentDisposition("attachment", Collections.singletonList(filename));
        
        assertEquals("test.txt", cd.getParameter("filename"));
        assertEquals("test.txt", cd.getParameter("FILENAME"));
        assertEquals("test.txt", cd.getParameter("FileName"));
    }
    
    @Test
    public void testHasParameter() {
        Parameter filename = new Parameter("filename", "test.txt");
        ContentDisposition cd = new ContentDisposition("attachment", Collections.singletonList(filename));
        
        assertTrue(cd.hasParameter("filename"));
        assertTrue(cd.hasParameter("FILENAME"));
        assertFalse(cd.hasParameter("name"));
    }
    
    @Test
    public void testGetMissingParameter() {
        ContentDisposition cd = new ContentDisposition("attachment", null);
        
        assertNull(cd.getParameter("filename"));
    }
    
    @Test
    public void testMultipleParameters() {
        List<Parameter> params = Arrays.asList(
            new Parameter("filename", "report.pdf"),
            new Parameter("creation-date", "\"Wed, 12 Feb 1997 16:29:51 -0500\""),
            new Parameter("size", "12345")
        );
        ContentDisposition cd = new ContentDisposition("attachment", params);
        
        assertEquals("report.pdf", cd.getParameter("filename"));
        assertNotNull(cd.getParameter("creation-date"));
        assertEquals("12345", cd.getParameter("size"));
    }
    
    @Test
    public void testToString() {
        ContentDisposition cd = new ContentDisposition("attachment", null);
        assertEquals("attachment", cd.toString());
    }
    
    @Test
    public void testToStringWithFilename() {
        Parameter filename = new Parameter("filename", "test.txt");
        ContentDisposition cd = new ContentDisposition("attachment", Collections.singletonList(filename));
        
        String str = cd.toString();
        assertTrue(str.startsWith("attachment"));
        assertTrue(str.contains("filename"));
    }
    
    @Test
    public void testEquals() {
        ContentDisposition cd1 = new ContentDisposition("attachment", null);
        ContentDisposition cd2 = new ContentDisposition("attachment", null);
        ContentDisposition cd3 = new ContentDisposition("ATTACHMENT", null);
        
        assertEquals(cd1, cd2);
        assertEquals(cd1, cd3); // Case insensitive
    }
    
    @Test
    public void testNotEquals() {
        ContentDisposition cd1 = new ContentDisposition("attachment", null);
        ContentDisposition cd2 = new ContentDisposition("inline", null);
        
        assertNotEquals(cd1, cd2);
    }
    
    @Test
    public void testHashCode() {
        ContentDisposition cd1 = new ContentDisposition("attachment", null);
        ContentDisposition cd2 = new ContentDisposition("attachment", null);
        
        assertEquals(cd1.hashCode(), cd2.hashCode());
    }
    
    @Test
    public void testGetParametersReturnsUnmodifiableList() {
        Parameter p = new Parameter("filename", "test.txt");
        ContentDisposition cd = new ContentDisposition("attachment", Collections.singletonList(p));
        
        List<Parameter> params = cd.getParameters();
        assertNotNull(params);
        
        try {
            params.add(new Parameter("test", "value"));
            fail("Should not be able to modify parameters list");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }
}

