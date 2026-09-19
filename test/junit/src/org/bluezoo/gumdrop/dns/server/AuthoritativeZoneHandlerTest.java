/*
 * AuthoritativeZoneHandlerTest.java
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

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthoritativeZoneHandlerTest {

    @Test
    public void testAuthoritativeAnswersAndRefused() throws Exception {
        Path zone = writeSampleZone();
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zone(ZoneFile.load(zone))
                    .build();

            DnsMessage query = DnsMessage.createQuery(1, "www.example.com.", DnsType.A);
            DnsMessage response = syncHandle(handler, query);
            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertTrue(response.isAuthoritative());
            assertEquals(1, response.getAnswers().size());
            assertFalse(response.getAuthorities().isEmpty());

            DnsMessage outside = DnsMessage.createQuery(2, "other.test.", DnsType.A);
            assertEquals(DnsMessage.RCODE_REFUSED,
                    syncHandle(handler, outside).getRcode());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testNodataIncludesSoaInAuthority() throws Exception {
        Path zone = writeSampleZone();
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zone(ZoneFile.load(zone))
                    .build();
            DnsMessage query = DnsMessage.createQuery(3, "www.example.com.", DnsType.TXT);
            DnsMessage response = syncHandle(handler, query);
            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertTrue(response.getAnswers().isEmpty());
            assertEquals(1, response.getAuthorities().size());
            assertEquals(DnsType.SOA, response.getAuthorities().get(0).getType());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testNxdomainIncludesSoaInAuthority() throws Exception {
        Path zone = writeSampleZone();
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zone(ZoneFile.load(zone))
                    .build();
            DnsMessage query = DnsMessage.createQuery(4, "missing.example.com.", DnsType.A);
            DnsMessage response = syncHandle(handler, query);
            assertEquals(DnsMessage.RCODE_NXDOMAIN, response.getRcode());
            assertEquals(DnsType.SOA, response.getAuthorities().get(0).getType());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testCnameChain() throws Exception {
        Path zone = Files.createTempFile("chain", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "alias IN CNAME target.example.com.\n"
                + "target IN A 192.0.2.8\n");
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zone(ZoneFile.load(zone))
                    .build();
            DnsMessage query = DnsMessage.createQuery(5, "alias.example.com.", DnsType.A);
            DnsMessage response = syncHandle(handler, query);
            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertEquals(2, response.getAnswers().size());
            assertEquals(DnsType.CNAME, response.getAnswers().get(0).getType());
            assertEquals(DnsType.A, response.getAnswers().get(1).getType());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    private static Path writeSampleZone() throws Exception {
        Path zone = Files.createTempFile("auth", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 3600\n"
                + "@ IN SOA ns1.example.com. admin.example.com. 1 3600 1800 86400 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "www IN A 192.0.2.1\n");
        return zone;
    }

    private static DnsMessage syncHandle(AuthoritativeZoneHandler handler,
                                         DnsMessage query) throws Exception {
        final AtomicReference<DnsMessage> result = new AtomicReference<DnsMessage>();
        final CountDownLatch latch = new CountDownLatch(1);
        handler.handleQuery(query, null, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                result.set(response);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return result.get();
    }
}
