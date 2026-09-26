/*
 * ClusterGroupsTest.java
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

package org.bluezoo.gumdrop.servlet.session;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The multicast groups a {@link Cluster} joins: both defaults unless a
 * group is given, and no name lookups.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterGroupsTest {

    private static ClusterContainer container(final InetAddress group) {
        return new ClusterContainer() {
            @Override
            public int getClusterPort() {
                return 8080;
            }

            @Override
            public InetAddress getClusterGroupAddress() {
                return group;
            }

            @Override
            public byte[] getClusterKey() {
                return new byte[32];
            }

            @Override
            public SessionContext getContextByDigest(byte[] digest) {
                return null;
            }

            @Override
            public Iterable<SessionContext> getDistributableContexts() {
                return Collections.<SessionContext>emptyList();
            }
        };
    }

    @Test
    public void defaultsAreTheIpv4AndIpv6Groups() throws Exception {
        List<InetAddress> groups = new Cluster(container(null)).getGroupAddresses();
        assertEquals(2, groups.size());
        assertEquals("224.0.80.80", groups.get(0).getHostAddress());
        assertTrue(groups.get(1) instanceof Inet6Address);
        assertEquals("ff12:0:0:0:0:0:0:8080", groups.get(1).getHostAddress());
    }

    @Test
    public void defaultGroupsAreMulticastAndIpv6IsLinkScoped() {
        assertTrue(Cluster.DEFAULT_GROUP_IPV4.isMulticastAddress());
        assertTrue(Cluster.DEFAULT_GROUP_IPV6.isMulticastAddress());
        assertTrue(Cluster.DEFAULT_GROUP_IPV6.isMCLinkLocal());
    }

    @Test
    public void anExplicitGroupReplacesBothDefaults() throws Exception {
        InetAddress v6 = InetAddress.getByAddress(new byte[] {
            (byte) 0xff, 0x12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x12, 0x34 });
        List<InetAddress> groups = new Cluster(container(v6)).getGroupAddresses();
        assertEquals(Collections.singletonList(v6), groups);
    }
}
