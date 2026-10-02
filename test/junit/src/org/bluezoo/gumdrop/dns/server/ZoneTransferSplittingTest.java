/*
 * ZoneTransferSplittingTest.java
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

import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import static org.junit.Assert.*;

/**
 * Zone transfer message splitting for zones and deltas larger than one DNS
 * message: {@link AxfrMessageSplitter} and {@link IxfrMessageSplitter}
 * multi-message packing, framing of the closing SOA, and the IXFR refusal
 * and up-to-date branches.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ZoneTransferSplittingTest {

    private static String bigText(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    private static MutableZone smallZone() throws Exception {
        Path file = MemoryTemp.createTempFile("split", ".zone");
        Files.writeString(file, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n");
        try {
            return ZoneFile.load(file).asMutable();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static DnsMessage ixfrQuery(MutableZone zone, int clientSerial) {
        DnsResourceRecord clientSoa = DnsResourceRecord.soa(zone.getOrigin(), 300,
                zone.getSoaData().mname, zone.getSoaData().rname, clientSerial,
                zone.getSoaData().refresh, zone.getSoaData().retry,
                zone.getSoaData().expire, zone.getSoaData().minimum);
        return new DnsMessage(8, DnsMessage.FLAG_RD,
                Collections.singletonList(new DnsQuestion(zone.getOrigin(),
                        DnsType.IXFR, DnsClass.IN)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(clientSoa),
                Collections.<DnsResourceRecord>emptyList());
    }

    private static void addBigTxts(MutableZone zone, int count) {
        List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < count; i++) {
            adds.add(DnsResourceRecord.txt("t" + i + ".example.com.", 300, bigText(200)));
        }
        DnsMessage update = DnsMessage.createDynamicUpdate(21,
                Collections.singletonList(zone.getSoaRecord()),
                Collections.<DnsResourceRecord>emptyList(), adds);
        assertEquals(DnsMessage.RCODE_NOERROR, DynamicUpdateProcessor.apply(zone, update));
    }

    private static int answerCount(List<DnsMessage> messages) {
        int n = 0;
        for (int i = 0; i < messages.size(); i++) {
            n += messages.get(i).getAnswers().size();
        }
        return n;
    }

    @Test
    public void axfrOfLargeZoneSpansSeveralMessagesBracketedBySoa() throws Exception {
        MutableZone zone = smallZone();
        addBigTxts(zone, 400);
        DnsMessage query = DnsMessage.createQuery(1, zone.getOrigin(), DnsType.AXFR);
        List<DnsMessage> parts = AxfrMessageSplitter.split(query, zone);
        assertTrue("expected several messages, got " + parts.size(), parts.size() > 1);
        assertEquals(DnsType.SOA, parts.get(0).getAnswers().get(0).getType());
        DnsMessage last = parts.get(parts.size() - 1);
        assertEquals(DnsType.SOA,
                last.getAnswers().get(last.getAnswers().size() - 1).getType());
        // every record once, plus the SOA repeated at the end
        assertEquals(zone.allRecords().size() + 1, answerCount(parts));
        for (int i = 0; i < parts.size(); i++) {
            assertEquals(DnsMessage.RCODE_NOERROR, parts.get(i).getRcode());
            assertTrue(parts.get(i).serialize().remaining() <= 65535);
        }
    }

    @Test
    public void ixfrOfLargeDeltaSpansSeveralMessages() throws Exception {
        MutableZone zone = smallZone();
        int clientSerial = zone.getSerial();
        addBigTxts(zone, 400);
        List<DnsMessage> parts = IxfrMessageSplitter.split(ixfrQuery(zone, clientSerial), zone);
        assertTrue("expected several messages, got " + parts.size(), parts.size() > 1);
        DnsMessage last = parts.get(parts.size() - 1);
        assertEquals(DnsType.SOA,
                last.getAnswers().get(last.getAnswers().size() - 1).getType());
        assertTrue(answerCount(parts) >= 400);
    }

    @Test
    public void ixfrFromSerialAheadOfZoneIsRefused() throws Exception {
        MutableZone zone = smallZone();
        List<DnsMessage> parts = IxfrMessageSplitter.split(
                ixfrQuery(zone, zone.getSerial() + 10), zone);
        assertEquals(1, parts.size());
        assertEquals(DnsMessage.RCODE_REFUSED, parts.get(0).getRcode());
    }

    @Test
    public void ixfrSmallDeltaIsSingleMessageEndingWithSoa() throws Exception {
        MutableZone zone = smallZone();
        int clientSerial = zone.getSerial();
        addBigTxts(zone, 1);
        List<DnsMessage> parts = IxfrMessageSplitter.split(ixfrQuery(zone, clientSerial), zone);
        assertEquals(1, parts.size());
        List<DnsResourceRecord> answers = parts.get(0).getAnswers();
        assertEquals(DnsType.SOA, answers.get(answers.size() - 1).getType());
    }
}
