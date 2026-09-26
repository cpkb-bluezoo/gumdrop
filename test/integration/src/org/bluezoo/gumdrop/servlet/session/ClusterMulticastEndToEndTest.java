/*
 * ClusterMulticastEndToEndTest.java
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
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A cluster joins the IPv4 group and, when an interface has an IPv6
 * address, the IPv6 group as well, each on a socket of its own family.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterMulticastEndToEndTest {

    private static boolean hasInterface(boolean ipv6) throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || !ni.supportsMulticast() || ni.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                if ((addresses.nextElement() instanceof Inet6Address) == ipv6) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ClusterContainer container(final InetAddress group, final int port) {
        return new ClusterContainer() {
            @Override
            public int getClusterPort() {
                return port;
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
    public void defaultsJoinBothFamiliesWhereAvailable() throws Exception {
        Assume.assumeTrue(hasInterface(false));
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        Cluster cluster = new Cluster(container(null, 40481));
        try {
            cluster.open(gumdrop);
            List<InetAddress> joined = cluster.getJoinedGroupAddresses();
            assertTrue(joined.contains(Cluster.DEFAULT_GROUP_IPV4));
            assertEquals(hasInterface(true), joined.contains(Cluster.DEFAULT_GROUP_IPV6));
        } finally {
            cluster.close();
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void anIpv6GroupBindsAnIpv6Socket() throws Exception {
        Assume.assumeTrue(hasInterface(true));
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        Cluster cluster = new Cluster(container(Cluster.DEFAULT_GROUP_IPV6, 40482));
        try {
            cluster.open(gumdrop);
            assertEquals(Collections.singletonList(Cluster.DEFAULT_GROUP_IPV6), cluster.getJoinedGroupAddresses());
        } finally {
            cluster.close();
            gumdrop.shutdown();
            gumdrop.join();
        }
    }
}
