/*
 * NxDomainCutPolicy.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsQuestion;

/**
 * RFC 8020 NXDOMAIN cut policy for {@link UpstreamRelayHandler}.
 */
public interface NxDomainCutPolicy {

    /** Apply NXDOMAIN cut using negatively cached ancestor names. */
    NxDomainCutPolicy ENABLED = new NxDomainCutPolicy() {
        @Override
        public boolean shouldApplyNxDomainCut(DnsQuestion question) {
            return true;
        }
    };

    /** Only treat exact-name negative cache hits as NXDOMAIN. */
    NxDomainCutPolicy DISABLED = new NxDomainCutPolicy() {
        @Override
        public boolean shouldApplyNxDomainCut(DnsQuestion question) {
            return false;
        }
    };

    /**
     * @param question the query being answered from cache
     * @return {@code true} to synthesize NXDOMAIN for subdomains of a
     *         proven-negative ancestor name
     */
    boolean shouldApplyNxDomainCut(DnsQuestion question);
}
