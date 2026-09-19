/*
 * NotifyTargetsTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class NotifyTargetsTest {

    @Test
    public void testInZoneNsGlueBecomesNotifyTarget() throws Exception {
        Path zone = Files.createTempFile("notify", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns2.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "ns2 IN A 192.0.2.2\n");
        try {
            MutableZone mutable = ZoneFile.load(zone).asMutable();
            assertEquals(1, mutable.inZoneSecondaryAddresses().size());
            assertEquals(new InetSocketAddress("192.0.2.2", 53),
                    mutable.inZoneSecondaryAddresses().get(0));
        } finally {
            Files.deleteIfExists(zone);
        }
    }
}
