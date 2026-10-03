/*
 * StreamH2WebSocketUpgradeTest.java
 * Copyright (C) 2026 Chris Burdess
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Branch tests for {@link EmailAddress} and {@link GroupEmailAddress}: null
 * argument rejection, empty addresses, equality across other types, and
 * rendering with comments and members.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EmailAddressValueBranchesTest {

    @Test
    public void nullLocalPartOrDomainIsRejected() {
        List<String> none = null;
        try {
            new EmailAddress(null, null, "x.y", none);
            fail("null local part accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("localPart"));
        }
        try {
            new EmailAddress(null, "a", null, none);
            fail("null domain accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("domain"));
        }
        try {
            new EmailAddress(null, null, "x.y", true);
            fail("null local part accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("localPart"));
        }
        try {
            new EmailAddress(null, "a", null, true);
            fail("null domain accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("domain"));
        }
    }

    @Test
    public void emptyAddressRendersEmpty() {
        EmailAddress empty = new EmailAddress(null, "", "", true);
        assertEquals("", empty.getAddress());
        assertEquals("", empty.getEnvelopeAddress());
        assertEquals("<>", empty.toString());
        assertTrue(empty.isSimpleAddress());
        assertNull(empty.getComments());
    }

    @Test
    public void toStringIncludesDisplayNameAndComments() {
        List<String> comments = new ArrayList<String>();
        comments.add("work");
        comments.add("primary");
        EmailAddress a = new EmailAddress("Ann", "ann", "example.org", comments);
        assertEquals("Ann <ann@example.org> (work) (primary)", a.toString());
        assertEquals(Arrays.asList("work", "primary"), a.getComments());
        assertFalse(a.isSimpleAddress());
        EmailAddress blank = new EmailAddress("", "b", "example.org", true);
        assertEquals("<b@example.org>", blank.toString());
    }

    @Test
    public void equalsIsCaseInsensitiveOnDomainOnly() {
        EmailAddress a = new EmailAddress(null, "user", "Example.ORG", true);
        EmailAddress b = new EmailAddress("Other", "user", "example.org", true);
        EmailAddress c = new EmailAddress(null, "User", "example.org", true);
        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals("user@example.org"));
        assertFalse(a.equals(null));
    }

    @Test
    public void groupRendersMembersAndComments() {
        EmailAddress m1 = new EmailAddress(null, "a", "x.y", true);
        EmailAddress m2 = new EmailAddress("B", "b", "x.y", true);
        List<EmailAddress> members = new ArrayList<EmailAddress>();
        members.add(m1);
        members.add(m2);
        List<String> comments = new ArrayList<String>();
        comments.add("note");
        GroupEmailAddress g = new GroupEmailAddress("Team", members, comments);
        assertEquals("Team", g.getGroupName());
        assertEquals(2, g.getMembers().size());
        assertEquals("Team: <a@x.y>, B <b@x.y>; (note)", g.toString());
        try {
            g.getMembers().add(m1);
            fail("member list is modifiable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(2, g.getMembers().size());
        }
    }

    @Test
    public void groupWithoutMembersOrComments() {
        GroupEmailAddress g = new GroupEmailAddress("Undisclosed", null, null);
        assertTrue(g.getMembers().isEmpty());
        assertEquals("Undisclosed: ;", g.toString());
    }
}
