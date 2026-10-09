/*
 * UpstreamRelayHandlerTransportTest.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpEndpoint;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.UdpEndpoint;
import org.bluezoo.gumdrop.UdpTransportFactory;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsServerMetrics;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link UpstreamRelayHandler} upstream attempts over UDP with timeout and
 * fallback, and the TCP retry after a truncated answer, driven through mock
 * transport factories that hand the handler recording endpoints. No sockets
 * are opened and timers are fired explicitly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UpstreamRelayHandlerTransportTest {

    /** UDP factory mock: each connect delivers a recording endpoint. */
    private static final class MockUdpFactory extends UdpTransportFactory {
        final List<ProtocolHandler> handlers = new ArrayList<ProtocolHandler>();
        final List<BinaryRecordingEndpoint> endpoints = new ArrayList<BinaryRecordingEndpoint>();
        final List<Integer> ports = new ArrayList<Integer>();
        int failConnectAt = -1;

        @Override
        public UdpEndpoint connect(Gumdrop gumdrop, InetAddress host, int port,
                ProtocolHandler handler, SelectorLoop loop) throws IOException {
            if (handlers.size() == failConnectAt) {
                handlers.add(handler);
                endpoints.add(null);
                throw new IOException("no route");
            }
            BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
            handlers.add(handler);
            endpoints.add(ep);
            ports.add(Integer.valueOf(port));
            handler.connected(ep);
            return null;
        }
    }

    /** TCP factory mock: each connect delivers a recording endpoint. */
    private static final class MockTcpFactory extends TcpTransportFactory {
        final List<ProtocolHandler> handlers = new ArrayList<ProtocolHandler>();
        final List<BinaryRecordingEndpoint> endpoints = new ArrayList<BinaryRecordingEndpoint>();
        boolean failConnect;

        @Override
        public TcpEndpoint connect(Gumdrop gumdrop, InetAddress host, int port,
                ProtocolHandler handler, SelectorLoop loop) throws IOException {
            if (failConnect) {
                throw new IOException("refused");
            }
            BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
            handlers.add(handler);
            endpoints.add(ep);
            handler.connected(ep);
            return null;
        }
    }

    /** Captures the handler's outcome. */
    private static final class Capture implements DnsQueryCallback {
        DnsMessage response;
        int calls;

        @Override
        public void onResponse(DnsMessage message) {
            response = message;
            calls++;
        }

        @Override
        public void onError(String error) {
            fail(error);
        }
    }

    private UpstreamRelayHandler handler;
    private MockUdpFactory udp;
    private MockTcpFactory tcp;
    private final SelectorLoop loop = new InlineSelectorLoop();

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Before
    public void setUp() throws Exception {
        handler = UpstreamRelayHandler.builder().useSystemResolvers(false).build();
        udp = new MockUdpFactory();
        tcp = new MockTcpFactory();
        set(handler, "upstreamUdpFactory", udp);
        set(handler, "upstreamTcpFactory", tcp);
    }

    private Capture ask(String name) {
        Capture c = new Capture();
        handler.handleQuery(DnsMessage.createQuery(21, name, DnsType.A), loop, c);
        return c;
    }

    private static DnsMessage sentQuery(BinaryRecordingEndpoint ep) throws Exception {
        byte[] bytes = ep.getAllBytes();
        return DnsMessage.parse(ByteBuffer.wrap(bytes));
    }

    private static DnsMessage answerTo(DnsMessage query, int flags) throws Exception {
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        answers.add(DnsResourceRecord.a("www.example.com", 120, InetAddress.getByName("192.0.2.7")));
        DnsMessage base = query.createResponse(answers);
        return new DnsMessage(base.getId(), base.getFlags() | flags, base.getQuestions(),
                base.getAnswers(), base.getAuthorities(), base.getAdditionals());
    }

    private static void deliver(ProtocolHandler h, DnsMessage message) {
        h.receive(message.serialize());
    }

    private static byte[] frame(DnsMessage message) {
        ByteBuffer wire = message.serialize();
        byte[] body = new byte[wire.remaining()];
        wire.get(body);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) ((body.length >> 8) & 0xFF);
        out[1] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    private static void feed(ProtocolHandler h, byte[] data, int chunk) {
        int pos = 0;
        while (pos < data.length) {
            int n = Math.min(chunk, data.length - pos);
            h.receive(ByteBuffer.wrap(data, pos, n));
            pos += n;
        }
    }

    @Test
    public void testUdpAnswerRestoresClientIdAndRecordsMetrics() throws Exception {
        TelemetryConfig tc = new TelemetryConfig();
        tc.metricsEnabled(true);
        handler.setMetrics(new DnsServerMetrics(tc));
        handler.setUpstreamServers("192.0.2.1:5300");
        Capture c = ask("www.example.com");
        assertEquals(1, udp.handlers.size());
        assertEquals(Integer.valueOf(5300), udp.ports.get(0));
        BinaryRecordingEndpoint ep = udp.endpoints.get(0);
        DnsMessage sent = sentQuery(ep);
        assertEquals(DnsType.A, sent.getQuestions().get(0).getType());
        assertTrue("an OPT record is added to the upstream query", sent.getAdditionals().size() == 1);
        deliver(udp.handlers.get(0), answerTo(sent, 0));
        assertEquals(1, c.calls);
        assertEquals(21, c.response.getId());
        assertEquals(1, c.response.getAnswers().size());
        assertEquals(1, ep.getCloseCount());
        assertTrue(ep.getTimers().get(0).isCancelled());
        // a late duplicate is ignored
        deliver(udp.handlers.get(0), answerTo(sent, 0));
        assertEquals(1, c.calls);
    }

    @Test
    public void testExistingOptIsKeptAndDnssecSetsDoBit() throws Exception {
        handler.setUpstreamServers("192.0.2.1");
        handler.setDnssecEnabled(true);
        List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
        adds.add(DnsResourceRecord.opt(1400));
        Capture c = new Capture();
        handler.handleQuery(DnsMessage.createQuery(5, "www.example.com", DnsType.A, adds), loop, c);
        DnsMessage sent = sentQuery(udp.endpoints.get(0));
        assertEquals(1, sent.getAdditionals().size());
        assertEquals(1400, sent.getAdditionals().get(0).getUdpPayloadSize());
        // with no client OPT and DNSSEC on, the forwarded OPT carries DO
        handler.handleQuery(DnsMessage.createQuery(6, "www.example.com", DnsType.A), loop, new Capture());
        DnsMessage sent2 = sentQuery(udp.endpoints.get(1));
        assertTrue(sent2.hasDO());
    }

    @Test
    public void testTimeoutThenExhaustionIsServfail() throws Exception {
        handler.setUpstreamServers("192.0.2.1 192.0.2.2");
        Capture c = ask("www.example.com");
        assertEquals(1, udp.handlers.size());
        udp.endpoints.get(0).fireTimers();
        assertEquals(2, udp.handlers.size());
        assertEquals(0, c.calls);
        udp.endpoints.get(1).fireTimers();
        assertEquals(1, c.calls);
        assertEquals(DnsMessage.RCODE_SERVFAIL, c.response.getRcode());
        assertEquals(1, udp.endpoints.get(0).getCloseCount());
    }

    @Test
    public void testBadUpstreamResponsesFallThroughToNextServer() throws Exception {
        handler.setUpstreamServers("192.0.2.1 192.0.2.2 192.0.2.3 192.0.2.4 192.0.2.5");
        TelemetryConfig tc = new TelemetryConfig();
        tc.metricsEnabled(true);
        handler.setMetrics(new DnsServerMetrics(tc));
        Capture c = ask("www.example.com");

        // 1: malformed datagram
        udp.handlers.get(0).receive(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(2, udp.handlers.size());
        // 2: wrong transaction id
        DnsMessage sent = sentQuery(udp.endpoints.get(1));
        DnsMessage wrongId = new DnsMessage((sent.getId() + 1) & 0xFFFF, DnsMessage.FLAG_QR,
                sent.getQuestions(), Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        deliver(udp.handlers.get(1), wrongId);
        assertEquals(3, udp.handlers.size());
        // 3: not a response (QR clear)
        deliver(udp.handlers.get(2), sentQuery(udp.endpoints.get(2)));
        assertEquals(4, udp.handlers.size());
        // 4: question does not match
        DnsMessage sent4 = sentQuery(udp.endpoints.get(3));
        List<DnsQuestion> other = Collections.singletonList(new DnsQuestion("evil.example.net",
                DnsType.A, sent4.getQuestions().get(0).getDNSClass()));
        DnsMessage wrongQuestion = new DnsMessage(sent4.getId(), DnsMessage.FLAG_QR, other,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        deliver(udp.handlers.get(3), wrongQuestion);
        assertEquals(5, udp.handlers.size());
        assertEquals(0, c.calls);
        // 5: a good answer
        deliver(udp.handlers.get(4), answerTo(sentQuery(udp.endpoints.get(4)), 0));
        assertEquals(1, c.calls);
        assertEquals(1, c.response.getAnswers().size());
        assertEquals(DnsMessage.RCODE_NOERROR, c.response.getRcode());
    }

    @Test
    public void testDisconnectErrorAndConnectFailureAdvance() throws Exception {
        handler.setUpstreamServers("192.0.2.1 192.0.2.2 192.0.2.3 192.0.2.4");
        udp.failConnectAt = 2;
        Capture c = ask("www.example.com");
        udp.handlers.get(0).disconnected();
        assertEquals(2, udp.handlers.size());
        udp.handlers.get(1).error(new IOException("icmp unreachable"));
        // the third connect throws, the fourth server answers
        assertEquals(4, udp.handlers.size());
        udp.handlers.get(3).securityEstablished(null);
        deliver(udp.handlers.get(3), answerTo(sentQuery(udp.endpoints.get(3)), 0));
        assertEquals(1, c.calls);
        assertEquals(1, c.response.getAnswers().size());
    }

    @Test
    public void testTruncatedAnswerIsRetriedOverTcpInSmallChunks() throws Exception {
        handler.setUpstreamServers("192.0.2.1");
        Capture c = ask("www.example.com");
        DnsMessage sent = sentQuery(udp.endpoints.get(0));
        deliver(udp.handlers.get(0), answerTo(sent, DnsMessage.FLAG_TC));
        assertEquals(0, c.calls);
        assertEquals(1, tcp.handlers.size());
        byte[] request = tcp.endpoints.get(0).getAllBytes();
        assertEquals(request.length - 2, ((request[0] & 0xFF) << 8) | (request[1] & 0xFF));

        List<DnsResourceRecord> many = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < 30; i++) {
            many.add(DnsResourceRecord.txt("www.example.com", 60,
                    "a-long-enough-text-value-to-grow-the-buffer-" + i));
        }
        DnsMessage full = sent.createResponse(many);
        feed(tcp.handlers.get(0), frame(full), 7);
        assertEquals(1, c.calls);
        assertEquals(30, c.response.getAnswers().size());
        assertEquals(21, c.response.getId());
        assertEquals(1, tcp.endpoints.get(0).getCloseCount());
        // further data after completion is ignored
        tcp.handlers.get(0).receive(ByteBuffer.wrap(new byte[] {0, 1}));
        assertEquals(1, c.calls);
        tcp.handlers.get(0).securityEstablished(null);
    }

    private Capture startTruncated() throws Exception {
        handler.setUpstreamServers("192.0.2.1");
        Capture c = ask("www.example.com");
        DnsMessage sent = sentQuery(udp.endpoints.get(0));
        deliver(udp.handlers.get(0), answerTo(sent, DnsMessage.FLAG_TC));
        return c;
    }

    private void assertFellBackToTruncated(Capture c) {
        assertEquals(1, c.calls);
        assertTrue(c.response.isTruncated());
        assertEquals(21, c.response.getId());
    }

    @Test
    public void testTcpRetryInvalidLengthFallsBackToUdpAnswer() throws Exception {
        Capture c = startTruncated();
        tcp.handlers.get(0).receive(ByteBuffer.wrap(new byte[] {0, 0}));
        assertFellBackToTruncated(c);
    }

    @Test
    public void testTcpRetryMismatchedIdFallsBack() throws Exception {
        Capture c = startTruncated();
        DnsMessage sent = sentQuery(udp.endpoints.get(0));
        DnsMessage wrong = new DnsMessage((sent.getId() + 7) & 0xFFFF, DnsMessage.FLAG_QR,
                sent.getQuestions(), Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        tcp.handlers.get(0).receive(ByteBuffer.wrap(frame(wrong)));
        assertFellBackToTruncated(c);
    }

    @Test
    public void testTcpRetryMalformedAnswerFallsBack() throws Exception {
        Capture c = startTruncated();
        tcp.handlers.get(0).receive(ByteBuffer.wrap(new byte[] {0, 3, 9, 9, 9}));
        assertFellBackToTruncated(c);
    }

    @Test
    public void testTcpRetryTimeoutFallsBack() throws Exception {
        Capture c = startTruncated();
        tcp.endpoints.get(0).fireTimers();
        assertFellBackToTruncated(c);
    }

    @Test
    public void testTcpRetryDisconnectFallsBack() throws Exception {
        Capture c = startTruncated();
        tcp.handlers.get(0).disconnected();
        assertFellBackToTruncated(c);
    }

    @Test
    public void testTcpRetryErrorFallsBack() throws Exception {
        Capture c = startTruncated();
        tcp.handlers.get(0).error(new IOException("reset"));
        assertFellBackToTruncated(c);
        assertTrue(tcp.endpoints.get(0).getTimers().get(0).isCancelled());
    }

    @Test
    public void testTcpRetryConnectFailureFallsBack() throws Exception {
        tcp.failConnect = true;
        Capture c = startTruncated();
        assertFellBackToTruncated(c);
    }
}
