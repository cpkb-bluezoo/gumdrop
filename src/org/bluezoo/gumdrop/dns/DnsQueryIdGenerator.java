/*
 * DnsQueryIdGenerator.java
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

package org.bluezoo.gumdrop.dns;

import java.security.SecureRandom;
import java.util.Set;

/**
 * Cryptographically random DNS message ID allocation.
 *
 * <p>RFC 1035 section 4.1.1: the 16-bit ID field matches queries to
 * responses. Predictable IDs enable off-path cache poisoning (Kaminsky
 * class attacks).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsQueryIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DnsQueryIdGenerator() {
    }

    /**
     * Allocates a random 16-bit ID not present in {@code inUse}.
     *
     * @param inUse IDs of queries currently awaiting a response
     * @return a value in {@code 1..65535}
     * @throws IllegalStateException if no ID is available
     */
    public static int allocate(Set<Integer> inUse) {
        int id = tryAllocate(inUse);
        if (id < 0) {
            throw new IllegalStateException("No available DNS query IDs");
        }
        return id;
    }

    /**
     * Allocates a random query ID in 1..65535 that is not in {@code inUse},
     * or reports that there is none. A few random probes find a free ID
     * quickly while few are in use; when most are, the IDs are scanned from
     * a random start, so the cost stays bounded however full the set is.
     *
     * @param inUse the IDs already in use
     * @return a free ID, or -1 if all 65535 are in use
     */
    public static int tryAllocate(Set<Integer> inUse) {
        if (inUse.size() >= 0xFFFF) {
            return -1;
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            int id = RANDOM.nextInt(0xFFFF) + 1;
            if (!inUse.contains(Integer.valueOf(id))) {
                return id;
            }
        }
        int start = RANDOM.nextInt(0xFFFF);
        for (int i = 0; i < 0xFFFF; i++) {
            int id = (start + i) % 0xFFFF + 1;
            if (!inUse.contains(Integer.valueOf(id))) {
                return id;
            }
        }
        return -1;
    }

    /**
     * Allocates a random ID for synthetic local responses (not sent on the wire).
     *
     * @return a value in {@code 1..65535}
     */
    public static int allocateSynthetic() {
        return RANDOM.nextInt(0xFFFF) + 1;
    }
}
