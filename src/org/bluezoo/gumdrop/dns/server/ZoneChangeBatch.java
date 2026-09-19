/*
 * ZoneChangeBatch.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records RR additions and deletions for one RFC 2136 update (one SOA serial step).
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
