/*
 * AxfrMessageSplitter.java
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits a zone into RFC 5936-style AXFR response messages for framed TCP.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class AxfrMessageSplitter {

    private static final int MAX_MESSAGE_BYTES = 32 * 1024;

    private AxfrMessageSplitter() {
    }

    static List<DnsMessage> split(DnsMessage query, MutableZone zone) {
        List<DnsResourceRecord> stream = orderedAxfrRecords(zone);
        if (stream.isEmpty()) {
            return Collections.singletonList(
                    AuthoritativeZoneHandler.createAuthoritativeResponse(query,
                            zone,
                            Collections.<DnsResourceRecord>emptyList(),
                            Collections.<DnsResourceRecord>emptyList(),
                            Collections.<DnsResourceRecord>emptyList(),
                            DnsMessage.RCODE_REFUSED));
        }
        List<DnsMessage> messages = new ArrayList<DnsMessage>();
        List<DnsResourceRecord> chunk = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < stream.size(); i++) {
            chunk.add(stream.get(i));
            if (estimateSize(query, chunk) > MAX_MESSAGE_BYTES && chunk.size() > 1) {
                DnsResourceRecord last = chunk.remove(chunk.size() - 1);
                messages.add(buildMessage(query, zone, chunk));
                chunk.clear();
                chunk.add(last);
            }
        }
        if (!chunk.isEmpty()) {
            messages.add(buildMessage(query, zone, chunk));
        }
        if (messages.size() > 1) {
            DnsResourceRecord soa = zone.getSoaRecord();
            DnsMessage last = messages.get(messages.size() - 1);
            List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>(
                    last.getAnswers());
            if (answers.isEmpty()
                    || answers.get(answers.size() - 1).getType()
                    != org.bluezoo.gumdrop.dns.DnsType.SOA) {
                answers.add(soa);
                messages.set(messages.size() - 1,
                        AuthoritativeZoneHandler.createAuthoritativeResponse(
                                query, zone, answers,
                                Collections.<DnsResourceRecord>emptyList(),
                                Collections.<DnsResourceRecord>emptyList(),
                                DnsMessage.RCODE_NOERROR));
            }
        }
        return messages;
    }

    private static List<DnsResourceRecord> orderedAxfrRecords(MutableZone zone) {
        List<DnsResourceRecord> all = zone.allRecords();
        DnsResourceRecord soa = zone.getSoaRecord();
        List<DnsResourceRecord> ordered = new ArrayList<DnsResourceRecord>();
        ordered.add(soa);
        for (int i = 0; i < all.size(); i++) {
            DnsResourceRecord rr = all.get(i);
            if (rr.getType() != org.bluezoo.gumdrop.dns.DnsType.SOA) {
                ordered.add(rr);
            }
        }
        ordered.add(soa);
        return ordered;
    }

    private static DnsMessage buildMessage(DnsMessage query, MutableZone zone,
                                           List<DnsResourceRecord> answers) {
        return AuthoritativeZoneHandler.createAuthoritativeResponse(query, zone,
                new ArrayList<DnsResourceRecord>(answers),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                DnsMessage.RCODE_NOERROR);
    }

    private static int estimateSize(DnsMessage query, List<DnsResourceRecord> answers) {
        DnsMessage msg = buildMessage(query,
                null, answers);
        ByteBuffer wire = msg.serialize();
        return wire.remaining();
    }
}
