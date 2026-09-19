/*
 * MinimalAnyPolicy.java
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
 * RFC 8482 minimal ANY response policy for {@link UpstreamRelayHandler}
 * and {@link AuthoritativeZoneHandler}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
