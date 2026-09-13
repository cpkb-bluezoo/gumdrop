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
import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsCookie;
import org.bluezoo.gumdrop.dns.DnsFormatException;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsMultiQType;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnssecAwareQueryCallback;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DnsResolver}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResolverTest {

    private DnsCache originalCache;

    @Before
    public void setUp() {
        originalCache = DnsResolver.getCache();
        DnsResolver.setCache(new DnsCache());
        DNSMultiQTypeCache.clear();
    }

    @After
    public void tearDown() {
        DnsResolver.setCache(originalCache);
        DNSMultiQTypeCache.clear();
    }

    @Test
    public void testQueryErrorWhenNotOpened() {
        DnsResolver resolver = new DnsResolver();
        final AtomicReference<String> error = new AtomicReference<>();
        resolver.query("example.com", DnsType.A, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
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
        DnsCache cache = DnsResolver.getCache();
        DnsQuestion question = new DnsQuestion("cached.example.com",
                DnsType.A, DnsClass.IN);
        InetAddress addr = InetAddress.getByAddress(
                new byte[]{10, 0, 0, 1});
        List<DnsResourceRecord> records = new ArrayList<>();
        records.add(DnsResourceRecord.a("cached.example.com", 300, addr));
        cache.cache(question, records);

        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.query("cached.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
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
        DnsCache cache = DnsResolver.getCache();
        cache.cacheNegative("nxdomain.example.com");

        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.query("nxdomain.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        assertNotNull("Should get NXDOMAIN response", result.get());
        assertEquals(DnsMessage.RCODE_NXDOMAIN, result.get().getRcode());
        assertEquals(0, mockTransport.sendCount);
        resolver.close();
    }

    // -- Response delivery via mock transport --

    @Test
    public void testResponseDeliveredViaTransport() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.query("wire.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
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
        DnsMessage response = buildResponse(queryId, "wire.example.com",
                false, new byte[]{1, 2, 3, 4});
        mockTransport.handler.onReceive(response.serialize());

        assertNotNull("Callback should receive response", result.get());
        assertEquals(1, result.get().getAnswers().size());
        resolver.close();
    }

    // -- DNSSEC-aware callback delivery --

    @Test
    public void testDnssecAwareCallbackReceivesValidationStatus() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.setDnssecEnabled(true);
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        final AtomicReference<DnssecStatus> statusRef = new AtomicReference<>();
        resolver.queryTLSA("_25._tcp.mail.example.com",
                new DnssecAwareQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response, DnssecStatus status) {
                        result.set(response);
                        statusRef.set(status);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockTransport.lastSentData);
        // A NODATA response (no answers, no authority NSEC/NSEC3) is
        // provably insecure per RFC 4035 section 5: DnssecChainValidator
        // has nothing to walk a chain of trust from.
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        DnsMessage nodata = new DnsMessage(queryId, flags,
                Collections.singletonList(
                        new DnsQuestion("_25._tcp.mail.example.com", DnsType.TLSA)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        mockTransport.handler.onReceive(nodata.serialize());

        assertNotNull("Callback should receive response", result.get());
        assertEquals("An unsigned NODATA response must be reported "
                        + "INSECURE, not silently treated as validated",
                DnssecStatus.INSECURE, statusRef.get());
        resolver.close();
    }

    @Test
    public void testPlainCallbackStillDeliveredWhenDnssecEnabled() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.setDnssecEnabled(true);
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.queryTLSA("_25._tcp.mail.example.com",
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockTransport.lastSentData);
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        DnsMessage nodata = new DnsMessage(queryId, flags,
                Collections.singletonList(
                        new DnsQuestion("_25._tcp.mail.example.com", DnsType.TLSA)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        mockTransport.handler.onReceive(nodata.serialize());

        assertNotNull("A plain DnsQueryCallback must still be delivered "
                + "the response even though it can't see the DNSSEC status",
                result.get());
        resolver.close();
    }

    @Test
    public void testDnssecAwareCallbackGetsIndeterminateWhenDnssecDisabled()
            throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnssecStatus> statusRef = new AtomicReference<>();
        resolver.query("wire.example.com", DnsType.A,
                new DnssecAwareQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response, DnssecStatus status) {
                        statusRef.set(status);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockTransport.lastSentData);
        DnsMessage response = buildResponse(queryId, "wire.example.com",
                false, new byte[]{1, 2, 3, 4});
        mockTransport.handler.onReceive(response.serialize());

        assertEquals("Without DNSSEC enabled there is nothing to "
                        + "validate, so status must be INDETERMINATE, "
                        + "never SECURE",
                DnssecStatus.INDETERMINATE, statusRef.get());
        resolver.close();
    }

    // -- Truncation: TCP retry succeeds --

    @Test
    public void testTruncatedResponseRetriedOverTcp() throws Exception {
        MockTransport mockUdp = new MockTransport();
        MockTcpTransport mockTcp = new MockTcpTransport();

        DnsResolver resolver = new TestableResolver(mockTcp);
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.query("big.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockUdp.lastSentData);

        // Deliver a truncated UDP response
        DnsMessage truncated = buildResponse(queryId, "big.example.com",
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
        List<DnsResourceRecord> fullAnswers = new ArrayList<>();
        fullAnswers.add(DnsResourceRecord.a("big.example.com", 300, addr1));
        fullAnswers.add(DnsResourceRecord.a("big.example.com", 300, addr2));
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD
                | DnsMessage.FLAG_RA;
        DnsMessage tcpResponse = new DnsMessage(queryId, flags,
                Collections.singletonList(
                        new DnsQuestion("big.example.com", DnsType.A)),
                fullAnswers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());

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

        DnsResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.query("fail.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockUdp.lastSentData);
        DnsMessage truncated = buildResponse(queryId, "fail.example.com",
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

        DnsResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.query("err.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockUdp.lastSentData);
        DnsMessage truncated = buildResponse(queryId, "err.example.com",
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

        DnsResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        resolver.query("slow.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        result.set(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockUdp.lastSentData);
        DnsMessage truncated = buildResponse(queryId, "slow.example.com",
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

        DnsResolver resolver = new TestableResolver(mockTcp);
        MockTransport mockUdp = new MockTransport();
        resolver.setTransport(mockUdp);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final List<DnsMessage> results = new ArrayList<>();
        resolver.query("once.example.com", DnsType.A,
                new DnsQueryCallback() {
                    @Override
                    public void onResponse(DnsMessage response) {
                        results.add(response);
                    }

                    @Override
                    public void onError(String err) {
                        fail("Should not get error: " + err);
                    }
                });

        int queryId = extractId(mockUdp.lastSentData);
        DnsMessage truncated = buildResponse(queryId, "once.example.com",
                true, new byte[]{8, 8, 8, 8});
        mockUdp.handler.onReceive(truncated.serialize());

        // Deliver TCP response, then simulate error and timeout
        DnsMessage tcpResp = buildResponse(queryId, "once.example.com",
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

    @Test
    public void testQueryIdsAreNotSequential() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        resolver.query("a.example.com", DnsType.A, noopCallback());
        int id1 = extractId(mockTransport.lastSentData);

        resolver.query("b.example.com", DnsType.A, noopCallback());
        int id2 = extractId(mockTransport.lastSentData);

        assertNotEquals("Second query should not reuse first ID", id1, id2);
        assertFalse("IDs should not start at predictable 0,1",
                (id1 == 0 && id2 == 1) || (id1 == 1 && id2 == 2));
        resolver.close();
    }

    private static DnsQueryCallback noopCallback() {
        return new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
            }

            @Override
            public void onError(String err) {
            }
        };
    }

    // -- queryBatch (RFC 10029) --

    @Test
    public void testBatchMergedResponseSingleExchange() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final List<DnsType> resultTypes = new ArrayList<>();
        final List<List<DnsResourceRecord>> resultRecords = new ArrayList<>();
        final boolean[] completed = {false};
        resolver.queryBatch("merged.example.com", Arrays.asList(DnsType.A, DnsType.AAAA),
                new BatchQueryCallback() {
                    @Override
                    public void onResult(DnsType type, List<DnsResourceRecord> records) {
                        resultTypes.add(type);
                        resultRecords.add(records);
                    }

                    @Override
                    public void onTypeError(DnsType type, String error) {
                        fail("Should not error for " + type + ": " + error);
                    }

                    @Override
                    public void onComplete() {
                        completed[0] = true;
                    }
                });

        // Only the primary query should have gone out -- the option
        // asks the server to merge AAAA in too.
        assertEquals("Should be exactly one exchange", 1, mockTransport.sendCount);
        int queryId = extractId(mockTransport.lastSentData);
        assertNotNull("Outgoing query should carry MQTYPE-Query for AAAA",
                mqTypeQueryOption(mockTransport.lastSentData));

        DnsMessage response = buildBatchResponse(queryId, "merged.example.com",
                Arrays.asList(DnsType.A, DnsType.AAAA),
                Collections.singletonList(DnsType.AAAA));
        mockTransport.handler.onReceive(response.serialize());

        assertEquals("Still exactly one exchange (no fallback needed)",
                1, mockTransport.sendCount);
        assertTrue("Should have completed", completed[0]);
        assertEquals(new HashSet<>(Arrays.asList(DnsType.A, DnsType.AAAA)),
                new HashSet<>(resultTypes));
        resolver.close();
    }

    @Test
    public void testBatchPartialMQTypeResponseFallsBackForMissingType() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final Set<DnsType> delivered = Collections.synchronizedSet(new HashSet<DnsType>());
        final boolean[] completed = {false};
        resolver.queryBatch("partial.example.com",
                Arrays.asList(DnsType.A, DnsType.AAAA, DnsType.HTTPS),
                new BatchQueryCallback() {
                    @Override
                    public void onResult(DnsType type, List<DnsResourceRecord> records) {
                        delivered.add(type);
                    }

                    @Override
                    public void onTypeError(DnsType type, String error) {
                        fail("Should not error for " + type + ": " + error);
                    }

                    @Override
                    public void onComplete() {
                        completed[0] = true;
                    }
                });

        assertEquals(1, mockTransport.sendCount);
        int primaryId = extractId(mockTransport.lastSentData);

        // Server only merges AAAA back -- HTTPS is left uncovered.
        DnsMessage response = buildBatchResponse(primaryId, "partial.example.com",
                Arrays.asList(DnsType.A, DnsType.AAAA),
                Collections.singletonList(DnsType.AAAA));
        mockTransport.handler.onReceive(response.serialize());

        assertFalse("Should not be complete yet (HTTPS still outstanding)", completed[0]);
        assertEquals("Should have sent a standalone fallback query for HTTPS",
                2, mockTransport.sendCount);
        int fallbackId = extractId(mockTransport.lastSentData);
        assertNotEquals(primaryId, fallbackId);

        DnsMessage httpsResponse = buildBatchResponse(fallbackId, "partial.example.com",
                Collections.singletonList(DnsType.HTTPS),
                Collections.<DnsType>emptyList());
        mockTransport.handler.onReceive(httpsResponse.serialize());

        assertTrue("Should be complete now", completed[0]);
        assertEquals(new HashSet<>(Arrays.asList(DnsType.A, DnsType.AAAA, DnsType.HTTPS)),
                delivered);
        resolver.close();
    }

    @Test
    public void testBatchUnsupportedServerFallsBackForAllAdditionalTypes() throws Exception {
        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        final Set<DnsType> delivered = Collections.synchronizedSet(new HashSet<DnsType>());
        final boolean[] completed = {false};
        resolver.queryBatch("unsupported.example.com", Arrays.asList(DnsType.A, DnsType.AAAA),
                new BatchQueryCallback() {
                    @Override
                    public void onResult(DnsType type, List<DnsResourceRecord> records) {
                        delivered.add(type);
                    }

                    @Override
                    public void onTypeError(DnsType type, String error) {
                        fail("Should not error for " + type + ": " + error);
                    }

                    @Override
                    public void onComplete() {
                        completed[0] = true;
                    }
                });

        assertEquals(1, mockTransport.sendCount);
        int primaryId = extractId(mockTransport.lastSentData);

        // Plain response, no MQTYPE-Response option at all -- server
        // doesn't support RFC 10029.
        DnsMessage response = buildResponse(primaryId, "unsupported.example.com",
                false, new byte[]{1, 2, 3, 4});
        mockTransport.handler.onReceive(response.serialize());

        assertEquals("Should have fallen back to a standalone AAAA query",
                2, mockTransport.sendCount);
        int fallbackId = extractId(mockTransport.lastSentData);

        DnsMessage aaaaResponse = buildBatchResponse(fallbackId, "unsupported.example.com",
                Collections.singletonList(DnsType.AAAA), Collections.<DnsType>emptyList());
        mockTransport.handler.onReceive(aaaaResponse.serialize());

        assertTrue(completed[0]);
        assertEquals(new HashSet<>(Arrays.asList(DnsType.A, DnsType.AAAA)), delivered);
        assertTrue("Server should now be cached as not supporting RFC 10029",
                DNSMultiQTypeCache.isKnownUnsupported(
                        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53)));
        resolver.close();
    }

    @Test
    public void testBatchSkipsOptionForKnownUnsupportedServer() throws Exception {
        DNSMultiQTypeCache.markUnsupported(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53));

        MockTransport mockTransport = new MockTransport();
        DnsResolver resolver = new DnsResolver();
        resolver.setTransport(mockTransport);
        resolver.addServer("127.0.0.1");
        resolver.open();

        resolver.queryBatch("skip-option.example.com", Arrays.asList(DnsType.A, DnsType.AAAA),
                new BatchQueryCallback() {
                    @Override
                    public void onResult(DnsType type, List<DnsResourceRecord> records) {
                    }

                    @Override
                    public void onTypeError(DnsType type, String error) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });

        assertEquals("Primary query still goes out immediately", 1, mockTransport.sendCount);
        assertNull("Should not attach MQTYPE-Query to a server known not to support it",
                mqTypeQueryOption(mockTransport.lastSentData));
        resolver.close();
    }

    private static byte[] mqTypeQueryOption(ByteBuffer data) throws DnsFormatException {
        ByteBuffer copy = data.duplicate();
        copy.rewind();
        DnsMessage message;
        try {
            message = DnsMessage.parse(copy);
        } catch (DnsFormatException e) {
            throw e;
        }
        for (DnsResourceRecord rr : message.getAdditionals()) {
            if (rr.getType() == DnsType.OPT) {
                return DnsCookie.findEdnsOption(rr.getRData(),
                        DnsMultiQType.EDNS_OPTION_MQTYPE_QUERY);
            }
        }
        return null;
    }

    private static DnsMessage buildBatchResponse(int queryId, String name,
                                                  List<DnsType> answerTypes,
                                                  List<DnsType> mqTypeResponseCoverage)
            throws Exception {
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA;
        List<DnsResourceRecord> answers = new ArrayList<>();
        DnsType primaryType = answerTypes.get(0);
        for (DnsType type : answerTypes) {
            answers.add(answerRecord(name, type));
        }
        List<DnsResourceRecord> additionals = new ArrayList<>();
        if (!mqTypeResponseCoverage.isEmpty()) {
            byte[] mqtypeOption = DnsMultiQType.buildMQTypeResponseOption(mqTypeResponseCoverage);
            additionals.add(DnsResourceRecord.opt(4096, 0, mqtypeOption));
        }
        return new DnsMessage(queryId, flags,
                Collections.singletonList(new DnsQuestion(name, primaryType)),
                answers,
                Collections.<DnsResourceRecord>emptyList(),
                additionals);
    }

    private static DnsResourceRecord answerRecord(String name, DnsType type) throws Exception {
        switch (type) {
            case A:
                return DnsResourceRecord.a(name, 300,
                        InetAddress.getByAddress(new byte[]{10, 0, 0, 1}));
            case AAAA:
                return DnsResourceRecord.aaaa(name, 300,
                        InetAddress.getByAddress(new byte[]{
                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}));
            case HTTPS:
                return DnsResourceRecord.https(name, 300, 1, ".",
                        Collections.<Integer, byte[]>emptyMap());
            default:
                throw new IllegalArgumentException("Unsupported test type: " + type);
        }
    }

    // -- Helpers --

    private static int extractId(ByteBuffer data) {
        return ((data.get(0) & 0xFF) << 8) | (data.get(1) & 0xFF);
    }

    private static DnsMessage buildResponse(int queryId, String name,
                                            boolean truncated,
                                            byte[] addr) throws Exception {
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD
                | DnsMessage.FLAG_RA;
        if (truncated) {
            flags |= DnsMessage.FLAG_TC;
        }
        List<DnsResourceRecord> answers = new ArrayList<>();
        answers.add(DnsResourceRecord.a(name, 300,
                InetAddress.getByAddress(addr)));
        return new DnsMessage(queryId, flags,
                Collections.singletonList(
                        new DnsQuestion(name, DnsType.A)),
                answers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
    }

    // -- Mock classes --

    /**
     * Minimal mock transport that records sends without actually
     * performing I/O.
     */
    private static class MockTransport implements DnsClientTransport {

        int sendCount;
        ByteBuffer lastSentData;
        DnsClientTransportHandler handler;

        @Override
        public void open(InetAddress server, int port,
                         org.bluezoo.gumdrop.SelectorLoop loop,
                         DnsClientTransportHandler handler)
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
    private static class MockTcpTransport implements DnsClientTransport {

        boolean failOnOpen;
        boolean opened;
        boolean closed;
        int sendCount;
        DnsClientTransportHandler handler;
        Runnable lastTimerCallback;

        @Override
        public void open(InetAddress server, int port,
                         org.bluezoo.gumdrop.SelectorLoop loop,
                         DnsClientTransportHandler handler)
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
    private static class TestableResolver extends DnsResolver {
        private final DnsClientTransport tcpTransport;

        TestableResolver(DnsClientTransport tcpTransport) {
            this.tcpTransport = tcpTransport;
        }

        @Override
        DnsClientTransport createTcpRetryTransport() {
            return tcpTransport;
        }
    }

}
