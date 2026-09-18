/*
 * ServeStalePolicy.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsQuestion;

/**
 * RFC 8767 serve-stale policy for {@link UpstreamRelayHandler}. Replace or
 * disable the default when composing a custom forwarder.
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
