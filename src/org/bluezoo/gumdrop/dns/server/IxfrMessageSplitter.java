/*
 * IxfrMessageSplitter.java
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
import org.bluezoo.gumdrop.dns.DnsType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds RFC 1995 incremental zone transfer responses on framed TCP.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class IxfrMessageSplitter {

    private static final int MAX_MESSAGE_BYTES = 32 * 1024;

    private IxfrMessageSplitter() {
    }

    static List<DnsMessage> split(DnsMessage query, MutableZone zone) {
        String origin = zone.getOrigin();
        Integer clientSerial = clientSoaSerial(query, origin);
        if (clientSerial == null) {
            return Collections.singletonList(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_FORMERR));
        }
        int currentSerial = zone.getSerial();
        if (clientSerial.intValue() == currentSerial) {
            return upToDate(query, zone);
        }
        if (clientSerial.intValue() > currentSerial) {
            return Collections.singletonList(query.createAuthoritativeErrorResponse(
                    DnsMessage.RCODE_REFUSED));
        }
        ZoneJournal journal = zone.getJournal();
        if (!journal.coversRange(clientSerial.intValue(), currentSerial)) {
            return AxfrMessageSplitter.split(query, zone);
        }
        List<DnsResourceRecord> delta = buildDeltaWire(journal,
                clientSerial.intValue(), currentSerial);
        if (delta.isEmpty()) {
            return upToDate(query, zone);
        }
        return packMessages(query, zone, delta);
    }

    private static List<DnsMessage> upToDate(DnsMessage query, MutableZone zone) {
        DnsResourceRecord soa = zone.getSoaRecord();
        return Collections.singletonList(
                AuthoritativeZoneHandler.createAuthoritativeResponse(query, zone,
                        Collections.singletonList(soa),
                        Collections.singletonList(soa),
                        Collections.<DnsResourceRecord>emptyList(),
                        DnsMessage.RCODE_NOERROR));
    }

    private static Integer clientSoaSerial(DnsMessage query, String origin) {
        List<DnsResourceRecord> authorities = query.getAuthorities();
        for (int i = 0; i < authorities.size(); i++) {
            DnsResourceRecord rr = authorities.get(i);
            if (rr.getType() == DnsType.SOA
                    && ZoneFile.normalizeName(rr.getName()).equals(origin)) {
                return Integer.valueOf(rr.getSoaSerial());
            }
        }
        return null;
    }

    private static List<DnsResourceRecord> buildDeltaWire(ZoneJournal journal,
                                                          int clientSerial,
                                                          int currentSerial) {
        List<ZoneJournal.Entry> steps = journal.entriesAfter(clientSerial, currentSerial);
        List<DnsResourceRecord> wire = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < steps.size(); i++) {
            ZoneJournal.Entry entry = steps.get(i);
            wire.addAll(entry.deletions);
            wire.addAll(entry.additions);
        }
        return wire;
    }

    private static List<DnsMessage> packMessages(DnsMessage query, MutableZone zone,
                                               List<DnsResourceRecord> delta) {
        DnsResourceRecord soa = zone.getSoaRecord();
        List<DnsMessage> messages = new ArrayList<DnsMessage>();
        List<DnsResourceRecord> chunk = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < delta.size(); i++) {
            chunk.add(delta.get(i));
            if (estimateIxfrSize(query, zone, chunk) > MAX_MESSAGE_BYTES
                    && chunk.size() > 1) {
                DnsResourceRecord last = chunk.remove(chunk.size() - 1);
                messages.add(ixfrMessage(query, zone, chunk));
                chunk.clear();
                chunk.add(last);
            }
        }
        if (!chunk.isEmpty()) {
            chunk.add(soa);
            messages.add(ixfrMessage(query, zone, chunk));
        } else if (messages.isEmpty()) {
            messages.add(ixfrMessage(query, zone,
                    Collections.singletonList(soa)));
        } else {
            DnsMessage lastMsg = messages.get(messages.size() - 1);
            List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>(
                    lastMsg.getAnswers());
            if (answers.isEmpty()
                    || answers.get(answers.size() - 1).getType() != DnsType.SOA) {
                answers.add(soa);
                messages.set(messages.size() - 1,
                        ixfrMessage(query, zone, answers));
            }
        }
        return messages;
    }

    private static DnsMessage ixfrMessage(DnsMessage query, MutableZone zone,
                                          List<DnsResourceRecord> answers) {
        return AuthoritativeZoneHandler.createAuthoritativeResponse(query, zone,
                new ArrayList<DnsResourceRecord>(answers),
                Collections.singletonList(zone.getSoaRecord()),
                Collections.<DnsResourceRecord>emptyList(),
                DnsMessage.RCODE_NOERROR);
    }

    private static int estimateIxfrSize(DnsMessage query, MutableZone zone,
                                        List<DnsResourceRecord> answers) {
        return ixfrMessage(query, zone, answers).serialize().remaining();
    }
}
