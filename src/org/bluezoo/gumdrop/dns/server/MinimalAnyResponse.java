/*
 * MinimalAnyResponse.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import java.util.Collections;
import java.util.List;

/**
 * RFC 8482 minimal-sized response for QTYPE=ANY.
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
