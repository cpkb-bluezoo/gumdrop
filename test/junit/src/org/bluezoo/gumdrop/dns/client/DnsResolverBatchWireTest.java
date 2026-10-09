/*
 * DnsResolverBatchWireTest.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsCookie;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsMultiQType;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import static org.junit.Assert.*;

/**
 * {@link DnsResolver#queryBatch} over a mock transport: RFC 10029 multiple
 * QTYPE coverage reported by the server, servers that ignore or mangle the
 * option, servers already known not to support it, and whole-exchange
 * failure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsResolverBatchWireTest {

    private static final class Mock implements DnsClientTransport {
        final List<ByteBuffer> sent = new ArrayList<ByteBuffer>();
        final List<Runnable> timers = new ArrayList<Runnable>();
        DnsClientTransportHandler handler;

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DnsClientTransportHandler handler) throws IOException {
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            sent.add(copy);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers.add(callback);
            return new TimerHandle() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
        }

        @Override
        public void close() {
        }

        DnsMessage queryAt(int index) throws Exception {
            return DnsMessage.parse(sent.get(index).duplicate());
        }
    }

    /** Records every callback of a batch. */
    private static final class Batch implements BatchQueryCallback {
        final List<DnsType> results = new ArrayList<DnsType>();
        final List<DnsType> failures = new ArrayList<DnsType>();
        int completes;

        @Override
        public void onResult(DnsType type, List<DnsResourceRecord> records) {
            results.add(type);
        }

        @Override
        public void onTypeError(DnsType type, String error) {
            failures.add(type);
        }

        @Override
        public void onComplete() {
            completes++;
        }
    }

    private DnsCache originalCache;
    private Mock transport;
    private DnsResolver resolver;

    @Before
    public void setUp() throws Exception {
        originalCache = DnsResolver.getCache();
        DnsResolver.setCache(new DnsCache());
        DnsMultiQTypeCache.clear();
        transport = new Mock();
        resolver = new DnsResolver();
        resolver.transport(transport);
        resolver.addServer("127.0.0.1");
        resolver.open();
    }

    @After
    public void tearDown() {
        resolver.close();
        DnsMultiQTypeCache.clear();
        DnsResolver.setCache(originalCache);
    }

    private static DnsResourceRecord a(String name) throws Exception {
        return DnsResourceRecord.a(name, 300, InetAddress.getByAddress(new byte[] {10, 0, 0, 1}));
    }

    private static DnsResourceRecord aaaa(String name) throws Exception {
        return DnsResourceRecord.aaaa(name, 300, InetAddress.getByName("2001:db8::1"));
    }

    private static DnsMessage reply(DnsMessage query, List<DnsResourceRecord> answers,
                                    List<DnsResourceRecord> additionals) {
        return new DnsMessage(query.getId(),
                DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA,
                query.getQuestions(), answers, Collections.<DnsResourceRecord>emptyList(),
                additionals);
    }

    private static List<DnsResourceRecord> list(DnsResourceRecord... records) {
        return new ArrayList<DnsResourceRecord>(Arrays.asList(records));
    }

    private void answer(int index, List<DnsResourceRecord> answers,
                        List<DnsResourceRecord> additionals) throws Exception {
        DnsMessage query = transport.queryAt(index);
        transport.handler.onReceive(reply(query, answers, additionals).serialize());
    }

    private static List<DnsType> types(DnsType... t) {
        return new ArrayList<DnsType>(Arrays.asList(t));
    }

    @Test
    public void singleTypeBatchNeedsNoOption() throws Exception {
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A), batch);
        assertEquals(1, transport.sent.size());
        answer(0, list(a("www.example.test")), Collections.<DnsResourceRecord>emptyList());
        assertEquals(Arrays.asList(DnsType.A), batch.results);
        assertEquals(1, batch.completes);
    }

    @Test
    public void serverCoveringTheAdditionalTypeAnswersInOneExchange() throws Exception {
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A, DnsType.AAAA), batch);
        assertEquals(1, transport.sent.size());
        byte[] option = DnsMultiQType.buildMQTypeResponseOption(types(DnsType.AAAA));
        List<DnsResourceRecord> additionals = list(DnsResourceRecord.opt(1232, option));
        answer(0, list(a("www.example.test"), aaaa("www.example.test")), additionals);
        assertEquals(1, transport.sent.size());
        assertTrue(batch.results.contains(DnsType.A));
        assertTrue(batch.results.contains(DnsType.AAAA));
        assertEquals(1, batch.completes);
    }

    @Test
    public void responseOptionWithoutMqtypeMarksServerUnsupportedAndRequeriesStandalone()
            throws Exception {
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A, DnsType.AAAA), batch);
        List<DnsResourceRecord> additionals = list(DnsResourceRecord.opt(1232));
        answer(0, list(a("www.example.test")), additionals);
        assertEquals("the uncovered type is queried on its own", 2, transport.sent.size());
        assertEquals(DnsType.AAAA, transport.queryAt(1).getQuestions().get(0).getType());
        answer(1, list(aaaa("www.example.test")), Collections.<DnsResourceRecord>emptyList());
        assertEquals(1, batch.completes);
        assertEquals(2, batch.results.size());

        // the server is now known not to support the option: no option is sent
        Batch second = new Batch();
        resolver.queryBatch("other.example.test", types(DnsType.A, DnsType.AAAA), second);
        DnsMessage firstQuery = transport.queryAt(2);
        assertTrue(firstQuery.getAdditionals().isEmpty()
                || DnsCookie.findEdnsOption(firstQuery.getAdditionals().get(0).getRData(),
                        DnsMultiQType.EDNS_OPTION_MQTYPE_QUERY) == null);
        answer(2, list(a("other.example.test")), Collections.<DnsResourceRecord>emptyList());
        assertEquals("the additional type is requested independently", 4, transport.sent.size());
        answer(3, list(aaaa("other.example.test")), Collections.<DnsResourceRecord>emptyList());
        assertEquals(1, second.completes);
    }

    @Test
    public void responseWithoutAnyOptMarksServerUnsupported() throws Exception {
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A, DnsType.AAAA), batch);
        answer(0, list(a("www.example.test")), Collections.<DnsResourceRecord>emptyList());
        assertEquals(2, transport.sent.size());
        assertTrue(DnsMultiQTypeCache.isKnownUnsupported(
                new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53)));
    }

    @Test
    public void malformedMqtypeResponseCoversNothing() throws Exception {
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A, DnsType.AAAA), batch);
        // option 21 with an odd-length payload cannot be parsed
        byte[] bad = new byte[] {0, 21, 0, 1, 7};
        answer(0, list(a("www.example.test")), list(DnsResourceRecord.opt(1232, bad)));
        assertEquals(2, transport.sent.size());
        answer(1, list(aaaa("www.example.test")), Collections.<DnsResourceRecord>emptyList());
        assertEquals(1, batch.completes);
    }

    @Test
    public void wholeExchangeFailureFailsEveryRidingType() throws Exception {
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A, DnsType.AAAA, DnsType.MX), batch);
        assertEquals(1, transport.sent.size());
        transport.timers.get(0).run();
        assertEquals(3, batch.failures.size());
        assertEquals(1, batch.completes);
        assertEquals(1, transport.sent.size());
    }

    @Test
    public void primaryFailureWithKnownUnsupportedServerStillTriesTheRest() throws Exception {
        DnsMultiQTypeCache.markUnsupported(
                new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53));
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A, DnsType.AAAA), batch);
        transport.timers.get(0).run();
        assertEquals(Arrays.asList(DnsType.A), batch.failures);
        assertEquals("the additional type goes out on its own", 2, transport.sent.size());
        answer(1, list(aaaa("www.example.test")), Collections.<DnsResourceRecord>emptyList());
        assertEquals(Arrays.asList(DnsType.AAAA), batch.results);
        assertEquals(1, batch.completes);
    }

    @Test
    public void standaloneFailureIsReportedPerType() throws Exception {
        DnsMultiQTypeCache.markUnsupported(
                new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53));
        Batch batch = new Batch();
        resolver.queryBatch("www.example.test", types(DnsType.A, DnsType.AAAA), batch);
        answer(0, list(a("www.example.test")), Collections.<DnsResourceRecord>emptyList());
        transport.timers.get(1).run();
        assertEquals(Arrays.asList(DnsType.A), batch.results);
        assertEquals(Arrays.asList(DnsType.AAAA), batch.failures);
        assertEquals(1, batch.completes);
    }
}
