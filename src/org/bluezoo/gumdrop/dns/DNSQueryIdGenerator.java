/*
 * DNSQueryIdGenerator.java
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
public final class DNSQueryIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DNSQueryIdGenerator() {
    }

    /**
     * Allocates a random 16-bit ID not present in {@code inUse}.
     *
     * @param inUse IDs of queries currently awaiting a response
     * @return a value in {@code 1..65535}
     * @throws IllegalStateException if no ID is available
     */
    public static int allocate(Set<Integer> inUse) {
        for (int attempt = 0; attempt < 65536; attempt++) {
            int id = RANDOM.nextInt(0xFFFF) + 1;
            if (!inUse.contains(id)) {
                return id;
            }
        }
        throw new IllegalStateException("No available DNS query IDs");
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
