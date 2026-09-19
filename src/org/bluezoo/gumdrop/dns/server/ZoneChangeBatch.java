/*
 * ZoneChangeBatch.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records RR additions and deletions for one RFC 2136 update (one SOA serial step).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneChangeBatch {

    private final List<DnsResourceRecord> deletions = new ArrayList<DnsResourceRecord>();
    private final List<DnsResourceRecord> additions = new ArrayList<DnsResourceRecord>();

    void addDeletion(DnsResourceRecord rr) {
        if (rr != null) {
            deletions.add(withTtl(rr, 0));
        }
    }

    void addAddition(DnsResourceRecord rr) {
        if (rr != null) {
            additions.add(rr);
        }
    }

    boolean isEmpty() {
        return deletions.isEmpty() && additions.isEmpty();
    }

    List<DnsResourceRecord> deletions() {
        return Collections.unmodifiableList(deletions);
    }

    List<DnsResourceRecord> additions() {
        return Collections.unmodifiableList(additions);
    }

    /** RFC 1995: deleted RRs are sent with TTL 0. */
    static DnsResourceRecord withTtl(DnsResourceRecord rr, int ttl) {
        return new DnsResourceRecord(rr.getName(), rr.getType(), rr.getRawType(),
                rr.getDNSClass(), rr.getRawClass(), ttl, rr.getRData());
    }
}
