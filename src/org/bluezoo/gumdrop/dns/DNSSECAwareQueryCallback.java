/*
 * DNSSECAwareQueryCallback.java
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

/**
 * A {@link DNSQueryCallback} that also receives the DNSSEC validation
 * status of the response, for callers that need to know whether an
 * answer was cryptographically authenticated rather than just
 * delivered.
 *
 * <p>RFC 7672 section 3.1.3 (DANE): a TLSA lookup must not be trusted
 * unless it came back {@link DNSSECStatus#SECURE} -- an insecure or
 * indeterminate answer must be treated as if no TLSA records existed.
 * This callback is what lets a caller enforce that rule, since a plain
 * {@link DNSQueryCallback} only ever sees the response message, not
 * whether {@link org.bluezoo.gumdrop.dns.client.DNSResolver} was able
 * to validate it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.client.DNSResolver
 */
public interface DNSSECAwareQueryCallback extends DNSQueryCallback {

    /**
     * Called when a DNS query completes successfully.
     *
     * @param response the DNS response message
     * @param status the DNSSEC validation status of the response, or
     *               {@link DNSSECStatus#INDETERMINATE} if DNSSEC
     *               validation was not performed for this query
     */
    void onResponse(DNSMessage response, DNSSECStatus status);

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #onResponse(DNSMessage, DNSSECStatus)}
     * with {@link DNSSECStatus#INDETERMINATE}, for code paths that
     * only have a plain {@link DNSQueryCallback} reference.
     */
    @Override
    default void onResponse(DNSMessage response) {
        onResponse(response, DNSSECStatus.INDETERMINATE);
    }

}
