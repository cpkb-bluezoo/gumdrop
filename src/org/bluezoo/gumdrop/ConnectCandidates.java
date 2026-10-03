/*
 * ConnectCandidates.java
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

package org.bluezoo.gumdrop;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The ordered list of addresses a client tries when connecting to a
 * resolved host name.
 *
 * <p>RFC 8305 (Happy Eyeballs v2) section 4: the first address determines
 * the preferred family, and the remaining addresses are then interleaved so
 * that families alternate. A host that publishes both IPv6 and IPv4
 * addresses where one family is unreachable is therefore never stuck behind
 * a run of addresses of the broken family.
 *
 * <p>Instances are not thread-safe; a client confines one to its
 * SelectorLoop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ConnectCandidates {

    private final List<InetAddress> ordered;
    private int next;

    /**
     * Orders the given addresses for connection attempts.
     *
     * @param addresses the resolved addresses, in resolver preference order
     */
    ConnectCandidates(List<InetAddress> addresses) {
        List<InetAddress> preferred = new ArrayList<InetAddress>();
        List<InetAddress> other = new ArrayList<InetAddress>();
        boolean preferV6 = !addresses.isEmpty()
                && addresses.get(0) instanceof Inet6Address;
        for (InetAddress a : addresses) {
            if ((a instanceof Inet6Address) == preferV6) {
                preferred.add(a);
            } else {
                other.add(a);
            }
        }
        ordered = new ArrayList<InetAddress>(addresses.size());
        int i = 0;
        int j = 0;
        while (i < preferred.size() || j < other.size()) {
            if (i < preferred.size()) {
                ordered.add(preferred.get(i++));
            }
            if (j < other.size()) {
                ordered.add(other.get(j++));
            }
        }
    }

    /**
     * Returns whether another address remains to be tried.
     *
     * @return true if {@link #next()} will return an address
     */
    boolean hasNext() {
        return next < ordered.size();
    }

    /**
     * Returns the next address to try.
     *
     * @return the address, or null when all have been returned
     */
    InetAddress next() {
        if (next >= ordered.size()) {
            return null;
        }
        return ordered.get(next++);
    }
}
