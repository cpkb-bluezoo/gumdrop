/*
 * DnsNsecSynthesisHandler.java
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
 * Callback for RFC 8198-style answers synthesized from cached validated
 * NSEC/NSEC3 proofs. The handler builds the response (or stores proof
 * records) from events; the cache does not return a materialized message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DnsNsecProofCache
 */
public interface DnsNsecSynthesisHandler {

    /**
     * No cached proof covers the question.
     */
    void synthesisMiss();

    /**
     * A cached proof applies; {@link #proofRecord} events follow, then
     * {@link #synthesisComplete()}.
     *
     * @param question the original question
     * @param rcode {@link DnsMessage#RCODE_NXDOMAIN} or
     *              {@link DnsMessage#RCODE_NOERROR} for NODATA
     */
    void synthesisStart(DnsQuestion question, int rcode);

    /**
     * One authority-section record to include in the synthesized response
     * (TTL already adjusted for cache age).
     *
     * @param record proof or supporting record
     */
    void proofRecord(DnsResourceRecord record);

    /**
     * End of synthesis for this lookup.
     */
    void synthesisComplete();
}
