/*
 * DNSResolverTest.java
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

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DNSCache;
import org.bluezoo.gumdrop.dns.DNSClass;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSQuestion;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DNSResolver}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResolverTest {

    private DNSCache originalCache;

    @Before
    public void setUp() {
        originalCache = DNSResolver.getCache();
        DNSResolver.setCache(new DNSCache());
    }

    @After
    public void tearDown() {
        DNSResolver.setCache(originalCache);
    }

    @Test
    public void testQueryErrorWhenNotOpened() {
        DNSResolver resolver = new DNSResolver();
        final AtomicReference<String> error = new AtomicReference<>();
        resolver.query("example.com", DNSType.A, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                fail("Should not get response");
            }

            @Override
            public void onError(String err) {
                error.set(err);
            }
        });
        assertNotNull("Should report error when not opened", error.get());
    }

    @Test
    public void testCacheHitDeliveredWithoutQuery() throws Exception {
        DNSCache cache = DNSResolver.getCache();
        DNSQuestion question = new DNSQuestion("cached.example.com",
                DNSType.A, DNSClass.IN);
        InetAddress addr = InetAddress.getByAddress(
                new byte[]{10, 0, 0, 1});
        List<DNSResourceRecord> records = new ArrayList<>();
        records.add(DNSResourceRecord.a("cached.example.com", 300, addr));
        cache.cache(question, records);

        MockTransport mockTransport = new MockTransport();
        DNSResolver resolver = new DNSResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DNSMessage> result = new AtomicReference<>();
        resolver.query("cached.example.com", DNSType.A,
                new DNSQueryCallback() {
                    @Override
                    public void onResponse(DNSMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        assertNotNull("Should get cached response", result.get());
        assertEquals(1, result.get().getAnswers().size());
        assertEquals(0, mockTransport.sendCount);
        resolver.close();
    }

    @Test
    public void testNegativeCacheDeliversSyntheticNxdomain() throws Exception {
        DNSCache cache = DNSResolver.getCache();
        cache.cacheNegative("nxdomain.example.com");

        MockTransport mockTransport = new MockTransport();
        DNSResolver resolver = new DNSResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DNSMessage> result = new AtomicReference<>();
        resolver.query("nxdomain.example.com", DNSType.A,
                new DNSQueryCallback() {
                    @Override
                    public void onResponse(DNSMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        assertNotNull("Should get NXDOMAIN response", result.get());
        assertEquals(DNSMessage.RCODE_NXDOMAIN, result.get().getRcode());
        assertEquals(0, mockTransport.sendCount);
        resolver.close();
    }

    /**
     * Minimal mock transport that records sends without actually
     * performing I/O.
     */
    private static class MockTransport implements DNSClientTransport {

        int sendCount;
        DNSClientTransportHandler handler;

        @Override
        public void open(InetAddress server, int port,
                         org.bluezoo.gumdrop.SelectorLoop loop,
                         DNSClientTransportHandler handler)
                throws IOException {
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
            sendCount++;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs,
                                         Runnable callback) {
            return new TimerHandle() {
                private boolean cancelled;

                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        @Override
        public void close() {
        }
    }

}
