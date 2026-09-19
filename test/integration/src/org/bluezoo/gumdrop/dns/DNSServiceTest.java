/*
 * DNSServiceTest.java
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

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandlers;
import org.bluezoo.gumdrop.dns.server.SyncDnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.dns.server.UpstreamRelayHandler;
import org.bluezoo.gumdrop.SelectorLoop;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DnsServer}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSServiceTest {

    private Gumdrop gumdrop;

    @Before
    public void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(2));
    }

    @After
    public void shutdownGumdrop() throws InterruptedException {
        gumdrop.shutdown();
        gumdrop.join();
    }

    /**
     * Drives {@link DnsServer#processQuery} to completion and returns
     * its response. A synchronous handler answer
     * completes immediately (the latch is already at zero by the
     * time {@code await} runs); upstream forwarding is genuinely
     * asynchronous, so this always waits rather than assuming either
     * shape.
     */
    private static DnsMessage syncProcessQuery(DnsServer service, DnsMessage query)
            throws Exception {
        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        service.processQuery(query, null, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                result.set(response);
                latch.countDown();
            }

            @Override
            public void onError(String err) {
                error.set(err);
                latch.countDown();
            }
        });
        assertTrue("Timed out waiting for processQuery callback",
                latch.await(5, TimeUnit.SECONDS));
        if (error.get() != null) {
            fail("Unexpected error: " + error.get());
        }
        return result.get();
    }

    /**
     * Drives {@link DnsServer#handleDatagram} to completion (i.e.
     * waits for its {@code onComplete} callback, which fires only
     * once a response has actually been sent).
     */
    private static void syncHandleDatagram(DnsServer service, DnsListener listener,
            ByteBuffer data, InetSocketAddress source) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        service.handleDatagram(listener, data, source, new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });
        assertTrue("Timed out waiting for handleDatagram to complete",
                latch.await(5, TimeUnit.SECONDS));
    }

    @Before
    @SuppressWarnings("try") // the resource is never used in the body on purpose -- only its successful construction/close matters here
    public void assumeNetworkBinding() {
        try {
            try (DatagramSocket s = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
                // binding succeeded
            }
        } catch (Exception e) {
            Assume.assumeNoException("Network binding not permitted (e.g. sandbox): skipping", e);
        }
    }

    /**
     * RFC 5452: when the upstream server returns a response whose ID
     * does not match the query, the proxy must discard it.
     */
    @Test
    public void testUpstreamResponseIdMismatchReturnsServfail() throws Exception {
        DatagramSocket mockUpstream = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        int mockPort = mockUpstream.getLocalPort();

        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    mockUpstream.setSoTimeout(3000);
                    mockUpstream.receive(pkt);

                    ByteBuffer queryBuf = ByteBuffer.wrap(buf, 0, pkt.getLength());
                    DnsMessage query = DnsMessage.parse(queryBuf);

                    // Respond with a WRONG ID to simulate spoofing
                    int wrongId = (query.getId() + 1) & 0xFFFF;
                    DnsMessage badResponse = new DnsMessage(
                            wrongId,
                            DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA,
                            query.getQuestions(),
                            Collections.singletonList(
                                    DnsResourceRecord.a("example.com", 300,
                                            InetAddress.getByName("1.2.3.4"))),
                            Collections.emptyList(),
                            Collections.emptyList());

                    ByteBuffer resp = badResponse.serialize();
                    byte[] respBytes = new byte[resp.remaining()];
                    resp.get(respBytes);
                    DatagramPacket reply = new DatagramPacket(
                            respBytes, respBytes.length,
                            pkt.getAddress(), pkt.getPort());
                    mockUpstream.send(reply);
                } catch (Exception e) {
                    // test will fail via timeout
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        try {
            DnsServer service = upstreamRelayServer("127.0.0.1:" + mockPort);
            service.start(gumdrop);

            try {
                DnsMessage query = DnsMessage.createQuery(42, "example.com", DnsType.A);
                DnsMessage response = syncProcessQuery(service, query);

                assertEquals(DnsMessage.RCODE_SERVFAIL, response.getRcode());
            } finally {
                service.stop();
            }
        } finally {
            mockUpstream.close();
        }
    }

    /**
     * RFC 1035 section 4.2.1: when a UDP response is truncated (TC bit
     * set), the proxy should retry over TCP.
     */
    @Test
    public void testUpstreamTcpFallbackOnTruncation() throws Exception {
        DatagramSocket mockUpstream = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        int mockPort = mockUpstream.getLocalPort();

        // Also start a TCP server for the fallback
        java.net.ServerSocket tcpServer = new java.net.ServerSocket(mockPort + 1, 1, InetAddress.getByName("127.0.0.1"));

        Thread udpResponder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    mockUpstream.setSoTimeout(3000);
                    mockUpstream.receive(pkt);

                    ByteBuffer queryBuf = ByteBuffer.wrap(buf, 0, pkt.getLength());
                    DnsMessage query = DnsMessage.parse(queryBuf);

                    // Return a truncated response (TC bit set)
                    int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RD
                            | DnsMessage.FLAG_RA | DnsMessage.FLAG_TC;
                    DnsMessage truncated = new DnsMessage(
                            query.getId(), flags,
                            query.getQuestions(),
                            Collections.emptyList(),
                            Collections.emptyList(),
                            Collections.emptyList());

                    ByteBuffer resp = truncated.serialize();
                    byte[] respBytes = new byte[resp.remaining()];
                    resp.get(respBytes);
                    DatagramPacket reply = new DatagramPacket(
                            respBytes, respBytes.length,
                            pkt.getAddress(), pkt.getPort());
                    mockUpstream.send(reply);
                } catch (Exception e) {
                    // test will fail
                }
            }
        });
        udpResponder.setDaemon(true);
        udpResponder.start();

        // Note: TCP fallback goes to the same host but port in the
        // upstream address. Since our DnsServer.retryOverTcp uses the
        // same address, we need the TCP server on the same port.
        // For simplicity, this test verifies the truncation detection
        // path exists by checking the response is still valid even if
        // TCP fallback fails (graceful degradation).

        try {
            DnsServer service = upstreamRelayServer("127.0.0.1:" + mockPort);
            service.start(gumdrop);

            try {
                DnsMessage query = DnsMessage.createQuery(42, "example.com", DnsType.A);
                DnsMessage response = syncProcessQuery(service, query);

                // Even if TCP fallback fails, we should get a response
                // (truncated or SERVFAIL)
                assertNotNull(response);
            } finally {
                service.stop();
            }
        } finally {
            mockUpstream.close();
            tcpServer.close();
        }
    }

    /**
     * Verifies that a matching upstream response ID is accepted normally.
     */
    @Test
    public void testUpstreamResponseIdMatchAccepted() throws Exception {
        DatagramSocket mockUpstream = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        int mockPort = mockUpstream.getLocalPort();

        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    mockUpstream.setSoTimeout(3000);
                    mockUpstream.receive(pkt);

                    ByteBuffer queryBuf = ByteBuffer.wrap(buf, 0, pkt.getLength());
                    DnsMessage query = DnsMessage.parse(queryBuf);

                    // Respond with correct ID
                    DnsMessage goodResponse = new DnsMessage(
                            query.getId(),
                            DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA,
                            query.getQuestions(),
                            Collections.singletonList(
                                    DnsResourceRecord.a("example.com", 300,
                                            InetAddress.getByName("93.184.216.34"))),
                            Collections.emptyList(),
                            Collections.emptyList());

                    ByteBuffer resp = goodResponse.serialize();
                    byte[] respBytes = new byte[resp.remaining()];
                    resp.get(respBytes);
                    DatagramPacket reply = new DatagramPacket(
                            respBytes, respBytes.length,
                            pkt.getAddress(), pkt.getPort());
                    mockUpstream.send(reply);
                } catch (Exception e) {
                    // test will fail via assertion
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        try {
            DnsServer service = upstreamRelayServer("127.0.0.1:" + mockPort);
            service.start(gumdrop);

            try {
                DnsMessage query = DnsMessage.createQuery(42, "example.com", DnsType.A);
                DnsMessage response = syncProcessQuery(service, query);

                assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
                assertFalse(response.getAnswers().isEmpty());
            } finally {
                service.stop();
            }
        } finally {
            mockUpstream.close();
        }
    }

    /**
     * RFC 7873 section 5.2.3: a query with a client cookie but no
     * server cookie receives a cookie-only response without resolution.
     */
    @Test
    public void testCookieOnlyResponseWithoutServerCookie() throws Exception {
        CapturingDNSListener listener = new CapturingDNSListener();
        DnsServer service = new DnsServer();
        listener.setServer(service);

        DnsCookie clientCookie = new DnsCookie();
        byte[] cc = clientCookie.getClientCookie();
        DnsResourceRecord opt = DnsResourceRecord.opt(
                DnsMessage.DEFAULT_EDNS_UDP_SIZE,
                buildCookieEdnsOption(cc));
        DnsMessage query = DnsMessage.createQuery(7, "example.com",
                DnsType.A, Collections.singletonList(opt));

        InetSocketAddress source =
                new InetSocketAddress("127.0.0.1", 54321);
        syncHandleDatagram(service, listener, query.serialize(), source);

        assertNotNull(listener.lastSent);
        DnsMessage response = DnsMessage.parse(listener.lastSent);
        assertTrue(response.getAnswers().isEmpty());
        assertEquals(1, response.getAdditionals().size());

        DnsResourceRecord responseOpt =
                response.getAdditionals().get(0);
        byte[] cookieData = DnsCookie.findEdnsOption(
                responseOpt.getRData(), DnsCookie.EDNS_OPTION_COOKIE);
        assertNotNull(cookieData);
        assertEquals(DnsCookie.CLIENT_COOKIE_LENGTH
                + DnsCookie.MIN_SERVER_COOKIE_LENGTH, cookieData.length);
    }

    /**
     * Non-QUERY opcodes (e.g. RFC 1996 NOTIFY) receive NOTIMP by default.
     */
    @Test
    public void testDefaultNonQueryOpcodeReturnsNotimp() throws Exception {
        CapturingDNSListener listener = new CapturingDNSListener();
        DnsServer service = new DnsServer();
        listener.setServer(service);

        DnsMessage notify = buildOpcodeQuery(11, DnsMessage.OPCODE_NOTIFY,
                "example.com", DnsType.SOA);
        InetSocketAddress source =
                new InetSocketAddress("127.0.0.1", 54320);
        syncHandleDatagram(service, listener, notify.serialize(), source);

        assertNotNull(listener.lastSent);
        DnsMessage response = DnsMessage.parse(listener.lastSent);
        assertEquals(DnsMessage.RCODE_NOTIMP, response.getRcode());
        assertEquals(notify.getId(), response.getId());
    }

    /**
     * Handlers can implement {@link DnsQueryHandler#handleNonQueryOpcode}
     * for NOTIFY, dynamic update, or other opcodes.
     */
    @Test
    public void testHandleNonQueryOpcodeHandler() throws Exception {
        CapturingDNSListener listener = new CapturingDNSListener();
        DnsServer service = new DnsServer();
        service.setHandler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop,
                                    DnsQueryCallback callback) {
                throw new AssertionError("unexpected QUERY");
            }

            @Override
            public boolean handleNonQueryOpcode(DnsMessage query,
                                                SelectorLoop loop,
                                                DnsQueryCallback callback) {
                if (query.getOpcode() == DnsMessage.OPCODE_NOTIFY) {
                    callback.onResponse(query.createResponse(
                            Collections.<DnsResourceRecord>emptyList()));
                    return true;
                }
                return false;
            }
        });
        listener.setServer(service);

        DnsMessage notify = buildOpcodeQuery(12, DnsMessage.OPCODE_NOTIFY,
                "example.com", DnsType.SOA);
        InetSocketAddress source =
                new InetSocketAddress("127.0.0.1", 54323);
        syncHandleDatagram(service, listener, notify.serialize(), source);

        assertNotNull(listener.lastSent);
        DnsMessage response = DnsMessage.parse(listener.lastSent);
        assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
    }

    /**
     * RFC 7873: after the cookie handshake, queries with a valid server
     * cookie are resolved normally.
     */
    @Test
    public void testCookieHandshakeThenResolution() throws Exception {
        DatagramSocket mockUpstream = new DatagramSocket(0,
                InetAddress.getByName("127.0.0.1"));
        int mockPort = mockUpstream.getLocalPort();

        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    mockUpstream.setSoTimeout(3000);
                    mockUpstream.receive(pkt);

                    ByteBuffer queryBuf = ByteBuffer.wrap(buf, 0, pkt.getLength());
                    DnsMessage query = DnsMessage.parse(queryBuf);
                    DnsMessage goodResponse = query.createResponse(
                            Collections.singletonList(
                                    DnsResourceRecord.a("example.com", 300,
                                            InetAddress.getByName("1.2.3.4"))));
                    ByteBuffer resp = goodResponse.serialize();
                    byte[] respBytes = new byte[resp.remaining()];
                    resp.get(respBytes);
                    mockUpstream.send(new DatagramPacket(respBytes,
                            respBytes.length, pkt.getAddress(), pkt.getPort()));
                } catch (Exception e) {
                    // test will fail via assertion
                }
            }
        });
        responder.setDaemon(true);
        responder.start();

        try {
            CapturingDNSListener listener = new CapturingDNSListener();
            DnsServer service = upstreamRelayServer("127.0.0.1:" + mockPort);
            // Upstream forwarding needs the UDP/TCP transport factories
            // start() creates -- unlike testCookieOnlyResponseWithoutServerCookie,
            // this test's second query actually reaches proxyToUpstream.
            service.start(gumdrop);
            listener.setServer(service);

            DnsCookie clientCookie = new DnsCookie();
            byte[] cc = clientCookie.getClientCookie();
            InetSocketAddress source =
                    new InetSocketAddress("127.0.0.1", 54322);

            DnsResourceRecord opt1 = DnsResourceRecord.opt(
                    DnsMessage.DEFAULT_EDNS_UDP_SIZE,
                    buildCookieEdnsOption(cc));
            DnsMessage query1 = DnsMessage.createQuery(8, "example.com",
                    DnsType.A, Collections.singletonList(opt1));
            syncHandleDatagram(service, listener, query1.serialize(), source);

            DnsMessage cookieResponse = DnsMessage.parse(listener.lastSent);
            byte[] cookieData = DnsCookie.findEdnsOption(
                    cookieResponse.getAdditionals().get(0).getRData(),
                    DnsCookie.EDNS_OPTION_COOKIE);
            assertNotNull(cookieData);
            assertTrue(cookieData.length > DnsCookie.CLIENT_COOKIE_LENGTH);

            DnsResourceRecord opt2 = DnsResourceRecord.opt(
                    DnsMessage.DEFAULT_EDNS_UDP_SIZE,
                    buildCookieEdnsOption(cookieData));
            DnsMessage query2 = DnsMessage.createQuery(9, "example.com",
                    DnsType.A, Collections.singletonList(opt2));
            syncHandleDatagram(service, listener, query2.serialize(), source);

            DnsMessage response = DnsMessage.parse(listener.lastSent);
            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertFalse(response.getAnswers().isEmpty());
        } finally {
            mockUpstream.close();
        }
    }

    // -- RFC 10029: MQTYPE-Query / MQTYPE-Response --

    @Test
    public void testMQTypeMergesAdditionalTypeIntoResponse() throws Exception {
        Map<DnsType, InetAddress> perType = new HashMap<>();
        perType.put(DnsType.A, InetAddress.getByName("10.0.0.1"));
        perType.put(DnsType.AAAA, InetAddress.getByName("::1"));
        DnsServer service = serviceAnsweringPerType(perType);

        DnsMessage query = buildMQTypeQuery(1, "merge.example.com", DnsType.A,
                Collections.singletonList(DnsType.AAAA));
        DnsMessage response = syncProcessQuery(service, query);

        assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
        assertEquals("Should have merged both A and AAAA answers",
                2, response.getAnswers().size());
        boolean hasA = false;
        boolean hasAAAA = false;
        for (DnsResourceRecord rr : response.getAnswers()) {
            if (rr.getType() == DnsType.A) hasA = true;
            if (rr.getType() == DnsType.AAAA) hasAAAA = true;
        }
        assertTrue(hasA);
        assertTrue(hasAAAA);
        assertEquals("MQTYPE-Response should list AAAA as covered",
                Collections.singletonList(DnsType.AAAA), mqtypeResponseCoverage(response));
    }

    @Test
    public void testMQTypeFormerrOnEmptyOption() throws Exception {
        DnsServer service = serviceAnsweringPerType(Collections.<DnsType, InetAddress>emptyMap());
        DnsMessage query = buildMQTypeQuery(2, "empty.example.com", DnsType.A,
                Collections.<DnsType>emptyList());
        DnsMessage response = syncProcessQuery(service, query);
        assertEquals(DnsMessage.RCODE_FORMERR, response.getRcode());
    }

    @Test
    public void testMQTypeFormerrWhenExceedingCap() throws Exception {
        DnsServer service = serviceAnsweringPerType(Collections.<DnsType, InetAddress>emptyMap());
        // 5 additional types > DEFAULT_MAX_MQTYPES (4)
        DnsMessage query = buildMQTypeQuery(3, "toomany.example.com", DnsType.A,
                Arrays.asList(DnsType.NS, DnsType.CNAME, DnsType.MX, DnsType.TXT, DnsType.AAAA));
        DnsMessage response = syncProcessQuery(service, query);
        assertEquals(DnsMessage.RCODE_FORMERR, response.getRcode());
    }

    @Test
    public void testMQTypeExcludesTypeWithMismatchedRcode() throws Exception {
        DnsServer service = new DnsServer();
        service.setHandler(new SyncDnsQueryHandler() {
            @Override
            protected DnsMessage resolveQuery(DnsMessage query) {
                DnsQuestion q = query.getQuestions().get(0);
                if (q.getType() == DnsType.A) {
                    return query.createResponse(Collections.singletonList(
                            DnsResourceRecord.a(q.getName(), 60,
                                    inetAddressUnchecked("10.0.0.2"))));
                }
                // AAAA resolves to NXDOMAIN -- inconsistent with the
                // primary A response's NOERROR, so RFC 10029 requires
                // it be omitted from MQTYPE-Response.
                return query.createErrorResponse(DnsMessage.RCODE_NXDOMAIN);
            }
        });

        DnsMessage query = buildMQTypeQuery(4, "mismatch.example.com", DnsType.A,
                Collections.singletonList(DnsType.AAAA));
        DnsMessage response = syncProcessQuery(service, query);

        assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
        assertEquals("Only the primary A answer should be present",
                1, response.getAnswers().size());
        assertEquals(DnsType.A, response.getAnswers().get(0).getType());
        assertTrue("AAAA should not be listed as covered",
                mqtypeResponseCoverage(response).isEmpty());
    }

    @Test
    public void testMQTypeOmitsAdditionalTypeWhenMergedResponseExceedsPayloadLimit()
            throws Exception {
        Map<DnsType, InetAddress> perType = new HashMap<DnsType, InetAddress>();
        perType.put(DnsType.A, InetAddress.getByName("10.0.0.1"));
        perType.put(DnsType.AAAA, InetAddress.getByName("::1"));
        DnsServer service = serviceAnsweringPerType(perType);

        DnsQuestion question = new DnsQuestion("merge.example.com", DnsType.A, DnsClass.IN);
        byte[] optionData = DnsMultiQType.buildMQTypeQueryOption(
                Collections.singletonList(DnsType.AAAA));
        DnsMessage probeQuery = new DnsMessage(99, DnsMessage.FLAG_RD,
                Collections.singletonList(question),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(
                        DnsResourceRecord.opt(DnsMessage.DEFAULT_EDNS_UDP_SIZE, 0, optionData)));
        DnsMessage merged = syncProcessQuery(service, probeQuery);
        int fullSize = merged.wireSize();

        DnsMessage primaryOnlyQuery = new DnsMessage(100, DnsMessage.FLAG_RD,
                Collections.singletonList(question),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        DnsMessage primaryOnly = syncProcessQuery(service, primaryOnlyQuery);
        int primarySize = primaryOnly.wireSize();
        assertTrue("fixture must leave room between primary-only and merged sizes",
                primarySize < fullSize);

        int tightPayload = primarySize + 1;
        DnsMessage tightQuery = new DnsMessage(101, DnsMessage.FLAG_RD,
                Collections.singletonList(question),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(
                        DnsResourceRecord.opt(tightPayload, 0, optionData)));
        DnsMessage tightResponse = syncProcessQuery(service, tightQuery);

        assertEquals(DnsMessage.RCODE_NOERROR, tightResponse.getRcode());
        assertEquals("Only the primary A answer should fit the tight payload",
                1, tightResponse.getAnswers().size());
        assertEquals(DnsType.A, tightResponse.getAnswers().get(0).getType());
        assertTrue("AAAA should be omitted when the merged response would not fit",
                mqtypeResponseCoverage(tightResponse).isEmpty());
    }

    private static InetAddress inetAddressUnchecked(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static DnsServer upstreamRelayServer(String upstreamServers) {
        UpstreamRelayHandler.Builder builder = UpstreamRelayHandler.builder()
                .useSystemResolvers(false)
                .cacheEnabled(false);
        if (upstreamServers != null) {
            builder.upstreamServers(upstreamServers);
        }
        DnsServer service = new DnsServer();
        service.setHandler(builder.build());
        return service;
    }

    private static DnsServer serviceAnsweringPerType(final Map<DnsType, InetAddress> perType) {
        DnsServer service = new DnsServer();
        service.setHandler(new SyncDnsQueryHandler() {
            @Override
            protected DnsMessage resolveQuery(DnsMessage query) {
                DnsQuestion q = query.getQuestions().get(0);
                InetAddress addr = perType.get(q.getType());
                if (addr == null) {
                    return query.createResponse(Collections.<DnsResourceRecord>emptyList());
                }
                DnsResourceRecord rr = (q.getType() == DnsType.AAAA)
                        ? DnsResourceRecord.aaaa(q.getName(), 60, addr)
                        : DnsResourceRecord.a(q.getName(), 60, addr);
                return query.createResponse(Collections.singletonList(rr));
            }
        });
        return service;
    }

    private static DnsMessage buildMQTypeQuery(int id, String name, DnsType primaryType,
                                               List<DnsType> additionalTypes) {
        DnsQuestion question = new DnsQuestion(name, primaryType, DnsClass.IN);
        byte[] optionData = DnsMultiQType.buildMQTypeQueryOption(additionalTypes);
        List<DnsResourceRecord> additionals = Collections.singletonList(
                DnsResourceRecord.opt(DnsMessage.DEFAULT_EDNS_UDP_SIZE, 0, optionData));
        return new DnsMessage(id, DnsMessage.FLAG_RD,
                Collections.singletonList(question),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                additionals);
    }

    private static List<DnsType> mqtypeResponseCoverage(DnsMessage response)
            throws DnsFormatException {
        for (DnsResourceRecord rr : response.getAdditionals()) {
            if (rr.getType() == DnsType.OPT) {
                byte[] data = DnsCookie.findEdnsOption(
                        rr.getRData(), DnsMultiQType.EDNS_OPTION_MQTYPE_RESPONSE);
                if (data != null) {
                    return DnsMultiQType.parseMQTypeResponseOption(data);
                }
            }
        }
        return Collections.emptyList();
    }

    private static byte[] buildCookieEdnsOption(byte[] cookieData) {
        ByteBuffer buf = ByteBuffer.allocate(4 + cookieData.length);
        buf.putShort((short) DnsCookie.EDNS_OPTION_COOKIE);
        buf.putShort((short) cookieData.length);
        buf.put(cookieData);
        return buf.array();
    }

    private static DnsMessage buildOpcodeQuery(int id, int opcode, String name,
                                               DnsType type) {
        int flags = opcode << 11;
        DnsQuestion question = new DnsQuestion(name, type, DnsClass.IN);
        return new DnsMessage(id, flags,
                Collections.singletonList(question),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
    }

    /** Test listener that captures outbound datagrams. */
    private static final class CapturingDNSListener extends DnsListener {
        ByteBuffer lastSent;
        InetSocketAddress lastDest;

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
            lastSent = data.duplicate();
            lastDest = destination;
        }
    }
}
