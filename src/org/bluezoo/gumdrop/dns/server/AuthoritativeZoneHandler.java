/*
 * AuthoritativeZoneHandler.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authoritative DNS handler backed by a {@link ZoneFile}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AuthoritativeZoneHandler implements DnsQueryHandler {

    private final ZoneFile zone;
    private MinimalAnyPolicy minimalAnyPolicy = MinimalAnyPolicy.ENABLED;

    public AuthoritativeZoneHandler(ZoneFile zone) {
        if (zone == null) {
            throw new NullPointerException("zone");
        }
        this.zone = zone;
    }

    /**
     * RFC 8482 policy for QTYPE=ANY (default: minimal HINFO answer).
     */
    public void setMinimalAnyPolicy(MinimalAnyPolicy minimalAnyPolicy) {
        this.minimalAnyPolicy = minimalAnyPolicy != null
                ? minimalAnyPolicy : MinimalAnyPolicy.DISABLED;
    }

    /**
     * Loads a zone file from disk.
     */
    public static AuthoritativeZoneHandler load(Path zoneFile) throws java.io.IOException {
        return new AuthoritativeZoneHandler(ZoneFile.load(zoneFile));
    }

    public ZoneFile getZone() {
        return zone;
    }

    @Override
    public void handleQuery(DnsMessage query, SelectorLoop loop,
                            DnsQueryCallback callback) {
        DnsQuestion question = query.getQuestions().get(0);
        String qname = question.getName();
        DnsType qtype = question.getType();

        if (!zone.isWithinZone(qname)) {
            callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_REFUSED));
            return;
        }

        if (qtype == DnsType.ANY
                && minimalAnyPolicy.shouldReturnMinimalAny(question)) {
            callback.onResponse(createAuthoritativeResponse(query,
                    MinimalAnyResponse.records(qname),
                    zone.getNsRecords(),
                    Collections.<DnsResourceRecord>emptyList()));
            return;
        }

        if (qtype == DnsType.SOA) {
            List<DnsResourceRecord> soa = zone.lookup(zone.getOrigin(), DnsType.SOA);
            if (soa.isEmpty()) {
                callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_SERVFAIL));
                return;
            }
            callback.onResponse(createAuthoritativeResponse(query, soa,
                    zone.getNsRecords(), Collections.<DnsResourceRecord>emptyList()));
            return;
        }

        List<DnsResourceRecord> answers = zone.lookup(qname, qtype);
        if (answers.isEmpty()) {
            callback.onResponse(createAuthoritativeResponse(query,
                    Collections.<DnsResourceRecord>emptyList(),
                    zone.getNsRecords(),
                    Collections.<DnsResourceRecord>emptyList(),
                    DnsMessage.RCODE_NXDOMAIN));
            return;
        }

        List<DnsResourceRecord> authorities = Collections.emptyList();
        List<DnsResourceRecord> additionals = Collections.emptyList();
        if (qtype == DnsType.NS || !qname.equals(zone.getOrigin())) {
            authorities = zone.getNsRecords();
        }
        callback.onResponse(createAuthoritativeResponse(query, answers,
                authorities, additionals));
    }

    private static DnsMessage createAuthoritativeResponse(DnsMessage query,
            List<DnsResourceRecord> answers,
            List<DnsResourceRecord> authorities,
            List<DnsResourceRecord> additionals) {
        return createAuthoritativeResponse(query, answers, authorities,
                additionals, DnsMessage.RCODE_NOERROR);
    }

    private static DnsMessage createAuthoritativeResponse(DnsMessage query,
            List<DnsResourceRecord> answers,
            List<DnsResourceRecord> authorities,
            List<DnsResourceRecord> additionals,
            int rcode) {
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_AA | DnsMessage.FLAG_RA
                | (query.getFlags() & DnsMessage.FLAG_RD);
        if (rcode != DnsMessage.RCODE_NOERROR) {
            flags |= (rcode & 0x0F);
        }
        List<DnsResourceRecord> answerList = new ArrayList<DnsResourceRecord>(answers);
        return new DnsMessage(query.getId(), flags, query.getQuestions(),
                answerList, authorities, additionals);
    }

}
