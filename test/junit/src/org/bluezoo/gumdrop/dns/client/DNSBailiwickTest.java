/*
 * DNSBailiwickTest.java
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

import org.bluezoo.gumdrop.dns.DnsResourceRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
