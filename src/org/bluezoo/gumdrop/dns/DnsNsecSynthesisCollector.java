/*
 * DnsNsecSynthesisCollector.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects RFC 8198 synthesis events from {@link DnsNsecProofCache#lookup}
 * and builds a wire response at the protocol boundary (after the event stream
 * completes). The proof cache itself does not return a materialized message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsNsecSynthesisCollector implements DnsNsecSynthesisHandler {

    private boolean miss;
    private int rcode;
    private final List<DnsResourceRecord> authority =
            new ArrayList<DnsResourceRecord>();

    /**
     * @return {@code true} if {@link DnsNsecProofCache#lookup} reported a miss
     */
    public boolean isMiss() {
        return miss;
    }

    /**
     * Builds a response for {@code query} when synthesis succeeded.
     *
     * @param query the client query
     * @return the synthesized response with AD set
     * @throws IllegalStateException if {@link #isMiss()} is true
     */
    public DnsMessage toResponse(DnsMessage query) {
        if (miss) {
            throw new IllegalStateException("No synthesis to deliver");
        }
        int responseFlags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RA
                | DnsMessage.FLAG_AD | (query.getFlags() & DnsMessage.FLAG_RD);
        if (rcode != DnsMessage.RCODE_NOERROR) {
            responseFlags = responseFlags | (rcode & 0x0F);
        }
        List<DnsResourceRecord> emptyAnswers =
                Collections.emptyList();
        List<DnsResourceRecord> authCopy =
                new ArrayList<DnsResourceRecord>(authority);
        List<DnsResourceRecord> emptyAdditionals =
                Collections.emptyList();
        return new DnsMessage(
                query.getId(),
                responseFlags,
                query.getQuestions(),
                emptyAnswers,
                authCopy,
                emptyAdditionals);
    }

    @Override
    public void synthesisMiss() {
        miss = true;
    }

    @Override
    public void synthesisStart(DnsQuestion question, int rcode) {
        this.rcode = rcode;
    }

    @Override
    public void proofRecord(DnsResourceRecord record) {
        authority.add(record);
    }

    @Override
    public void synthesisComplete() {
    }
}
