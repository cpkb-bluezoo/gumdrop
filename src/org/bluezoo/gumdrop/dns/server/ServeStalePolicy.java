/*
 * ServeStalePolicy.java
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

import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsQuestion;

/**
 * RFC 8767 serve-stale policy for {@link UpstreamRelayHandler}. Replace or
 * disable the default when composing a custom forwarder.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ServeStalePolicy {

    /** Serve stale data when upstream fails and a stale cache entry exists. */
    ServeStalePolicy ENABLED = new ServeStalePolicy() {
        @Override
        public boolean shouldServeStale(DnsQuestion question,
                                        DnsCache.StaleHit staleHit) {
            return true;
        }
    };

    /** Never serve stale data (upstream failure yields SERVFAIL). */
    ServeStalePolicy DISABLED = new ServeStalePolicy() {
        @Override
        public boolean shouldServeStale(DnsQuestion question,
                                        DnsCache.StaleHit staleHit) {
            return false;
        }
    };

    /**
     * @param question the query that missed the live cache
     * @param staleHit the expired-but-retained cache entry
     * @return {@code true} to return the stale answer (with capped TTL)
     */
    boolean shouldServeStale(DnsQuestion question, DnsCache.StaleHit staleHit);
}
