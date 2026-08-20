/*
 * HTTPMethodSafety.java
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

package org.bluezoo.gumdrop.http.client;

import java.util.Set;

/**
 * Classifies HTTP methods by whether they are safe to send as QUIC 0-RTT
 * early data (RFC 9001 section 4.6.1).
 *
 * <p>0-RTT data has no anti-replay guarantee at the application layer -- a
 * network attacker can capture and re-send a client's 0-RTT packets, and
 * the server has no way to distinguish a replay from a genuine retry. Only
 * methods that are both safe and idempotent per RFC 9110 sections 9.3.1,
 * 9.3.2, 9.3.7 and 9.3.8 are eligible: a replayed GET/HEAD/OPTIONS/TRACE
 * has no side effect beyond what the original request already had.
 * POST/PUT/PATCH/DELETE are never eligible, regardless of application-level
 * idempotency the server might separately guarantee.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HTTPMethodSafety {

    private static final Set<String> ZERO_RTT_ELIGIBLE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private HTTPMethodSafety() {
    }

    /**
     * Returns whether a request using the given method may be sent as
     * 0-RTT early data.
     *
     * @param method the HTTP method, e.g. "GET"
     * @return true if the method is safe and idempotent
     */
    public static boolean isEarlyDataEligible(String method) {
        return method != null && ZERO_RTT_ELIGIBLE.contains(method.toUpperCase());
    }

}
