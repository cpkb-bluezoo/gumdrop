/*
 * DNSBailiwickTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DNSBailiwickTest {

    @Test
    public void testIsWithinBailiwick() {
        assertTrue(DnsBailiwick.isWithinBailiwick("www.example.com", "example.com"));
        assertTrue(DnsBailiwick.isWithinBailiwick("example.com.", "example.com"));
        assertFalse(DnsBailiwick.isWithinBailiwick("example.com", "co.uk"));
        assertFalse(DnsBailiwick.isWithinBailiwick("evil.co.uk", "example.co.uk"));
    }

    @Test
    public void testFilterAnswersRejectsOutOfBailiwick() throws Exception {
        DnsResourceRecord in = DnsResourceRecord.a("host.example.com", 300,
                java.net.InetAddress.getByAddress(new byte[]{1, 2, 3, 4}));
        DnsResourceRecord out = DnsResourceRecord.a("host.evil.com", 300,
                java.net.InetAddress.getByAddress(new byte[]{5, 6, 7, 8}));
        List<DnsResourceRecord> answers = Arrays.asList(in, out);
        List<DnsResourceRecord> filtered = DnsBailiwick.filterAnswersInBailiwick(
                "example.com", answers);
        assertEquals(1, filtered.size());
        assertEquals("host.example.com", filtered.get(0).getName());
    }

    @Test
    public void testNamesEqualIgnoresCaseAndTrailingDot() {
        assertTrue(DnsBailiwick.namesEqual("Example.COM.", "example.com"));
    }
}
