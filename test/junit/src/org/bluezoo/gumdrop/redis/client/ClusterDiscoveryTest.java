/*
 * ClusterDiscoveryTest.java
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
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link ClusterDiscovery}: trying the seeds in order for the topology.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterDiscoveryTest {

    private static final String SLOTS_COMMAND = "*2\r\n$7\r\nCLUSTER\r\n$5\r\nSLOTS\r\n";

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
    }

    private static final class Outcome implements ClusterDiscovery.Callback {
        SlotMap map;
        RedisClientProtocolHandler seed;
        Exception failure;
        int calls;

        @Override
        public void discovered(SlotMap m, RedisClientProtocolHandler seedConnection) {
            map = m;
            seed = seedConnection;
            calls++;
        }

        @Override
        public void failed(Exception cause) {
            failure = cause;
            calls++;
        }
    }

    private FakeConnector connector;
    private Outcome outcome;
    private List<ClusterNode> seeds;

    @Before
    public void setUp() {
        connector = new FakeConnector();
        outcome = new Outcome();
        seeds = Arrays.asList(new ClusterNode("seed1", 7000, null), new ClusterNode("seed2", 7001, null));
    }

    private Request up(int index) {
        Request r = connector.requests.get(index);
        r.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        r.handler = new RedisClientProtocolHandler(null);
        r.handler.attachForTest(r.endpoint);
        r.listener.ready(r.handler);
        return r;
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
        r.handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String WHOLE_CLUSTER =
            "*1\r\n*3\r\n:0\r\n:16383\r\n*3\r\n$9\r\n127.0.0.1\r\n:7000\r\n$2\r\nid\r\n";

    @Test
    public void theFirstSeedIsAskedForTheSlots() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        assertEquals(1, connector.requests.size());
        assertEquals("seed1:7000", connector.requests.get(0).node.getAddress());
        Request r = up(0);
        assertEquals(SLOTS_COMMAND, written(r));
        assertEquals(0, outcome.calls);
    }

    @Test
    public void aGoodAnswerEndsTheDiscoveryAndHandsOverTheOpenSeedConnection() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        Request r = up(0);
        reply(r, WHOLE_CLUSTER);
        assertEquals(1, outcome.calls);
        assertNotNull(outcome.map);
        assertEquals(7000, outcome.map.nodeFor(100).getPort());
        assertTrue(outcome.map.isComplete());
        assertSame("the connection is handed over still open", r.handler, outcome.seed);
        assertTrue(r.endpoint.isOpen());
        assertEquals("the second seed is not used", 1, connector.requests.size());
    }

    @Test
    public void aBlankHostInTheAnswerMeansTheSeedThatAnswered() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        Request r = up(0);
        reply(r, "*1\r\n*3\r\n:0\r\n:16383\r\n*2\r\n$0\r\n\r\n:7000\r\n");
        assertEquals("seed1", outcome.map.nodeFor(5).getHost());
    }

    @Test
    public void aSeedThatCannotBeReachedMovesOnToTheNext() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        connector.requests.get(0).listener.failed(new java.io.IOException("refused"));
        assertEquals(2, connector.requests.size());
        assertEquals("seed2:7001", connector.requests.get(1).node.getAddress());
        reply(up(1), WHOLE_CLUSTER);
        assertNotNull(outcome.map);
    }

    @Test
    public void anErrorReplyMovesOnToTheNextSeed() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        Request r = up(0);
        reply(r, "-ERR This instance has cluster support disabled\r\n");
        assertEquals(2, connector.requests.size());
        assertFalse("the failed seed's connection is closed", r.endpoint.isOpen());
        reply(up(1), WHOLE_CLUSTER);
        assertNotNull(outcome.map);
    }

    @Test
    public void aMalformedAnswerMovesOnToTheNextSeed() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        reply(up(0), "*1\r\n*2\r\n:10\r\n:5\r\n");
        assertEquals(2, connector.requests.size());
    }

    @Test
    public void anAnswerWithNoSlotsMovesOnToTheNextSeed() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        reply(up(0), "*0\r\n");
        assertEquals(2, connector.requests.size());
        assertEquals(0, outcome.calls);
    }

    @Test
    public void aSeedThatClosesWithoutAnsweringMovesOnToTheNext() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        Request r = up(0);
        r.listener.closed();
        assertEquals(2, connector.requests.size());
    }

    @Test
    public void whenEverySeedFailsTheDiscoveryFails() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        connector.requests.get(0).listener.failed(new java.io.IOException("refused"));
        connector.requests.get(1).listener.failed(new java.io.IOException("timed out"));
        assertEquals(1, outcome.calls);
        assertNull(outcome.map);
        assertNotNull(outcome.failure);
        assertTrue(outcome.failure.getMessage(), outcome.failure.getMessage().contains("timed out"));
    }

    @Test
    public void noSeedsFailsAtOnce() {
        ClusterDiscovery.discover(new ArrayList<ClusterNode>(), connector, outcome);
        assertEquals(1, outcome.calls);
        assertNotNull(outcome.failure);
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void lateEventsFromAnEarlierSeedAreIgnored() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        Request first = connector.requests.get(0);
        first.listener.failed(new java.io.IOException("refused"));
        first.listener.failed(new java.io.IOException("again"));
        first.listener.closed();
        assertEquals("the second seed was asked once only", 2, connector.requests.size());
    }

    @Test
    public void theOutcomeIsReportedOnce() {
        ClusterDiscovery.discover(seeds, connector, outcome);
        Request r = up(0);
        reply(r, WHOLE_CLUSTER);
        r.listener.closed(); // the seed connection closes later
        assertEquals(1, outcome.calls);
    }
}
