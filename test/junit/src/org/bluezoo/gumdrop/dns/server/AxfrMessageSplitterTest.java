/*
 * AxfrMessageSplitterTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class AxfrMessageSplitterTest {

    @Test
    public void testSplitEndsWithSoa() throws Exception {
        Path zone = Files.createTempFile("axfr", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "www IN A 192.0.2.1\n");
        try {
            MutableZone mutable = ZoneFile.load(zone).asMutable();
            DnsMessage query = DnsMessage.createQuery(1, "example.com.", DnsType.AXFR);
            List<DnsMessage> parts = AxfrMessageSplitter.split(query, mutable);
            assertFalse(parts.isEmpty());
            assertEquals(DnsType.SOA, parts.get(0).getAnswers().get(0).getType());
            DnsMessage last = parts.get(parts.size() - 1);
            assertEquals(DnsType.SOA,
                    last.getAnswers().get(last.getAnswers().size() - 1).getType());
        } finally {
            Files.deleteIfExists(zone);
        }
    }
}
