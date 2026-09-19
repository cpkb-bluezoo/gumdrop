/*
 * MinimalAnyResponse.java
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

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import java.util.Collections;
import java.util.List;

/**
 * RFC 8482 minimal-sized response for QTYPE=ANY.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MinimalAnyResponse {

    /** RFC 8482 section 4: TTL lower than normal zone data. */
    public static final int DEFAULT_TTL = 3600;

    private MinimalAnyResponse() {
    }

    /**
     * @param qname the query name
     * @return a single HINFO RR with zero-length CPU and OS strings
     */
    public static List<DnsResourceRecord> records(String qname) {
        return Collections.singletonList(
                DnsResourceRecord.hinfo(qname, DEFAULT_TTL, "", ""));
    }

    /**
     * @param query the client query (QTYPE ANY)
     * @return a minimal NOERROR response
     */
    public static DnsMessage createResponse(DnsMessage query) {
        String qname = query.getQuestions().get(0).getName();
        return query.createResponse(records(qname));
    }
}
