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
import org.bluezoo.gumdrop.dns.DNSFormatException;
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

    // -- Response delivery via mock transport --

    @Test
    public void testResponseDeliveredViaTransport() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DNSResolver resolver = new DNSResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DNSMessage> result = new AtomicReference<>();
        resolver.query("wire.example.com", DNSType.A,
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

        assertEquals(1, mockTransport.sendCount);
        assertNotNull(mockTransport.lastSentData);

        int queryId = extractId(mockTransport.lastSentData);
        DNSMessage response = buildResponse(queryId, "wire.example.com",
                false, new byte[]{1, 2, 3, 4});
        mockTransport.handler.onReceive(response.serialize());

        assertNotNull("Callback should receive response", result.get());
        assertEquals(1, result.get().getAnswers().size());
        resolver.close();
    }

    // -- Truncation: TCP retry succeeds --

    @Test
    public void testTruncatedResponseRetriedOverTcp() throws Exception {
        MockTransport mockUdp = new MockTransport();
        MockTcpTransport mockTcp = new MockTcpTransport();

        DNSResolver resolver = new TestableResolver(mockTcp);
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DNSMessage> result = new AtomicReference<>();
        resolver.query("big.example.com", DNSType.A,
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

        int queryId = extractId(mockUdp.lastSentData);

        // Deliver a truncated UDP response
        DNSMessage truncated = buildResponse(queryId, "big.example.com",
                true, new byte[]{1, 1, 1, 1});
        mockUdp.handler.onReceive(truncated.serialize());

        // TCP transport should have been opened and sent a query
        assertNull("Should not have delivered truncated yet", result.get());
        assertTrue(mockTcp.opened);
        assertTrue(mockTcp.sendCount > 0);

        // Simulate TCP response with two answers
        InetAddress addr1 = InetAddress.getByAddress(
                new byte[]{10, 0, 0, 1});
        InetAddress addr2 = InetAddress.getByAddress(
                new byte[]{10, 0, 0, 2});
        List<DNSResourceRecord> fullAnswers = new ArrayList<>();
        fullAnswers.add(DNSResourceRecord.a("big.example.com", 300, addr1));
        fullAnswers.add(DNSResourceRecord.a("big.example.com", 300, addr2));
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD
                | DNSMessage.FLAG_RA;
        DNSMessage tcpResponse = new DNSMessage(queryId, flags,
                Collections.singletonList(
                        new DNSQuestion("big.example.com", DNSType.A)),
                fullAnswers,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());

        mockTcp.handler.onReceive(tcpResponse.serialize());

        assertNotNull("Should receive TCP response", result.get());
        assertEquals("Full TCP response should have 2 answers",
                2, result.get().getAnswers().size());
        assertTrue("TCP transport should be closed", mockTcp.closed);
        resolver.close();
    }

    // -- Truncation: TCP open fails, fall back to truncated --

    @Test
    public void testTruncatedFallbackWhenTcpOpenFails() throws Exception {
        MockTcpTransport mockTcp = new MockTcpTransport();
        mockTcp.failOnOpen = true;

        DNSResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DNSMessage> result = new AtomicReference<>();
        resolver.query("fail.example.com", DNSType.A,
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

        int queryId = extractId(mockUdp.lastSentData);
        DNSMessage truncated = buildResponse(queryId, "fail.example.com",
                true, new byte[]{5, 5, 5, 5});
        mockUdp.handler.onReceive(truncated.serialize());

        assertNotNull("Should fall back to truncated response", result.get());
        assertTrue("Fallback response should still be truncated",
                result.get().isTruncated());
        resolver.close();
    }

    // -- Truncation: TCP transport error callback --

    @Test
    public void testTruncatedFallbackOnTcpError() throws Exception {
        MockTcpTransport mockTcp = new MockTcpTransport();

        DNSResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DNSMessage> result = new AtomicReference<>();
        resolver.query("err.example.com", DNSType.A,
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

        int queryId = extractId(mockUdp.lastSentData);
        DNSMessage truncated = buildResponse(queryId, "err.example.com",
                true, new byte[]{6, 6, 6, 6});
        mockUdp.handler.onReceive(truncated.serialize());

        assertNull("Not delivered yet", result.get());

        // Simulate TCP transport error
        mockTcp.handler.onError(new IOException("connection refused"));

        assertNotNull("Should fall back to truncated", result.get());
        assertTrue(result.get().isTruncated());
        assertTrue(mockTcp.closed);
        resolver.close();
    }

    // -- Truncation: TCP timeout --

    @Test
    public void testTruncatedFallbackOnTcpTimeout() throws Exception {
        MockTcpTransport mockTcp = new MockTcpTransport();

        DNSResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DNSMessage> result = new AtomicReference<>();
        resolver.query("slow.example.com", DNSType.A,
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

        int queryId = extractId(mockUdp.lastSentData);
        DNSMessage truncated = buildResponse(queryId, "slow.example.com",
                true, new byte[]{7, 7, 7, 7});
        mockUdp.handler.onReceive(truncated.serialize());

        assertNull("Not delivered yet", result.get());
        assertNotNull("Timer should be scheduled",
                mockTcp.lastTimerCallback);

        // Fire the timeout
        mockTcp.lastTimerCallback.run();

        assertNotNull("Should fall back to truncated", result.get());
        assertTrue(result.get().isTruncated());
        assertTrue(mockTcp.closed);
        resolver.close();
    }

    // -- Double-delivery guard --

    @Test
    public void testTcpRetryDeliversExactlyOnce() throws Exception {
        MockTcpTransport mockTcp = new MockTcpTransport();

        DNSResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final List<DNSMessage> results = new ArrayList<>();
        resolver.query("once.example.com", DNSType.A,
                new DNSQueryCallback() {
                    @Override
                    public void onResponse(DNSMessage response) {
                        results.add(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockUdp.lastSentData);
        DNSMessage truncated = buildResponse(queryId, "once.example.com",
                true, new byte[]{8, 8, 8, 8});
        mockUdp.handler.onReceive(truncated.serialize());

        // Deliver TCP response, then simulate error and timeout
        DNSMessage tcpResp = buildResponse(queryId, "once.example.com",
                false, new byte[]{9, 9, 9, 9});
        mockTcp.handler.onReceive(tcpResp.serialize());
        mockTcp.handler.onError(new IOException("late error"));
        if (mockTcp.lastTimerCallback != null) {
            mockTcp.lastTimerCallback.run();
        }

        assertEquals("Should deliver exactly once", 1, results.size());
        assertFalse("Should be the TCP (non-truncated) response",
                results.get(0).isTruncated());
        resolver.close();
    }

    // -- Helpers --

    private static int extractId(ByteBuffer data) {
        return ((data.get(0) & 0xFF) << 8) | (data.get(1) & 0xFF);
    }

    private static DNSMessage buildResponse(int queryId, String name,
                                            boolean truncated,
                                            byte[] addr) throws Exception {
        int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RD
                | DNSMessage.FLAG_RA;
        if (truncated) {
            flags |= DNSMessage.FLAG_TC;
        }
        List<DNSResourceRecord> answers = new ArrayList<>();
        answers.add(DNSResourceRecord.a(name, 300,
                InetAddress.getByAddress(addr)));
        return new DNSMessage(queryId, flags,
                Collections.singletonList(
                        new DNSQuestion(name, DNSType.A)),
                answers,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());
    }

    // -- Mock classes --

    /**
     * Minimal mock transport that records sends without actually
     * performing I/O.
     */
    private static class MockTransport implements DNSClientTransport {

        int sendCount;
        ByteBuffer lastSentData;
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
            lastSentData = ByteBuffer.allocate(data.remaining());
            lastSentData.put(data);
            lastSentData.flip();
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs,
                                         Runnable callback) {
            return new MockTimerHandle();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Mock TCP transport injected via {@link TestableResolver} to
     * test the async truncation retry path.
     */
    private static class MockTcpTransport implements DNSClientTransport {

        boolean failOnOpen;
        boolean opened;
        boolean closed;
        int sendCount;
        DNSClientTransportHandler handler;
        Runnable lastTimerCallback;

        @Override
        public void open(InetAddress server, int port,
                         org.bluezoo.gumdrop.SelectorLoop loop,
                         DNSClientTransportHandler handler)
                throws IOException {
            if (failOnOpen) {
                throw new IOException("Mock TCP open failure");
            }
            this.opened = true;
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
            sendCount++;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs,
                                         Runnable callback) {
            this.lastTimerCallback = callback;
            return new MockTimerHandle();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static class MockTimerHandle implements TimerHandle {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * Resolver subclass that returns an injected TCP transport for
     * truncation retries.
     */
    private static class TestableResolver extends DNSResolver {
        private final DNSClientTransport tcpTransport;

        TestableResolver(DNSClientTransport tcpTransport) {
            this.tcpTransport = tcpTransport;
        }

        @Override
        DNSClientTransport createTcpRetryTransport() {
            return tcpTransport;
        }
    }

}
