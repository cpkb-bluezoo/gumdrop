/*
 * RedisClusterStreamingTest.java
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

package org.bluezoo.gumdrop.redis.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Streaming through a cluster session, with fake node connections: a
 * streamed read answered {@code MOVED} before any of the payload is retried
 * on the new owner and streamed from there; one that loses its connection
 * part-way fails and is not repeated; a streamed write that is redirected
 * opens a fresh source, and one that cannot be opened again fails with
 * {@code NOT_REPEATABLE}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterStreamingTest {

    private static final String A = "127.0.0.1:7000";
    private static final String B = "127.0.0.1:7001";
    private static final String C = "127.0.0.1:7002";

    private static final class Request {
        final ClusterNode node;
        final NodeListener listener;
        RedisClientProtocolHandler handler;
        RedisClientProtocolHandlerTest.StubEndpoint endpoint;

        Request(ClusterNode node, NodeListener listener) {
            this.node = node;
            this.listener = listener;
        }
    }

    private static final class FakeConnector implements NodeConnector {
        final List<Request> requests = new ArrayList<Request>();

        @Override
        public void connect(ClusterNode node, NodeListener listener) {
            requests.add(new Request(node, listener));
        }

        List<Request> forAddress(String address) {
            List<Request> out = new ArrayList<Request>();
            for (Request r : requests) {
                if (r.node.getAddress().equals(address)) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    /** A bulk handler that records the events and the session it was given. */
    private static final class Rec implements BulkResultHandler {
        final List<String> log = new ArrayList<String>();
        final List<RedisSession> sessions = new ArrayList<RedisSession>();
        long total;

        @Override
        public void handleNull() {
            log.add("null");
        }

        @Override
        public ReplyFlow handleBegin(long length, ReplyStream stream) {
            log.add("begin:" + length);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleContent(ByteBuffer chunk) {
            total += chunk.remaining();
            chunk.position(chunk.limit());
            return ReplyFlow.CONTINUE;
        }

        @Override
        public void handleEnd() {
            log.add("end");
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
            sessions.add(session);
        }
    }

    private static final class Text implements StringResultHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public void handleResult(String result, RedisSession s) {
            log.add("ok:" + result);
        }

        @Override
        public void handleError(String error, RedisSession s) {
            log.add("error:" + error.split(" ")[0]);
        }
    }

    /** A source of {@code length} bytes of 'x'. */
    private static final class Source implements ArgumentSource {
        final long length;
        int opens;
        int closes;
        boolean oneShot;
        boolean startsEmpty;

        Source(long length) {
            this.length = length;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public ArgumentStream open(ArgumentSink sink) {
            opens++;
            if (oneShot && opens > 1) {
                return null;
            }
            return new ArgumentStream() {
                private long produced;
                private boolean first = true;

                @Override
                public int read(ByteBuffer dst) throws IOException {
                    if (first && startsEmpty) {
                        first = false;
                        return 0;
                    }
                    first = false;
                    int n = (int) Math.min(dst.remaining(), length - produced);
                    for (int i = 0; i < n; i++) {
                        dst.put((byte) 'x');
                    }
                    produced += n;
                    return n == 0 ? -1 : n;
                }

                @Override
                public void close() {
                    closes++;
                }
            };
        }
    }

    private SlotMap map;
    private FakeConnector connector;
    private RedisClusterSession session;

    private static RespValue bulk(String s) {
        return RespValue.bulkString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static RespValue arr(RespValue... items) {
        List<RespValue> list = new ArrayList<RespValue>();
        for (RespValue v : items) {
            list.add(v);
        }
        return RespValue.array(list);
    }

    private static RespValue range(int from, int to, int port) {
        return arr(RespValue.integer(from), RespValue.integer(to),
                arr(bulk("127.0.0.1"), RespValue.integer(port)));
    }

    @Before
    public void setUp() {
        map = SlotMap.parse(arr(range(0, 5460, 7000), range(5461, 10922, 7001),
                range(10923, 16383, 7002)), "127.0.0.1");
        connector = new FakeConnector();
        session = new RedisClusterSession(map, connector);
    }

    private Request up(String address, int index) {
        Request r = connector.forAddress(address).get(index);
        r.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        r.handler = new RedisClientProtocolHandler(new RedisConnectionReady() {
            @Override
            public void handleReady(RedisSession s) {
            }

            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint ep) {
            }

            @Override
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
            }

            @Override
            public void onDisconnected() {
            }
        });
        r.handler.attachForTest(r.endpoint);
        r.listener.ready(r.handler);
        return r;
    }

    private Request up(String address) {
        return up(address, 0);
    }

    private static String written(Request r) {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : r.endpoint.sentBuffers) {
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            sb.append(new String(bytes, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private static void reply(Request r, String wire) {
        r.handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.ISO_8859_1)));
    }

    // ── streamed reads ──

    @Test
    public void aStreamedGetMovedBeforeAnyPayloadIsStreamedFromTheNewOwner() {
        Rec rec = new Rec();
        session.get("foo", rec);
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertTrue("the handler has heard nothing of it", rec.log.isEmpty());

        up(B);
        Request b = connector.forAddress(B).get(0);
        String wire = "$5\r\nhello\r\n";
        for (int i = 0; i < wire.length(); i++) {
            reply(b, wire.substring(i, i + 1));
        }
        assertEquals("[begin:5, end, complete]", rec.log.toString());
        assertEquals(5, rec.total);
        assertSame("the handler is given the cluster session", session, rec.sessions.get(0));
    }

    @Test
    public void aStreamedGetThatLosesItsConnectionMidPayloadFailsAndIsNotRepeated() {
        Rec rec = new Rec();
        session.get("foo", rec);
        Request c = up(C);
        reply(c, "$10\r\n012");
        c.handler.disconnected();
        c.listener.closed();
        assertEquals("[begin:10, complete:CONNLOST]", rec.log.toString());
        assertEquals("it was not sent anywhere else", 1, connector.requests.size());
    }

    // ── streamed writes ──

    @Test
    public void aStreamedSetWaitsForItsConnectionBeforeItsSourceIsOpened() {
        Source value = new Source(3);
        session.set("foo", value, new Text());
        assertEquals(0, value.opens);
        Request c = up(C);
        assertEquals(1, value.opens);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nxxx\r\n", written(c));
    }

    @Test
    public void aRedirectedStreamedSetOpensAFreshSourceOnTheNewOwner() {
        Source value = new Source(3);
        Text text = new Text();
        session.set("foo", value, text);
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        up(B);
        Request b = connector.forAddress(B).get(0);
        assertEquals(2, value.opens);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nxxx\r\n", written(b));
        reply(b, "+OK\r\n");
        assertEquals("[ok:OK]", text.log.toString());
    }

    @Test
    public void aStreamedSetThatCannotBeReadAgainFailsWhenRedirected() {
        Source value = new Source(3);
        value.oneShot = true;
        Text text = new Text();
        session.set("foo", value, text);
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        up(B);
        Request b = connector.forAddress(B).get(0);
        assertEquals("[error:NOT_REPEATABLE]", text.log.toString());
        assertEquals("nothing of it is written to the new owner", "", written(b));
    }

    @Test
    public void aStreamedSetWhoseConnectionIsLostMidValueFailsAndClosesItsStream() {
        Source value = new Source(100);
        value.startsEmpty = true;
        Text text = new Text();
        session.set("foo", value, text);
        Request c = up(C);
        c.handler.disconnected();
        assertEquals(1, value.closes);
        assertEquals(1, text.log.size());
        assertTrue(text.log.get(0), text.log.get(0).startsWith("error:"));
        assertEquals("it was not sent anywhere else", 1, connector.requests.size());
    }

}
