/*
 * DnsServerCompositionTest.java
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

package org.bluezoo.gumdrop.dns;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.dns.server.AuthoritativeZoneHandler;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandlers;
import org.bluezoo.gumdrop.dns.server.SyncDnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.dns.server.UpstreamRelayHandler;
import org.bluezoo.gumdrop.dns.server.ZoneFile;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Workstream C.3 — {@link DnsServer} composition API.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsServerCompositionTest {

    @Test
    public void testDefaultServerReturnsEmptyNoError() throws Exception {
        DnsServer server = new DnsServer();
        DnsMessage query = DnsMessage.createQuery(1, "example.com.", DnsType.A);
        DnsMessage response = syncProcessQuery(server, query);
        assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
        assertTrue(response.getAnswers().isEmpty());
    }

    @Test
    public void testBuilderWithoutHandlerReturnsEmpty() throws Exception {
        DnsServer server = DnsServer.compose()
                .listener(new DnsListener())
                .server();
        DnsMessage query = DnsMessage.createQuery(2, "example.com.", DnsType.A);
        DnsMessage response = syncProcessQuery(server, query);
        assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
        assertTrue(response.getAnswers().isEmpty());
    }

    @Test
    public void testBuilderWithUpstreamRelayHandler() throws Exception {
        DnsServer server = DnsServer.compose()
                .listener(new DnsListener().port(5353))
                .handler(UpstreamRelayHandler.builder()
                        .upstreamServers("127.0.0.1:1")
                        .cacheEnabled(false)
                        .build())
                .server();
        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        server.start(gumdrop);
        try {
            DnsMessage query = DnsMessage.createQuery(3, "example.com.", DnsType.A);
            DnsMessage response = syncProcessQuery(server, query);
            assertEquals(DnsMessage.RCODE_SERVFAIL, response.getRcode());
        } finally {
            server.stop();
            gumdrop.shutdown();
            gumdrop.join();
        }
    }

    @Test
    public void testAuthoritativeZoneHandler() throws Exception {
        Path zone = Files.createTempFile("example-com", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 3600\n"
                + "@ IN SOA ns1.example.com. admin.example.com. 1 3600 1800 86400 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "www IN A 192.0.2.1\n");
        try {
            ZoneFile loaded = ZoneFile.load(zone);
            DnsServer server = DnsServer.compose()
                    .listener(new DnsListener())
                    .handler(AuthoritativeZoneHandler.builder().zone(loaded).build())
                    .server();

            DnsMessage query = DnsMessage.createQuery(4, "www.example.com.",
                    DnsType.A);
            DnsMessage response = syncProcessQuery(server, query);
            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertTrue(response.isAuthoritative());
            assertEquals(1, response.getAnswers().size());
            assertEquals("192.0.2.1",
                    response.getAnswers().get(0).getAddress().getHostAddress());

            DnsMessage outside = DnsMessage.createQuery(5, "other.test.",
                    DnsType.A);
            DnsMessage refused = syncProcessQuery(server, outside);
            assertEquals(DnsMessage.RCODE_REFUSED, refused.getRcode());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    @Test
    public void testSyncDnsQueryHandler() throws Exception {
        DnsServer server = DnsServer.compose()
                .listener(new DnsListener())
                .handler(new SyncDnsQueryHandler() {
                    @Override
                    protected DnsMessage resolveQuery(DnsMessage query) {
                        DnsQuestion q = query.getQuestions().get(0);
                        if ("local.test.".equals(q.getName())) {
                            return query.createResponse(Collections.singletonList(
                                    DnsResourceRecord.a("local.test.", 60,
                                            inetAddress("10.0.0.1"))));
                        }
                        return null;
                    }
                })
                .server();

        DnsMessage hit = DnsMessage.createQuery(6, "local.test.", DnsType.A);
        assertFalse(syncProcessQuery(server, hit).getAnswers().isEmpty());

        DnsMessage miss = DnsMessage.createQuery(7, "missing.test.", DnsType.A);
        assertTrue(syncProcessQuery(server, miss).getAnswers().isEmpty());
    }

    private static DnsMessage syncProcessQuery(DnsServer server, DnsMessage query)
            throws Exception {
        final AtomicReference<DnsMessage> result = new AtomicReference<DnsMessage>();
        final CountDownLatch latch = new CountDownLatch(1);
        server.processQuery(query, null, new DnsQueryCallback() {
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

    private static InetAddress inetAddress(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

}
