/*
 * NxDomainCutPolicy.java
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
 * RFC 8020 NXDOMAIN cut policy for {@link UpstreamRelayHandler}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
