/*
 * ContentIDTest.java
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
 * Unit tests for {@link ContentID}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentIDTest {

    @Test
    public void testConstructor() {
        ContentID cid = new ContentID("abc123", "example.com");
        
        assertEquals("abc123", cid.getLocalPart());
        assertEquals("example.com", cid.getDomain());
    }
    
    @Test
    public void testToString() {
        ContentID cid = new ContentID("image001", "mail.example.com");
        
        String str = cid.toString();
        assertEquals("<image001@mail.example.com>", str);
    }
    
    @Test
    public void testEquals() {
        ContentID cid1 = new ContentID("abc123", "example.com");
        ContentID cid2 = new ContentID("abc123", "example.com");
        
        assertEquals(cid1, cid2);
    }
    
    @Test
    public void testNotEqualsDifferentLocalPart() {
        ContentID cid1 = new ContentID("abc123", "example.com");
        ContentID cid2 = new ContentID("xyz789", "example.com");
        
        assertNotEquals(cid1, cid2);
    }
    
    @Test
    public void testNotEqualsDifferentDomain() {
        ContentID cid1 = new ContentID("abc123", "example.com");
        ContentID cid2 = new ContentID("abc123", "other.com");
        
        assertNotEquals(cid1, cid2);
    }
    
    @Test
    public void testHashCode() {
        ContentID cid1 = new ContentID("abc123", "example.com");
        ContentID cid2 = new ContentID("abc123", "example.com");
        
        assertEquals(cid1.hashCode(), cid2.hashCode());
    }
    
    @Test
    public void testTypicalMessageId() {
        // Typical Message-ID format
        ContentID cid = new ContentID("CAKzMBDA8yFFaKE+abc123@mail.gmail.com", "mail.gmail.com");
        
        assertEquals("CAKzMBDA8yFFaKE+abc123@mail.gmail.com", cid.getLocalPart());
        assertEquals("mail.gmail.com", cid.getDomain());
    }
    
    @Test
    public void testContentIdWithNumbers() {
        ContentID cid = new ContentID("part1.E72C5B26.B8F21A30", "example.com");
        
        assertEquals("part1.E72C5B26.B8F21A30", cid.getLocalPart());
        assertEquals("example.com", cid.getDomain());
    }
    
    @Test
    public void testLocalPartWithSpecialChars() {
        // RFC 5322 allows various special characters in local part
        ContentID cid = new ContentID("user+tag.name", "example.com");
        
        assertEquals("user+tag.name", cid.getLocalPart());
    }
}

