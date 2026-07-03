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
        assertTrue(DNSBailiwick.isWithinBailiwick("www.example.com", "example.com"));
        assertTrue(DNSBailiwick.isWithinBailiwick("example.com.", "example.com"));
        assertFalse(DNSBailiwick.isWithinBailiwick("example.com", "co.uk"));
        assertFalse(DNSBailiwick.isWithinBailiwick("evil.co.uk", "example.co.uk"));
    }

    @Test
    public void testFilterAnswersRejectsOutOfBailiwick() throws Exception {
        DNSResourceRecord in = DNSResourceRecord.a("host.example.com", 300,
                java.net.InetAddress.getByAddress(new byte[]{1, 2, 3, 4}));
        DNSResourceRecord out = DNSResourceRecord.a("host.evil.com", 300,
                java.net.InetAddress.getByAddress(new byte[]{5, 6, 7, 8}));
        List<DNSResourceRecord> answers = Arrays.asList(in, out);
        List<DNSResourceRecord> filtered = DNSBailiwick.filterAnswersInBailiwick(
                "example.com", answers);
        assertEquals(1, filtered.size());
        assertEquals("host.example.com", filtered.get(0).getName());
    }

    @Test
    public void testNamesEqualIgnoresCaseAndTrailingDot() {
        assertTrue(DNSBailiwick.namesEqual("Example.COM.", "example.com"));
    }
}
