/*
 * IxfrMessageSplitterTest.java
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

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IxfrMessageSplitterTest {

    @Test
    public void testUpToDateIxfr() throws Exception {
        MutableZone zone = loadSampleZone();
        DnsMessage query = ixfrQuery(zone, zone.getSerial());
        List<DnsMessage> messages = IxfrMessageSplitter.split(query, zone);
        assertEquals(1, messages.size());
        assertEquals(DnsType.SOA, messages.get(0).getAnswers().get(0).getType());
        assertEquals(DnsType.SOA, messages.get(0).getAuthorities().get(0).getType());
    }

    @Test
    public void testIncrementalDeltaAfterUpdate() throws Exception {
        MutableZone zone = loadSampleZone();
        int clientSerial = zone.getSerial();
        applyAddA(zone, "delta.example.com.", "192.0.2.44");
        assertEquals(clientSerial + 1, zone.getSerial());

        DnsResourceRecord clientSoa = DnsResourceRecord.soa(zone.getOrigin(), 300,
                zone.getSoaData().mname, zone.getSoaData().rname,
                clientSerial, zone.getSoaData().refresh, zone.getSoaData().retry,
                zone.getSoaData().expire, zone.getSoaData().minimum);
        DnsMessage query = ixfrQuery(zone, clientSoa);

        List<DnsMessage> messages = IxfrMessageSplitter.split(query, zone);
        assertFalse(messages.isEmpty());
        int answerCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            answerCount += messages.get(i).getAnswers().size();
        }
        assertTrue(answerCount < zone.allRecords().size());
        assertTrue(containsName(messages, "delta.example.com."));
    }

    @Test
    public void testFallbackToAxfrWhenJournalMissingHistory() throws Exception {
        MutableZone zone = loadSampleZone();
        DnsResourceRecord staleSoa = DnsResourceRecord.soa(zone.getOrigin(), 300,
                zone.getSoaData().mname, zone.getSoaData().rname,
                0, zone.getSoaData().refresh, zone.getSoaData().retry,
                zone.getSoaData().expire, zone.getSoaData().minimum);
        DnsMessage query = ixfrQuery(zone, staleSoa);
        List<DnsMessage> ixfrAttempt = IxfrMessageSplitter.split(query, zone);
        List<DnsMessage> axfr = AxfrMessageSplitter.split(query, zone);
        assertEquals(axfr.size(), ixfrAttempt.size());
        assertEquals(axfr.get(0).getAnswers().size(),
                ixfrAttempt.get(0).getAnswers().size());
    }

    @Test
    public void testFormerrWithoutClientSoa() throws Exception {
        MutableZone zone = loadSampleZone();
        DnsMessage query = DnsMessage.createQuery(9, zone.getOrigin(), DnsType.IXFR);
        List<DnsMessage> messages = IxfrMessageSplitter.split(query, zone);
        assertEquals(1, messages.size());
        assertEquals(DnsMessage.RCODE_FORMERR, messages.get(0).getRcode());
    }

    private static MutableZone loadSampleZone() throws Exception {
        Path zone = Files.createTempFile("ixfr", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "www IN A 192.0.2.1\n");
        try {
            return ZoneFile.load(zone).asMutable();
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    private static void applyAddA(MutableZone zone, String owner, String ip) throws Exception {
        DnsResourceRecord add = DnsResourceRecord.a(owner, 300,
                InetAddress.getByName(ip));
        DnsMessage update = DnsMessage.createDynamicUpdate(11,
                Collections.singletonList(zone.getSoaRecord()),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(add));
        assertEquals(DnsMessage.RCODE_NOERROR, DynamicUpdateProcessor.apply(zone, update));
    }

    private static DnsMessage ixfrQuery(MutableZone zone, int clientSerial) {
        DnsResourceRecord clientSoa = DnsResourceRecord.soa(zone.getOrigin(), 300,
                zone.getSoaData().mname, zone.getSoaData().rname,
                clientSerial, zone.getSoaData().refresh, zone.getSoaData().retry,
                zone.getSoaData().expire, zone.getSoaData().minimum);
        return ixfrQuery(zone, clientSoa);
    }

    private static DnsMessage ixfrQuery(MutableZone zone, DnsResourceRecord clientSoa) {
        return new DnsMessage(8, DnsMessage.FLAG_RD,
                Collections.singletonList(
                        new org.bluezoo.gumdrop.dns.DnsQuestion(
                                zone.getOrigin(), DnsType.IXFR, DnsClass.IN)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(clientSoa),
                Collections.<DnsResourceRecord>emptyList());
    }

    private static boolean containsName(List<DnsMessage> messages, String name) {
        for (int i = 0; i < messages.size(); i++) {
            List<DnsResourceRecord> answers = messages.get(i).getAnswers();
            for (int j = 0; j < answers.size(); j++) {
                if (answers.get(j).getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
