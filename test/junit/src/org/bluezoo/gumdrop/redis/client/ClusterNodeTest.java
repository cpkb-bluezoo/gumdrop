/*
 * ClusterNodeTest.java
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

package org.bluezoo.gumdrop.redis.client;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link ClusterNode} identity and address formatting.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterNodeTest {

    @Test
    public void nodesAreEqualWhenTheirAddressesAre() {
        ClusterNode a = new ClusterNode("10.0.0.1", 7000, "id1");
        ClusterNode b = new ClusterNode("10.0.0.1", 7000, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void differentHostOrPortIsADifferentNode() {
        ClusterNode a = new ClusterNode("10.0.0.1", 7000, "id1");
        assertNotEquals(a, new ClusterNode("10.0.0.2", 7000, "id1"));
        assertNotEquals(a, new ClusterNode("10.0.0.1", 7001, "id1"));
        assertNotEquals(a, "10.0.0.1:7000");
    }

    @Test
    public void nodesWorkAsSetMembers() {
        Set<ClusterNode> set = new HashSet<ClusterNode>();
        set.add(new ClusterNode("h", 1, "x"));
        set.add(new ClusterNode("h", 1, "y"));
        assertEquals(1, set.size());
    }

    @Test
    public void addressBracketsAnIpv6HostOnly() {
        assertEquals("h.example:7000", new ClusterNode("h.example", 7000, null).getAddress());
        assertEquals("[::1]:7000", new ClusterNode("::1", 7000, null).getAddress());
        assertEquals("[::1]:7000", new ClusterNode("::1", 7000, null).toString());
    }
}
