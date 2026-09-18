/*
 * MinimalAnyPolicy.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsQuestion;

/**
 * RFC 8482 minimal ANY response policy for {@link UpstreamRelayHandler}.
 */
public interface MinimalAnyPolicy {

    /** Answer ANY queries with a single minimal HINFO record. */
    MinimalAnyPolicy ENABLED = new MinimalAnyPolicy() {
        @Override
        public boolean shouldReturnMinimalAny(DnsQuestion question) {
            return true;
        }
    };

    /** Forward ANY queries normally. */
    MinimalAnyPolicy DISABLED = new MinimalAnyPolicy() {
        @Override
        public boolean shouldReturnMinimalAny(DnsQuestion question) {
            return false;
        }
    };

    /**
     * @param question the query (QTYPE is ANY when this is consulted)
     * @return {@code true} to return RFC 8482 minimal answer locally
     */
    boolean shouldReturnMinimalAny(DnsQuestion question);
}
