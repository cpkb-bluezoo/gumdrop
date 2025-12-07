/*
 * MIMEVersionTest.java
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
 * Unit tests for {@link MIMEVersion}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MIMEVersionTest {

    @Test
    public void testVersion10() {
        MIMEVersion version = MIMEVersion.VERSION_1_0;
        
        assertNotNull(version);
        assertEquals("1.0", version.toString());
    }
    
    @Test
    public void testParse10() {
        MIMEVersion version = MIMEVersion.parse("1.0");
        
        assertNotNull(version);
        assertEquals(MIMEVersion.VERSION_1_0, version);
    }
    
    @Test
    public void testParseWithWhitespace() {
        MIMEVersion version = MIMEVersion.parse("  1.0  ");
        
        assertNotNull(version);
        assertEquals(MIMEVersion.VERSION_1_0, version);
    }
    
    @Test
    public void testParseUnknown() {
        MIMEVersion version = MIMEVersion.parse("2.0");
        
        // Unknown versions should return null
        assertNull(version);
    }
    
    @Test
    public void testParseNull() {
        MIMEVersion version = MIMEVersion.parse(null);
        
        assertNull(version);
    }
    
    @Test
    public void testParseEmpty() {
        MIMEVersion version = MIMEVersion.parse("");
        
        assertNull(version);
    }
    
    @Test
    public void testParseInvalid() {
        MIMEVersion version = MIMEVersion.parse("not a version");
        
        assertNull(version);
    }
    
    @Test
    public void testParseWithComment() {
        // RFC 2045 allows comments in MIME-Version header
        // Our simple parser doesn't strip comments, so this returns null
        MIMEVersion version = MIMEVersion.parse("1.0 (produced by Outlook)");
        
        // Comment handling is not implemented - returns null for non-exact match
        assertNull(version);
    }
}

