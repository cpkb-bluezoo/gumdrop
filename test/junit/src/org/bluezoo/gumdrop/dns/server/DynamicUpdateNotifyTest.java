/*
 * DynamicUpdateNotifyTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsZoneOperations;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

public class DynamicUpdateNotifyTest {

    @Test
    public void testUpdateAddARecord() throws Exception {
        Path zone = Files.createTempFile("update", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n");
        try {
            MutableZone mutable = ZoneFile.load(zone).asMutable();
            DnsResourceRecord zoneSoa = mutable.getSoaRecord();
            DnsResourceRecord add = DnsResourceRecord.a("new.example.com.", 300,
                    java.net.InetAddress.getByName("192.0.2.9"));
            DnsMessage update = DnsMessage.createDynamicUpdate(99,
                    Collections.singletonList(zoneSoa),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.singletonList(add));
            int rcode = DynamicUpdateProcessor.apply(mutable, update);
            assertEquals(DnsMessage.RCODE_NOERROR, rcode);
            assertEquals(2, mutable.getSerial());
            ZoneLookupResult hit = mutable.lookup("new.example.com.", DnsType.A);
            assertEquals(ZoneLookupResult.STATUS_ANSWER, hit.getStatus());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testNotifyMessageFactory() {
        DnsMessage notify = DnsMessage.createNotify(7, "example.com.");
        assertEquals(DnsMessage.OPCODE_NOTIFY, notify.getOpcode());
        assertEquals(1, notify.getQuestions().size());
        assertEquals(DnsType.SOA, notify.getQuestions().get(0).getType());
    }
}
