/*
 * ClusterNode.java
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

/**
 * A node of a Redis Cluster, identified by its address.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ClusterNode {

    private final String host;
    private final int port;
    private final String id;

    ClusterNode(String host, int port, String id) {
        this.host = host;
        this.port = port;
        this.id = id;
    }

    String getHost() {
        return host;
    }

    int getPort() {
        return port;
    }

    /** The node ID the cluster reports, or null if not known. */
    String getId() {
        return id;
    }

    /** {@code host:port}, bracketing an IPv6 host. */
    String getAddress() {
        return address(host, port);
    }

    static String address(String host, int port) {
        if (host.indexOf(':') >= 0) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    // Identity is the address: the same node reported with and without an
    // ID, or after a failover, is still the same endpoint to connect to.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClusterNode)) {
            return false;
        }
        ClusterNode other = (ClusterNode) o;
        return port == other.port && host.equals(other.host);
    }

    @Override
    public int hashCode() {
        return 31 * host.hashCode() + port;
    }

    @Override
    public String toString() {
        return getAddress();
    }
}
