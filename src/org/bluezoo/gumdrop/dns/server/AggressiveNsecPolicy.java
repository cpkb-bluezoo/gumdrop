/*
 * AggressiveNsecPolicy.java
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

import org.bluezoo.gumdrop.dns.DnsQuestion;

/**
 * RFC 8198 aggressive use of DNSSEC-validated cache for
 * {@link UpstreamRelayHandler}. Replace or disable when every query must
 * reach upstream (for example full-query logging).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface AggressiveNsecPolicy {

    /** Synthesize NXDOMAIN/NODATA from validated NSEC/NSEC3 proofs when possible. */
    AggressiveNsecPolicy ENABLED = new AggressiveNsecPolicy() {
        @Override
        public boolean shouldSynthesizeFromNsecCache(DnsQuestion question) {
            return true;
        }
    };

    /** Always forward; do not synthesize from the NSEC proof cache. */
    AggressiveNsecPolicy DISABLED = new AggressiveNsecPolicy() {
        @Override
        public boolean shouldSynthesizeFromNsecCache(DnsQuestion question) {
            return false;
        }
    };

    /**
     * @param question the query that missed the live answer cache
     * @return {@code true} to answer from cached validated proofs without upstream
     */
    boolean shouldSynthesizeFromNsecCache(DnsQuestion question);
}
