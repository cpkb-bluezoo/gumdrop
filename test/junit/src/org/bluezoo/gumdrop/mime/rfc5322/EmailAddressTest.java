/*
 * EmailAddressTest.java
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

package org.bluezoo.gumdrop.mime.rfc5322;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link EmailAddress}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EmailAddressTest {

    @Test
    public void testSimpleAddress() {
        EmailAddress addr = new EmailAddress(null, "user", "example.com", (List<String>) null);
        
        assertNull(addr.getDisplayName());
        assertEquals("user", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
        assertEquals("user@example.com", addr.getAddress());
        assertNull(addr.getComments());
    }
    
    @Test
    public void testAddressWithDisplayName() {
        EmailAddress addr = new EmailAddress("John Doe", "john", "example.com", (List<String>) null);
        
        assertEquals("John Doe", addr.getDisplayName());
        assertEquals("john", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
        assertEquals("john@example.com", addr.getAddress());
    }
    
    @Test
    public void testAddressWithComments() {
        List<String> comments = Arrays.asList("Work", "Primary");
        EmailAddress addr = new EmailAddress("Jane Smith", "jane", "example.com", comments);
        
        assertEquals("Jane Smith", addr.getDisplayName());
        assertEquals("jane@example.com", addr.getAddress());
        assertNotNull(addr.getComments());
        assertEquals(2, addr.getComments().size());
        assertTrue(addr.getComments().contains("Work"));
        assertTrue(addr.getComments().contains("Primary"));
    }
    
    @Test
    public void testSimpleAddressFlag() {
        EmailAddress simple = new EmailAddress(null, "user", "example.com", true);
        assertTrue(simple.isSimpleAddress());
        
        EmailAddress notSimple = new EmailAddress(null, "user", "example.com", false);
        assertFalse(notSimple.isSimpleAddress());
    }
    
    @Test
    public void testGetLocalPartAndDomain() {
        EmailAddress addr = new EmailAddress(null, "john.doe", "mail.example.com", (List<String>) null);
        
        assertEquals("john.doe", addr.getLocalPart());
        assertEquals("mail.example.com", addr.getDomain());
    }
    
    @Test
    public void testToStringSimple() {
        EmailAddress addr = new EmailAddress(null, "user", "example.com", (List<String>) null);
        
        String str = addr.toString();
        assertTrue(str.contains("user@example.com"));
    }
    
    @Test
    public void testToStringWithDisplayName() {
        EmailAddress addr = new EmailAddress("John Doe", "john", "example.com", (List<String>) null);
        
        String str = addr.toString();
        assertTrue(str.contains("John Doe"));
        assertTrue(str.contains("john@example.com"));
    }
    
    @Test
    public void testToStringWithComments() {
        List<String> comments = Collections.singletonList("Work");
        EmailAddress addr = new EmailAddress("Jane", "jane", "example.com", comments);
        
        String str = addr.toString();
        assertTrue(str.contains("(Work)"));
    }
    
    @Test
    public void testEmptyDisplayName() {
        EmailAddress addr = new EmailAddress("", "user", "example.com", (List<String>) null);
        
        // Empty string is considered as having a display name
        assertEquals("", addr.getDisplayName());
    }
    
    @Test
    public void testCommentsUnmodifiable() {
        List<String> comments = Arrays.asList("Test");
        EmailAddress addr = new EmailAddress("User", "user", "example.com", comments);
        
        List<String> retrievedComments = addr.getComments();
        assertNotNull(retrievedComments);
        
        try {
            retrievedComments.add("New Comment");
            fail("Should not be able to modify comments list");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }
    
    @Test
    public void testAddressFormats() {
        // Various valid email formats
        EmailAddress simple = new EmailAddress(null, "simple", "example.com", (List<String>) null);
        assertEquals("simple", simple.getLocalPart());
        
        EmailAddress plusTag = new EmailAddress(null, "plus+tag", "example.com", (List<String>) null);
        assertEquals("plus+tag", plusTag.getLocalPart());
        
        EmailAddress dotted = new EmailAddress(null, "dots.in.local", "example.com", (List<String>) null);
        assertEquals("dots.in.local", dotted.getLocalPart());
        
        EmailAddress subdomain = new EmailAddress(null, "user", "subdomain.example.com", (List<String>) null);
        assertEquals("subdomain.example.com", subdomain.getDomain());
    }
    
    @Test
    public void testMultipleComments() {
        List<String> comments = Arrays.asList("Comment 1", "Comment 2", "Comment 3");
        EmailAddress addr = new EmailAddress("User", "user", "example.com", comments);
        
        String str = addr.toString();
        assertTrue(str.contains("(Comment 1)"));
        assertTrue(str.contains("(Comment 2)"));
        assertTrue(str.contains("(Comment 3)"));
    }
    
    @Test
    public void testEquals() {
        EmailAddress addr1 = new EmailAddress("John", "john", "example.com", (List<String>) null);
        EmailAddress addr2 = new EmailAddress("John", "john", "example.com", (List<String>) null);
        
        assertEquals(addr1, addr2);
    }
    
    @Test
    public void testEqualsCaseInsensitiveDomain() {
        EmailAddress addr1 = new EmailAddress("John", "john", "example.com", (List<String>) null);
        EmailAddress addr2 = new EmailAddress("John", "john", "EXAMPLE.COM", (List<String>) null);
        
        // Domain comparison should be case-insensitive
        assertEquals(addr1, addr2);
    }
    
    @Test
    public void testNotEqualsLocalPartCaseSensitive() {
        EmailAddress addr1 = new EmailAddress(null, "john", "example.com", (List<String>) null);
        EmailAddress addr2 = new EmailAddress(null, "John", "example.com", (List<String>) null);
        
        // Local-part comparison should be case-sensitive
        assertNotEquals(addr1, addr2);
    }
    
    @Test
    public void testNotEquals() {
        EmailAddress addr1 = new EmailAddress("John", "john", "example.com", (List<String>) null);
        EmailAddress addr2 = new EmailAddress("Jane", "jane", "example.com", (List<String>) null);
        
        assertNotEquals(addr1, addr2);
    }
    
    @Test
    public void testHashCode() {
        EmailAddress addr1 = new EmailAddress("John", "john", "example.com", (List<String>) null);
        EmailAddress addr2 = new EmailAddress("John", "john", "example.com", (List<String>) null);
        
        assertEquals(addr1.hashCode(), addr2.hashCode());
    }
    
    @Test(expected = NullPointerException.class)
    public void testNullLocalPartThrows() {
        new EmailAddress("Name", null, "example.com", (List<String>) null);
    }
    
    @Test(expected = NullPointerException.class)
    public void testNullDomainThrows() {
        new EmailAddress("Name", "user", null, (List<String>) null);
    }
}
