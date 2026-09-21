/*
 * DnsZoneOperations.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared helpers for zone transfer message construction and answer merging.
 * Network I/O is performed asynchronously by {@link DnsZoneClient}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DnsZoneOperations {

    private DnsZoneOperations() {
    }

    public static DnsMessage createAxfrQuery(int id, String zoneName) {
        DnsQuestion q = new DnsQuestion(zoneName, DnsType.AXFR, DnsClass.IN);
        return new DnsMessage(id, DnsMessage.FLAG_RD,
                Collections.singletonList(q),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
    }

    public static DnsMessage createIxfrQuery(int id, String zoneName,
            DnsResourceRecord clientSoa) {
        DnsQuestion q = new DnsQuestion(zoneName, DnsType.IXFR, DnsClass.IN);
        return new DnsMessage(id, DnsMessage.FLAG_RD,
                Collections.singletonList(q),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(clientSoa),
                Collections.<DnsResourceRecord>emptyList());
    }

    public static List<DnsResourceRecord> mergeTransferAnswers(
            List<DnsMessage> messages) throws IOException {
        List<DnsResourceRecord> records = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < messages.size(); i++) {
            DnsMessage msg = messages.get(i);
            if (msg.getRcode() != DnsMessage.RCODE_NOERROR) {
                throw new IOException("zone transfer failed: rcode=" + msg.getRcode());
            }
            records.addAll(msg.getAnswers());
        }
        if (records.isEmpty()) {
            throw new IOException("zone transfer returned no records");
        }
        return records;
    }

}
