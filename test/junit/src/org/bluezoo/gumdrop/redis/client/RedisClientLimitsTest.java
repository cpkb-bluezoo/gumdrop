/*
 * RedisClientLimitsTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The limits on what a server can make the client read: the longest line, the
 * longest identifier or scalar delivered whole, and the most that is read
 * only to be discarded. They are options of both clients, they are refused
 * when they cannot work, and they are applied to every connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientLimitsTest {

    private static final class Quiet implements RedisConnectionReady {
        public void handleReady(RedisSession session) {
        }

        public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
        }

        public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
        }

        public void onError(Exception cause) {
        }

        public void onDisconnected() {
        }
    }

    private static final class Keys implements KeyArrayResultHandler {
        final List<String> log = new ArrayList<String>();

        public void handleNull() {
            log.add("null");
        }

        public ReplyFlow handleBegin(long count, ReplyStream stream) {
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow handleKey(String key) {
            log.add("key:" + key);
            return ReplyFlow.CONTINUE;
        }

        public void handleEnd() {
        }

        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }
    }

    /** A bulk handler that skips every reply at once. */
    private static final class Skipper implements BulkResultHandler {
        final List<String> log = new ArrayList<String>();

        public void handleNull() {
        }

        public ReplyFlow handleBegin(long length, ReplyStream stream) {
            return ReplyFlow.SKIP;
        }

        public ReplyFlow handleContent(ByteBuffer chunk) {
            return ReplyFlow.CONTINUE;
        }

        public void handleEnd() {
        }

        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }
    }

    private static RedisClientProtocolHandler attach(RedisClientProtocolHandler h,
            RedisClientProtocolHandlerTest.StubEndpoint endpoint) {
        h.attachForTest(endpoint);
        return h;
    }

    private static void feed(RedisClientProtocolHandler h, String wire) {
        h.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static String x(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('x');
        }
        return sb.toString();
    }

    // ── on the connection ──

    @Test
    public void aLineOverTheLimitClosesTheConnection() {
        RedisClientProtocolHandlerTest.StubEndpoint ep = new RedisClientProtocolHandlerTest.StubEndpoint();
        RedisClientProtocolHandler h = attach(new RedisClientProtocolHandler(new Quiet()), ep);
        h.setMaxLine(64);
        feed(h, "+" + x(60) + "\r\n");
        assertTrue("within the limit", ep.open);
        feed(h, "+" + x(100) + "\r\n");
        assertFalse("over it", ep.open);
    }

    @Test
    public void anIdentifierOverTheLimitFailsOnlyItsReply() {
        RedisClientProtocolHandlerTest.StubEndpoint ep = new RedisClientProtocolHandlerTest.StubEndpoint();
        RedisClientProtocolHandler h = attach(new RedisClientProtocolHandler(new Quiet()), ep);
        h.setMaxField(8);
        Keys keys = new Keys();
        h.keys("*", keys);
        feed(h, "*2\r\n$3\r\nabc\r\n$20\r\n" + x(20) + "\r\n");
        assertEquals("[key:abc, complete:TOOLARGE]", keys.log.toString());
        assertTrue(ep.open);
    }

    @Test
    public void whatIsDiscardedPastTheLimitClosesTheConnection() {
        RedisClientProtocolHandlerTest.StubEndpoint ep = new RedisClientProtocolHandlerTest.StubEndpoint();
        RedisClientProtocolHandler h = attach(new RedisClientProtocolHandler(new Quiet()), ep);
        h.setMaxSkip(100);
        Skipper small = new Skipper();
        h.get("a", small);
        feed(h, "$50\r\n" + x(50) + "\r\n");
        assertEquals("[complete:CANCELLED]", small.log.toString());
        assertTrue("within the limit the connection survives", ep.open);
        Skipper big = new Skipper();
        h.get("b", big);
        feed(h, "$500\r\n" + x(500) + "\r\n");
        assertFalse("past it the connection is closed", ep.open);
    }

    // ── as options of the clients ──

    @Test
    public void theSingleNodeClientAppliesItsLimitsToItsConnection() {
        RedisClient client = new RedisClient("localhost", 6379).maxLine(64).maxField(8).maxSkip(100);
        RedisClientProtocolHandlerTest.StubEndpoint ep = new RedisClientProtocolHandlerTest.StubEndpoint();
        RedisClientProtocolHandler h = attach(client.newHandler(new Quiet()), ep);
        Keys keys = new Keys();
        h.keys("*", keys);
        feed(h, "*1\r\n$20\r\n" + x(20) + "\r\n");
        assertEquals("[complete:TOOLARGE]", keys.log.toString());
        feed(h, "+" + x(100) + "\r\n");
        assertFalse(ep.open);
    }

    @Test
    public void theClusterClientAppliesItsLimitsToTheConnectionToEachNode() {
        TcpNodeConnector connector = new TcpNodeConnector(null, null, null, false, new TlsConfig(),
                null, null, 2, 64, 8, 100L);
        RedisClient client = connector.newClient(new ClusterNode("h", 7000, null));
        RedisClientProtocolHandlerTest.StubEndpoint ep = new RedisClientProtocolHandlerTest.StubEndpoint();
        RedisClientProtocolHandler h = attach(client.newHandler(new Quiet()), ep);
        Keys keys = new Keys();
        h.keys("*", keys);
        feed(h, "*1\r\n$20\r\n" + x(20) + "\r\n");
        assertEquals("[complete:TOOLARGE]", keys.log.toString());
        feed(h, "+" + x(100) + "\r\n");
        assertFalse(ep.open);
    }

    @Test
    public void theDefaultsAreTheDocumentedOnes() {
        assertEquals(64 * 1024, RedisClientProtocolHandler.DEFAULT_MAX_LINE);
        assertEquals(64 * 1024, RedisClientProtocolHandler.DEFAULT_MAX_FIELD);
        assertEquals(256L * 1024 * 1024, RedisClientProtocolHandler.DEFAULT_MAX_SKIP);
    }

    @Test
    public void limitsThatCannotWorkAreRefusedByBothClients() {
        RedisClient c = new RedisClient("localhost", 6379);
        assertSame(c, c.maxLine(64));
        assertSame(c, c.maxField(1));
        assertSame(c, c.maxSkip(1));
        RedisClusterClient cc = new RedisClusterClient();
        assertSame(cc, cc.maxLine(64));
        assertSame(cc, cc.maxField(1));
        assertSame(cc, cc.maxSkip(1));
        for (int i = 0; i < 6; i++) {
            try {
                switch (i) {
                    case 0:
                        c.maxLine(63);
                        break;
                    case 1:
                        c.maxField(0);
                        break;
                    case 2:
                        c.maxSkip(0);
                        break;
                    case 3:
                        cc.maxLine(-1);
                        break;
                    case 4:
                        cc.maxField(0);
                        break;
                    default:
                        cc.maxSkip(0);
                        break;
                }
                fail("case " + i);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("limit"));
            }
        }
    }

}
