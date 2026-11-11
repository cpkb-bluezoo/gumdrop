/*
 * NodeAdapterTest.java
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

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link NodeAdapter}: authentication, and the translation of connection
 * events into {@link NodeListener} calls.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NodeAdapterTest {

    private static final class Events implements NodeListener {
        final List<String> events = new ArrayList<String>();
        RedisClientProtocolHandler connection;

        @Override
        public void ready(RedisClientProtocolHandler c) {
            connection = c;
            events.add("ready");
        }

        @Override
        public void failed(Exception cause) {
            events.add("failed:" + cause.getMessage());
        }

        @Override
        public void closed() {
            events.add("closed");
        }
    }

    private Events events;
    private RedisClientProtocolHandlerTest.StubEndpoint endpoint;

    @Before
    public void setUp() {
        events = new Events();
        endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
    }

    private RedisClientProtocolHandler connect(String user, String password) {
        RedisClientProtocolHandler h = new RedisClientProtocolHandler(new NodeAdapter(events, user, password));
        h.connected(endpoint);
        return h;
    }

    private String written() {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : endpoint.sentBuffers) {
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            sb.append(new String(bytes, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private static void feed(RedisClientProtocolHandler h, String wire) {
        h.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void withoutCredentialsTheConnectionIsReadyAtOnce() {
        RedisClientProtocolHandler h = connect(null, null);
        assertEquals("ready", events.events.get(0));
        assertSame(h, events.connection);
        assertEquals("", written());
    }

    @Test
    public void aPasswordIsSentAndTheConnectionIsReadyOnceItIsAccepted() {
        RedisClientProtocolHandler h = connect(null, "s3cret");
        assertEquals("AUTH comes first", "*2\r\n$4\r\nAUTH\r\n$6\r\ns3cret\r\n", written());
        assertTrue("not ready until the server accepts it", events.events.isEmpty());
        feed(h, "+OK\r\n");
        assertEquals(1, events.events.size());
        assertEquals("ready", events.events.get(0));
    }

    @Test
    public void aUserAndPasswordAreSentTogether() {
        connect("alice", "s3cret");
        assertEquals("*3\r\n$4\r\nAUTH\r\n$5\r\nalice\r\n$6\r\ns3cret\r\n", written());
    }

    @Test
    public void aRejectedPasswordFailsTheConnectionAndClosesIt() {
        RedisClientProtocolHandler h = connect(null, "wrong");
        feed(h, "-WRONGPASS invalid username-password pair\r\n");
        assertEquals(1, events.events.size());
        assertTrue(events.events.get(0), events.events.get(0).startsWith("failed:"));
        assertTrue(events.events.get(0), events.events.get(0).contains("WRONGPASS"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void closingAfterARejectedPasswordIsNotReportedAgain() {
        RedisClientProtocolHandler h = connect(null, "wrong");
        feed(h, "-WRONGPASS bad\r\n");
        h.disconnected();
        assertEquals(1, events.events.size());
    }

    @Test
    public void anErrorBeforeTheConnectionIsReadyFailsIt() {
        RedisClientProtocolHandler h = new RedisClientProtocolHandler(new NodeAdapter(events, null, null));
        h.error(new java.io.IOException("refused"));
        assertEquals("failed:refused", events.events.get(0));
    }

    @Test
    public void anErrorAfterTheConnectionIsReadyIsNotAFailure() {
        RedisClientProtocolHandler h = connect(null, null);
        h.error(new java.io.IOException("reset"));
        assertEquals(1, events.events.size());
    }

    @Test
    public void closingBeforeReadyIsAFailure() {
        RedisClientProtocolHandler h = connect(null, "pw");
        h.disconnected();
        assertEquals(1, events.events.size());
        assertTrue(events.events.get(0), events.events.get(0).startsWith("failed:"));
    }

    @Test
    public void closingAfterReadyIsReportedAsClosed() {
        RedisClientProtocolHandler h = connect(null, null);
        h.disconnected();
        assertEquals("closed", events.events.get(1));
    }

    @Test
    public void theFailureIsReportedOnlyOnce() {
        RedisClientProtocolHandler h = new RedisClientProtocolHandler(new NodeAdapter(events, null, null));
        h.error(new java.io.IOException("refused"));
        h.disconnected();
        assertEquals(1, events.events.size());
    }
}
