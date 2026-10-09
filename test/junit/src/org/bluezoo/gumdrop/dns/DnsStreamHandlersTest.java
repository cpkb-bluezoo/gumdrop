/*
 * DnsStreamHandlersTest.java
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

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.*;

/**
 * Drives the DoT and DoQ protocol handlers byte by byte against a
 * recording endpoint and a scripted query handler: framing, partial input,
 * malformed input, non-query opcodes, sequences and error callbacks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsStreamHandlersTest {

    /** Scripted handler: mode selects how the callback is invoked. */
    private static final class ScriptHandler implements DnsQueryHandler {
        static final int ANSWER = 0;
        static final int ERROR = 1;
        static final int SEQUENCE = 2;
        static final int EMPTY_SEQUENCE = 3;
        static final int THROW = 4;
        int mode = ANSWER;
        int queries;

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop,
                                DnsQueryCallback callback) {
            queries++;
            if (mode == ERROR) {
                callback.onError("boom");
            } else if (mode == THROW) {
                throw new IllegalStateException("scripted");
            } else if (mode == SEQUENCE) {
                List<DnsMessage> list = new ArrayList<DnsMessage>();
                list.add(query.createResponse(Collections.<DnsResourceRecord>emptyList()));
                list.add(query.createResponse(Collections.<DnsResourceRecord>emptyList()));
                callback.onResponseSequence(list);
            } else if (mode == EMPTY_SEQUENCE) {
                callback.onResponseSequence(new ArrayList<DnsMessage>());
            } else {
                callback.onResponse(query.createResponse(Collections.<DnsResourceRecord>emptyList()));
            }
        }

        @Override
        public boolean handleNonQueryOpcode(DnsMessage query, SelectorLoop loop,
                                            DnsQueryCallback callback) {
            return false;
        }
    }

    private DnsServer server;
    private ScriptHandler handler;
    private BinaryRecordingEndpoint endpoint;

    @Before
    public void setUp() throws Exception {
        server = new DnsServer();
        handler = new ScriptHandler();
        server.handler(handler);
        endpoint = new BinaryRecordingEndpoint();
    }

    private void enableMetrics() throws Exception {
        Field f = DnsServer.class.getDeclaredField("metrics");
        f.setAccessible(true);
        f.set(server, new DnsServerMetrics(new TelemetryConfig()));
    }

    private static byte[] frame(DnsMessage m) {
        ByteBuffer b = m.serialize();
        byte[] body = new byte[b.remaining()];
        b.get(body);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) (body.length >> 8);
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    private static DnsMessage query() {
        return DnsMessage.createQuery(4, "www.example.com.", DnsType.A);
    }

    private static int framedMessages(byte[] all) {
        int count = 0;
        int pos = 0;
        while (pos + 2 <= all.length) {
            int len = ((all[pos] & 0xFF) << 8) | (all[pos + 1] & 0xFF);
            pos += 2 + len;
            count++;
        }
        assertEquals(all.length, pos);
        return count;
    }

    // ---- DoT ----

    private DoTProtocolHandler dot() {
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.securityEstablished(null);
        return h;
    }

    @Test
    public void dotAnswersByteByByteAndPipelined() throws Exception {
        enableMetrics();
        DoTProtocolHandler h = dot();
        byte[] f = frame(query());
        for (int i = 0; i < f.length; i++) {
            h.receive(ByteBuffer.wrap(new byte[] {f[i]}));
        }
        assertEquals(1, handler.queries);
        assertEquals(1, framedMessages(endpoint.getAllBytes()));
        byte[] two = new byte[f.length * 2];
        System.arraycopy(f, 0, two, 0, f.length);
        System.arraycopy(f, 0, two, f.length, f.length);
        h.receive(ByteBuffer.wrap(two));
        assertEquals(3, handler.queries);
        assertEquals(3, framedMessages(endpoint.getAllBytes()));
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void dotLargeInputGrowsAccumulator() {
        DoTProtocolHandler h = dot();
        byte[] f = frame(query());
        byte[] big = new byte[f.length * 200];
        for (int i = 0; i < 200; i++) {
            System.arraycopy(f, 0, big, i * f.length, f.length);
        }
        h.receive(ByteBuffer.wrap(big));
        assertEquals(200, handler.queries);
    }

    @Test
    public void dotZeroLengthClosesConnection() {
        DoTProtocolHandler h = dot();
        h.receive(ByteBuffer.wrap(new byte[] {0, 0}));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void dotMalformedMessageIsDroppedConnectionStays() {
        DoTProtocolHandler h = dot();
        h.receive(ByteBuffer.wrap(new byte[] {0, 3, 1, 2, 3}));
        assertEquals(0, handler.queries);
        assertTrue(endpoint.isOpen());
        assertTrue(endpoint.getWrites().isEmpty());
    }

    @Test
    public void dotErrorCallbackGivesServfail() throws Exception {
        handler.mode = ScriptHandler.ERROR;
        DoTProtocolHandler h = dot();
        h.receive(ByteBuffer.wrap(frame(query())));
        byte[] all = endpoint.getAllBytes();
        DnsMessage r = DnsMessage.parse(ByteBuffer.wrap(all, 2, all.length - 2));
        assertEquals(DnsMessage.RCODE_SERVFAIL, r.getRcode());
    }

    @Test
    public void dotSequenceSendsEveryMessage() throws Exception {
        enableMetrics();
        handler.mode = ScriptHandler.SEQUENCE;
        DoTProtocolHandler h = dot();
        h.receive(ByteBuffer.wrap(frame(query())));
        assertEquals(2, framedMessages(endpoint.getAllBytes()));
    }

    @Test
    public void dotHandlerExceptionIsContained() {
        handler.mode = ScriptHandler.THROW;
        DoTProtocolHandler h = dot();
        h.receive(ByteBuffer.wrap(frame(query())));
        assertEquals(1, handler.queries);
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void dotNonQueryOpcodeIsNotimp() throws Exception {
        DoTProtocolHandler h = dot();
        h.receive(ByteBuffer.wrap(frame(DnsMessage.createNotify(5, "example.com."))));
        byte[] all = endpoint.getAllBytes();
        DnsMessage r = DnsMessage.parse(ByteBuffer.wrap(all, 2, all.length - 2));
        assertEquals(DnsMessage.RCODE_NOTIMP, r.getRcode());
    }

    @Test
    public void dotEmptyQuestionIsFormerr() throws Exception {
        DnsMessage empty = new DnsMessage(9, DnsMessage.FLAG_RD,
                Collections.<DnsQuestion>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        DoTProtocolHandler h = dot();
        h.receive(ByteBuffer.wrap(frame(empty)));
        byte[] all = endpoint.getAllBytes();
        DnsMessage r = DnsMessage.parse(ByteBuffer.wrap(all, 2, all.length - 2));
        assertEquals(DnsMessage.RCODE_FORMERR, r.getRcode());
    }

    @Test
    public void dotLifecycleCallbacksTolerateNullEndpoint() {
        DoTProtocolHandler h = new DoTProtocolHandler(server, "doh");
        h.disconnected();
        h.error(new java.io.IOException("x"));
        h.connected(endpoint);
        h.disconnected();
        h.error(new java.io.IOException("y"));
    }

    // ---- DoQ ----

    private DoQStreamHandler doq() {
        DoQStreamHandler h = new DoQStreamHandler(server);
        h.connected(endpoint);
        h.securityEstablished(null);
        return h;
    }

    @Test
    public void doqAnswersOnFinAndCloses() throws Exception {
        enableMetrics();
        DoQStreamHandler h = doq();
        byte[] f = frame(query());
        h.receive(ByteBuffer.wrap(f, 0, 5));
        h.receive(ByteBuffer.wrap(f, 5, f.length - 5));
        h.readFinished();
        assertEquals(1, handler.queries);
        assertEquals(1, framedMessages(endpoint.getAllBytes()));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void doqEmptyStreamJustCloses() {
        DoQStreamHandler h = doq();
        h.readFinished();
        assertFalse(endpoint.isOpen());
        h.disconnected();
        assertEquals(1, endpoint.getCloseCount());
    }

    @Test
    public void doqShortFramingIsProtocolError() {
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(new byte[] {7}));
        h.readFinished();
        assertEquals(0, handler.queries);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void doqTruncatedMessageIsProtocolError() {
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(new byte[] {0, 40, 1, 2}));
        h.disconnected();
        assertEquals(0, handler.queries);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void doqMalformedQueryIsProtocolError() {
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(new byte[] {0, 3, 1, 2, 3}));
        h.readFinished();
        assertEquals(0, handler.queries);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void doqOversizedInputIsRejected() {
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.allocate(40000));
        h.receive(ByteBuffer.allocate(40000));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void doqHandlerExceptionIsInternalError() {
        handler.mode = ScriptHandler.THROW;
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(frame(query())));
        h.readFinished();
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void doqErrorCallbackGivesServfail() throws Exception {
        handler.mode = ScriptHandler.ERROR;
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(frame(query())));
        h.readFinished();
        byte[] all = endpoint.getAllBytes();
        DnsMessage r = DnsMessage.parse(ByteBuffer.wrap(all, 2, all.length - 2));
        assertEquals(DnsMessage.RCODE_SERVFAIL, r.getRcode());
    }

    @Test
    public void doqSequenceSendsAllThenCloses() throws Exception {
        enableMetrics();
        handler.mode = ScriptHandler.SEQUENCE;
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(frame(query())));
        h.readFinished();
        assertEquals(2, framedMessages(endpoint.getAllBytes()));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void doqEmptySequenceIsServfail() throws Exception {
        handler.mode = ScriptHandler.EMPTY_SEQUENCE;
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(frame(query())));
        h.readFinished();
        byte[] all = endpoint.getAllBytes();
        DnsMessage r = DnsMessage.parse(ByteBuffer.wrap(all, 2, all.length - 2));
        assertEquals(DnsMessage.RCODE_SERVFAIL, r.getRcode());
    }

    @Test
    public void doqNonQueryOpcodeIsNotimp() throws Exception {
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(frame(DnsMessage.createNotify(5, "example.com."))));
        h.readFinished();
        byte[] all = endpoint.getAllBytes();
        DnsMessage r = DnsMessage.parse(ByteBuffer.wrap(all, 2, all.length - 2));
        assertEquals(DnsMessage.RCODE_NOTIMP, r.getRcode());
    }

    @Test
    public void doqEmptyQuestionIsFormerr() throws Exception {
        DnsMessage empty = new DnsMessage(9, DnsMessage.FLAG_RD,
                Collections.<DnsQuestion>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        DoQStreamHandler h = doq();
        h.receive(ByteBuffer.wrap(frame(empty)));
        h.readFinished();
        byte[] all = endpoint.getAllBytes();
        DnsMessage r = DnsMessage.parse(ByteBuffer.wrap(all, 2, all.length - 2));
        assertEquals(DnsMessage.RCODE_FORMERR, r.getRcode());
    }

    @Test
    public void doqErrorCallbackToleratesNullEndpoint() {
        DoQStreamHandler h = new DoQStreamHandler(server);
        h.error(new java.io.IOException("x"));
    }
}
