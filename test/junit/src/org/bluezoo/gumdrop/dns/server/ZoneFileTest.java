/*
 * ZoneFileTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ZoneFileTest {

    @Test
    public void testWildcardAndNxdomainNodata() throws Exception {
        Path zone = Files.createTempFile("wildcard", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "www IN A 192.0.2.1\n"
                + "exists IN TXT \"hello\"\n"
                + "* IN A 192.0.2.99\n");
        try {
            ZoneFile loaded = ZoneFile.load(zone);

            ZoneLookupResult hit = loaded.lookup("www.example.com.", DnsType.A);
            assertEquals(ZoneLookupResult.STATUS_ANSWER, hit.getStatus());
            assertEquals(1, hit.getAnswers().size());

            ZoneLookupResult wild = loaded.lookup("other.example.com.", DnsType.A);
            assertEquals(ZoneLookupResult.STATUS_ANSWER, wild.getStatus());
            assertTrue(wild.isFromWildcard());

            ZoneLookupResult nodata = loaded.lookup("exists.example.com.", DnsType.A);
            assertEquals(ZoneLookupResult.STATUS_NODATA, nodata.getStatus());

            ZoneLookupResult nx = loaded.lookup("deep.sub.example.com.", DnsType.A);
            assertEquals(ZoneLookupResult.STATUS_NXDOMAIN, nx.getStatus());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testCnameAtOwner() throws Exception {
        Path zone = Files.createTempFile("cname", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "alias IN CNAME target.example.com.\n"
                + "target IN A 192.0.2.5\n");
        try {
            ZoneFile loaded = ZoneFile.load(zone);
            ZoneLookupResult aliasA = loaded.lookup("alias.example.com.", DnsType.A);
            assertEquals(ZoneLookupResult.STATUS_ANSWER, aliasA.getStatus());
            assertEquals(DnsType.CNAME, aliasA.getAnswers().get(0).getType());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testRequiresSoa() throws Exception {
        Path zone = Files.createTempFile("no-soa", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "www IN A 192.0.2.1\n");
        try {
            try {
                ZoneFile.load(zone);
                fail("expected IOException");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().contains("SOA"));
            }
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testGlueCollection() throws Exception {
        Path zone = Files.createTempFile("glue", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 192.0.2.2\n");
        try {
            ZoneFile loaded = ZoneFile.load(zone);
            assertFalse(loaded.glueFor(loaded.getNsRecords()).isEmpty());
        } finally {
            Files.deleteIfExists(zone);
        }
    }
}
