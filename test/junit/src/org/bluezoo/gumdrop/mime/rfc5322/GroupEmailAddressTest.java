/*
 * GroupEmailAddressTest.java
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
 * Unit tests for {@link GroupEmailAddress}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GroupEmailAddressTest {

    @Test
    public void testGroupWithMembers() {
        EmailAddress member1 = new EmailAddress("John", "john", "example.com", (List<String>) null);
        EmailAddress member2 = new EmailAddress("Jane", "jane", "example.com", (List<String>) null);
        List<EmailAddress> members = Arrays.asList(member1, member2);
        
        GroupEmailAddress group = new GroupEmailAddress("Team", members, null);
        
        assertEquals("Team", group.getGroupName());
        assertEquals(2, group.getMembers().size());
    }
    
    @Test
    public void testEmptyGroup() {
        GroupEmailAddress group = new GroupEmailAddress("Empty Group", Collections.emptyList(), null);
        
        assertEquals("Empty Group", group.getGroupName());
        assertTrue(group.getMembers().isEmpty());
    }
    
    @Test
    public void testGroupWithComments() {
        List<String> comments = Collections.singletonList("Marketing");
        EmailAddress member = new EmailAddress("Alice", "alice", "example.com", (List<String>) null);
        
        GroupEmailAddress group = new GroupEmailAddress("Marketing Team", 
            Collections.singletonList(member), comments);
        
        assertEquals("Marketing Team", group.getGroupName());
        assertNotNull(group.getComments());
        assertEquals(1, group.getComments().size());
    }
    
    @Test
    public void testMembersUnmodifiable() {
        EmailAddress member = new EmailAddress("Bob", "bob", "example.com", (List<String>) null);
        GroupEmailAddress group = new GroupEmailAddress("Group", 
            Collections.singletonList(member), null);
        
        List<EmailAddress> members = group.getMembers();
        
        try {
            members.add(new EmailAddress("Eve", "eve", "example.com", (List<String>) null));
            fail("Should not be able to modify members list");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }
    
    @Test
    public void testToString() {
        EmailAddress member = new EmailAddress("John", "john", "example.com", (List<String>) null);
        GroupEmailAddress group = new GroupEmailAddress("Team", 
            Collections.singletonList(member), null);
        
        String str = group.toString();
        assertTrue(str.contains("Team"));
    }
    
    @Test
    public void testGroupInheritsFromEmailAddress() {
        GroupEmailAddress group = new GroupEmailAddress("Support", Collections.emptyList(), null);
        
        // GroupEmailAddress extends EmailAddress
        assertTrue(group instanceof EmailAddress);
    }
    
    @Test
    public void testGroupHasEmptyAddress() {
        GroupEmailAddress group = new GroupEmailAddress("Team", Collections.emptyList(), null);
        
        // Groups don't have a real address
        assertEquals("", group.getLocalPart());
        assertEquals("", group.getDomain());
        assertEquals("", group.getAddress());
    }
}
