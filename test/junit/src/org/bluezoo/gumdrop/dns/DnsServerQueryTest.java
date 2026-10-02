/*
 * DnsServerQueryTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link DnsServer#handleDatagram}: query dispatch, cookies, RFC 10029
 * multiple QTYPE merging and error handling, using a recording listener.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsServerQueryTest {

    /** A listener that records the datagrams it is asked to send. */
    private static final class RecordingListener extends DnsListener {
        final List<ByteBuffer> sent = new ArrayList<ByteBuffer>();

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
            sent.add(data);
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }
    }

    /** A handler answering from a per-type table. */
    private static final class TableHandler implements DnsQueryHandler {
        final Map<DnsType, DnsMessage> byType = new HashMap<DnsType, DnsMessage>();
        boolean failAll;
        boolean nonQueryHandled;
        boolean nonQueryError;

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback callback) {
            if (failAll) {
                callback.onError("boom");
                return;
            }
            DnsType type = query.getQuestions().get(0).getType();
            DnsMessage canned = byType.get(type);
            if (canned == null) {
                callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_NXDOMAIN));
                return;
            }
            callback.onResponse(query.createResponse(canned.getAnswers()));
        }

        @Override
        public boolean handleNonQueryOpcode(DnsMessage query, SelectorLoop loop,
                                            DnsQueryCallback callback) {
            if (nonQueryError) {
                callback.onError("nope");
                return true;
            }
            if (nonQueryHandled) {
                callback.onResponse(query.createResponse(Collections.<DnsResourceRecord>emptyList()));
                return true;
            }
            return false;
        }
    }

    private RecordingListener listener;
    private DnsServer server;
    private TableHandler handler;
    private int completed;
    private InetSocketAddress source;

    private final Runnable done = new Runnable() {
        @Override
        public void run() {
            completed++;
        }
    };

    @Before
    public void setUp() throws Exception {
        listener = new RecordingListener();
        server = new DnsServer();
        handler = new TableHandler();
        server.setHandler(handler);
        source = new InetSocketAddress(InetAddress.getByName("192.0.2.50"), 5353);
    }

    private static DnsMessage answerOf(DnsType type, String name, String addr) throws Exception {
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        if (type == DnsType.A) {
            answers.add(DnsResourceRecord.a(name, 60, InetAddress.getByName(addr)));
        } else {
            answers.add(DnsResourceRecord.aaaa(name, 60, InetAddress.getByName(addr)));
        }
        return new DnsMessage(0, 0, Collections.<DnsQuestion>emptyList(), answers,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
    }

    private void send(DnsMessage query) {
        server.handleDatagram(listener, query.serialize(), source, done);
    }

    private DnsMessage lastResponse() throws Exception {
        assertFalse(listener.sent.isEmpty());
        ByteBuffer data = listener.sent.get(listener.sent.size() - 1);
        return DnsMessage.parse(data);
    }

    private static byte[] cookieOption(byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(4 + data.length);
        buf.putShort((short) DnsCookie.EDNS_OPTION_COOKIE);
        buf.putShort((short) data.length);
        buf.put(data);
        return buf.array();
    }

    private static DnsMessage queryWithOpt(int id, DnsType type, byte[] optionBytes) {
        List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
        adds.add(DnsResourceRecord.opt(4096, optionBytes));
        return DnsMessage.createQuery(id, "www.example.com.", type, adds);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void testStandardQueryAnswered() throws Exception {
        handler.byType.put(DnsType.A, answerOf(DnsType.A, "www.example.com.", "192.0.2.1"));
        send(DnsMessage.createQuery(7, "www.example.com.", DnsType.A));
        DnsMessage r = lastResponse();
        assertEquals(7, r.getId());
        assertEquals(1, r.getAnswers().size());
        assertEquals(1, completed);
    }

    @Test
    public void testMalformedDatagramCompletesWithoutResponse() {
        server.handleDatagram(listener, ByteBuffer.wrap(new byte[] {1, 2, 3}), source, done);
        assertTrue(listener.sent.isEmpty());
        assertEquals(1, completed);
    }

    @Test
    public void testEmptyQuestionSectionIsFormerr() throws Exception {
        DnsMessage empty = new DnsMessage(9, DnsMessage.FLAG_RD,
                Collections.<DnsQuestion>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        send(empty);
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
        assertEquals(1, completed);
    }

    @Test
    public void testHandlerErrorIsServfail() throws Exception {
        handler.failAll = true;
        send(DnsMessage.createQuery(3, "www.example.com.", DnsType.A));
        assertEquals(DnsMessage.RCODE_SERVFAIL, lastResponse().getRcode());
        assertEquals(1, completed);
    }

    @Test
    public void testNonQueryOpcodeUnhandledIsNotimp() throws Exception {
        DnsMessage notify = DnsMessage.createNotify(5, "example.com.");
        send(notify);
        assertEquals(DnsMessage.RCODE_NOTIMP, lastResponse().getRcode());
        assertEquals(1, completed);
    }

    @Test
    public void testNonQueryOpcodeHandled() throws Exception {
        handler.nonQueryHandled = true;
        send(DnsMessage.createNotify(5, "example.com."));
        assertEquals(DnsMessage.RCODE_NOERROR, lastResponse().getRcode());
        assertEquals(1, completed);
    }

    @Test
    public void testNonQueryOpcodeErrorIsServfail() throws Exception {
        handler.nonQueryError = true;
        send(DnsMessage.createNotify(5, "example.com."));
        assertEquals(DnsMessage.RCODE_SERVFAIL, lastResponse().getRcode());
        assertEquals(1, completed);
    }

    @Test
    public void testShortCookieIsFormerr() throws Exception {
        send(queryWithOpt(1, DnsType.A, cookieOption(new byte[4])));
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
    }

    @Test
    public void testClientOnlyCookieGetsCookieOnlyResponseThenFullCookieWorks() throws Exception {
        handler.byType.put(DnsType.A, answerOf(DnsType.A, "www.example.com.", "192.0.2.1"));
        byte[] client = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        send(queryWithOpt(1, DnsType.A, cookieOption(client)));
        DnsMessage first = lastResponse();
        assertTrue(first.getAnswers().isEmpty());
        byte[] serverCookie = null;
        for (int i = 0; i < first.getAdditionals().size(); i++) {
            DnsResourceRecord rr = first.getAdditionals().get(i);
            if (rr.getType() == DnsType.OPT) {
                byte[] opt = DnsCookie.findEdnsOption(rr.getRData(), DnsCookie.EDNS_OPTION_COOKIE);
                assertNotNull(opt);
                assertTrue(opt.length > 8);
                serverCookie = new byte[opt.length - 8];
                System.arraycopy(opt, 8, serverCookie, 0, serverCookie.length);
            }
        }
        assertNotNull(serverCookie);

        // a valid server cookie lets the query be resolved and echoes the cookie
        send(queryWithOpt(2, DnsType.A, cookieOption(concat(client, serverCookie))));
        DnsMessage second = lastResponse();
        assertEquals(1, second.getAnswers().size());
        boolean hasCookie = false;
        for (int i = 0; i < second.getAdditionals().size(); i++) {
            DnsResourceRecord rr = second.getAdditionals().get(i);
            if (rr.getType() == DnsType.OPT
                    && DnsCookie.findEdnsOption(rr.getRData(), DnsCookie.EDNS_OPTION_COOKIE) != null) {
                hasCookie = true;
            }
        }
        assertTrue(hasCookie);

        // a forged server cookie falls back to a cookie-only response
        byte[] forged = new byte[16];
        send(queryWithOpt(3, DnsType.A, cookieOption(concat(client, forged))));
        assertTrue(lastResponse().getAnswers().isEmpty());
    }

    private static DnsMessage mq(int id, DnsType primary, DnsType... extra) {
        List<DnsType> types = new ArrayList<DnsType>();
        for (int i = 0; i < extra.length; i++) {
            types.add(extra[i]);
        }
        return queryWithOpt(id, primary, DnsMultiQType.buildMQTypeQueryOption(types));
    }

    @Test
    public void testMqtypeMergesAdditionalType() throws Exception {
        handler.byType.put(DnsType.A, answerOf(DnsType.A, "www.example.com.", "192.0.2.1"));
        handler.byType.put(DnsType.AAAA, answerOf(DnsType.AAAA, "www.example.com.", "2001:db8::1"));
        send(mq(1, DnsType.A, DnsType.AAAA));
        DnsMessage r = lastResponse();
        assertEquals(2, r.getAnswers().size());
        boolean found = false;
        for (int i = 0; i < r.getAdditionals().size(); i++) {
            DnsResourceRecord rr = r.getAdditionals().get(i);
            if (rr.getType() == DnsType.OPT) {
                byte[] opt = DnsCookie.findEdnsOption(rr.getRData(),
                        DnsMultiQType.EDNS_OPTION_MQTYPE_RESPONSE);
                if (opt != null) {
                    List<DnsType> covered = DnsMultiQType.parseMQTypeResponseOption(opt);
                    assertEquals(Collections.singletonList(DnsType.AAAA), covered);
                    found = true;
                }
            }
        }
        assertTrue(found);
    }

    @Test
    public void testMqtypeMismatchedRcodeIsNotCovered() throws Exception {
        handler.byType.put(DnsType.A, answerOf(DnsType.A, "www.example.com.", "192.0.2.1"));
        // no AAAA entry: the table answers NXDOMAIN, which differs from NOERROR
        send(mq(1, DnsType.A, DnsType.AAAA));
        DnsMessage r = lastResponse();
        assertEquals(1, r.getAnswers().size());
    }

    @Test
    public void testMqtypeSubHandlerErrorIsNotCovered() throws Exception {
        final DnsMessage aAnswer = answerOf(DnsType.A, "www.example.com.", "192.0.2.1");
        server.setHandler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback cb) {
                if (query.getQuestions().get(0).getType() == DnsType.A) {
                    cb.onResponse(query.createResponse(aAnswer.getAnswers()));
                } else {
                    cb.onError("down");
                }
            }
        });
        send(mq(1, DnsType.A, DnsType.AAAA));
        assertEquals(1, lastResponse().getAnswers().size());
    }

    @Test
    public void testMqtypeInvalidRequestsAreFormerr() throws Exception {
        handler.byType.put(DnsType.A, answerOf(DnsType.A, "www.example.com.", "192.0.2.1"));
        // odd-length option data
        send(queryWithOpt(1, DnsType.A, new byte[] {0, 20, 0, 3, 0, 28, 0}));
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
        // ANY is not allowed
        send(mq(2, DnsType.A, DnsType.ANY));
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
        // primary type repeated
        send(mq(3, DnsType.A, DnsType.A));
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
        // duplicates
        send(mq(4, DnsType.A, DnsType.AAAA, DnsType.AAAA));
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
        // empty list
        send(mq(5, DnsType.A));
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
        // over the configured cap
        server.setMaxMQTypes(1);
        send(mq(6, DnsType.A, DnsType.AAAA, DnsType.MX));
        assertEquals(DnsMessage.RCODE_FORMERR, lastResponse().getRcode());
    }

    @Test
    public void testMqtypeIgnoredForTruncatedPrimary() throws Exception {
        server.setHandler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback cb) {
                DnsMessage full = query.createResponse(Collections.<DnsResourceRecord>emptyList());
                cb.onResponse(new DnsMessage(full.getId(),
                        full.getFlags() | DnsMessage.FLAG_TC, full.getQuestions(),
                        full.getAnswers(), full.getAuthorities(), full.getAdditionals()));
            }
        });
        send(mq(1, DnsType.A, DnsType.AAAA));
        assertTrue(lastResponse().isTruncated());
    }

    @Test
    public void testStaticHelpers() {
        assertEquals("NOERROR", DnsServer.rcodeToString(DnsMessage.RCODE_NOERROR));
        assertEquals("FORMERR", DnsServer.rcodeToString(DnsMessage.RCODE_FORMERR));
        assertEquals("SERVFAIL", DnsServer.rcodeToString(DnsMessage.RCODE_SERVFAIL));
        assertEquals("NXDOMAIN", DnsServer.rcodeToString(DnsMessage.RCODE_NXDOMAIN));
        assertEquals("NOTIMP", DnsServer.rcodeToString(DnsMessage.RCODE_NOTIMP));
        assertEquals("REFUSED", DnsServer.rcodeToString(DnsMessage.RCODE_REFUSED));
        assertEquals("9", DnsServer.rcodeToString(9));
        assertTrue(DnsServer.isStandardQuery(DnsMessage.createQuery(1, "a.", DnsType.A)));
        assertFalse(DnsServer.isStandardQuery(DnsMessage.createNotify(1, "a.")));
    }

    @Test
    public void testListenerManagementAndComposer() {
        DnsServer s = new DnsServer();
        assertTrue(s.getListeners().isEmpty());
        s.addListener(listener);
        s.setListeners(Collections.singletonList(listener));
        s.setListeners(Collections.singletonList("ignored"));
        assertEquals(2, s.getListeners().size());
        assertNull(s.getMetrics());
        try {
            DnsServer.compose().server();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        DnsServer composed = DnsServer.compose().listener(listener).handler(handler).server();
        assertEquals(1, composed.getListeners().size());
        // stop() on never-started listeners must not throw
        composed.stop();
    }
}
