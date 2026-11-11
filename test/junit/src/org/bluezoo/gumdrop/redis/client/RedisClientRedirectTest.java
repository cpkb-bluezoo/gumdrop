/*
 * RedisClientRedirectTest.java
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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The error-interception hook of {@link RedisClientProtocolHandler}: what a
 * {@link RedirectListener} is given, when it takes over a command, and that
 * a command can be sent again from the {@link SentCommand} it receives.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientRedirectTest {

    /** Records the commands and errors it is offered. */
    private static final class Recorder implements RedirectListener {
        final List<SentCommand> lost = new ArrayList<SentCommand>();
        int lostCalls;
        final List<SentCommand> commands = new ArrayList<SentCommand>();
        final List<String> errors = new ArrayList<String>();
        boolean take;

        @Override
        public boolean handleError(SentCommand command, String error) {
            commands.add(command);
            errors.add(error);
            return take;
        }

        @Override
        public void connectionLost(List<SentCommand> unanswered) {
            lostCalls++;
            lost.addAll(unanswered);
        }
    }

    /** Bulk-string results (GET and the like). */
    private static final class Results implements BulkResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(byte[] value, RedisSession session) {
            events.add("bulk:" + new String(value, StandardCharsets.UTF_8));
        }

        @Override
        public void handleNull(RedisSession session) {
            events.add("null");
        }

        @Override
        public void handleError(String error, RedisSession session) {
            events.add("error:" + error);
        }
    }

    /** Simple-string results (SET, PING and the like). */
    private static final class StringResults implements StringResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(String result, RedisSession session) {
            events.add("string:" + result);
        }

        @Override
        public void handleError(String error, RedisSession session) {
            events.add("error:" + error);
        }
    }

    private RedisClientProtocolHandler handler;
    private RedisClientProtocolHandlerTest.StubEndpoint endpoint;
    private RedisSession session;

    @Before
    public void setUp() {
        final AtomicReference<RedisSession> ref = new AtomicReference<RedisSession>();
        handler = new RedisClientProtocolHandler(new RedisConnectionReady() {
            @Override
            public void handleReady(RedisSession s) {
                ref.set(s);
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
        endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        session = ref.get();
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    private String lastSent() {
        ByteBuffer b = endpoint.sentBuffers.get(endpoint.sentBuffers.size() - 1);
        byte[] bytes = new byte[b.remaining()];
        b.duplicate().get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    public void withoutAListenerErrorsGoStraightToTheHandler() {
        Results r = new Results();
        session.get("k", r);
        feed("-MOVED 3999 127.0.0.1:6381\r\n");
        assertEquals(1, r.events.size());
        assertEquals("error:MOVED 3999 127.0.0.1:6381", r.events.get(0));
    }

    @Test
    public void aListenerSeesTheErrorAndTheCommandThatCausedIt() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        Results r = new Results();
        session.get("k", r);
        feed("-MOVED 3999 127.0.0.1:6381\r\n");

        assertEquals(1, rec.commands.size());
        assertEquals("MOVED 3999 127.0.0.1:6381", rec.errors.get(0));
        SentCommand c = rec.commands.get(0);
        assertEquals("GET", c.getCommand());
        assertArrayEquals(new String[] { "k" }, c.getTextArgs());
        assertNull(c.getBinaryArgs());
        assertSame(r, c.getCallback());
        assertEquals("not taken over, so the handler is told", "error:MOVED 3999 127.0.0.1:6381",
                r.events.get(0));
    }

    @Test
    public void aListenerThatTakesOverKeepsTheErrorFromTheHandler() {
        Recorder rec = new Recorder();
        rec.take = true;
        handler.setRedirectListener(rec);
        Results r = new Results();
        session.get("k", r);
        feed("-ASK 3999 127.0.0.1:6381\r\n");
        assertEquals(1, rec.commands.size());
        assertTrue(r.events.isEmpty());
    }

    @Test
    public void successfulRepliesDoNotReachTheListener() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        Results r = new Results();
        session.get("k", r);
        feed("$1\r\nv\r\n");
        assertTrue(rec.commands.isEmpty());
        assertEquals("bulk:v", r.events.get(0));
    }

    @Test
    public void commandsWithNoArgumentsAreRecordedToo() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        session.ping(new StringResults());
        feed("-LOADING busy\r\n");
        SentCommand c = rec.commands.get(0);
        assertEquals("PING", c.getCommand());
        assertNull(c.getTextArgs());
        assertNull(c.getBinaryArgs());
    }

    @Test
    public void binaryArgumentsAreRecordedAsBinary() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        session.set("k", new byte[] { 1, 2, 3 }, new StringResults());
        feed("-MOVED 1 h:1\r\n");
        SentCommand c = rec.commands.get(0);
        assertNull(c.getTextArgs());
        assertEquals(2, c.getBinaryArgs().length);
        assertArrayEquals(new byte[] { 1, 2, 3 }, c.getBinaryArgs()[1]);
    }

    @Test
    public void repliesAreMatchedToCommandsInOrder() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        Results a = new Results();
        Results b = new Results();
        session.get("first", a);
        session.get("second", b);
        feed("$1\r\nx\r\n-MOVED 5 h:1\r\n");
        assertEquals("bulk:x", a.events.get(0));
        assertEquals(1, rec.commands.size());
        assertArrayEquals(new String[] { "second" }, rec.commands.get(0).getTextArgs());
    }

    @Test
    public void aRecordedCommandCanBeSentAgainOnAnotherConnection() {
        Recorder rec = new Recorder();
        rec.take = true;
        handler.setRedirectListener(rec);
        StringResults r = new StringResults();
        session.set("k", "v", r);
        feed("-MOVED 3999 127.0.0.1:6381\r\n");

        RedisClientProtocolHandler other = new RedisClientProtocolHandler(null);
        RedisClientProtocolHandlerTest.StubEndpoint otherEndpoint =
                new RedisClientProtocolHandlerTest.StubEndpoint();
        other.attachForTest(otherEndpoint);
        rec.commands.get(0).resend(other);

        ByteBuffer sent = otherEndpoint.sentBuffers.get(0);
        byte[] bytes = new byte[sent.remaining()];
        sent.get(bytes);
        assertEquals("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n",
                new String(bytes, StandardCharsets.UTF_8));
        other.receive(ByteBuffer.wrap("+OK\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("the original handler gets the answer from the new node", "string:OK",
                r.events.get(0));
    }

    @Test
    public void clearingTheListenerRestoresPlainDelivery() {
        Recorder rec = new Recorder();
        rec.take = true;
        handler.setRedirectListener(rec);
        handler.setRedirectListener(null);
        Results r = new Results();
        session.get("k", r);
        feed("-MOVED 1 h:1\r\n");
        assertTrue(rec.commands.isEmpty());
        assertEquals(1, r.events.size());
    }

    // ── connection loss ──

    @Test
    public void aListenerIsGivenTheCommandsStillUnansweredWhenTheConnectionDrops() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        session.get("one", new Results());
        session.get("two", new Results());
        session.get("three", new Results());
        feed("$1\r\nx\r\n");
        handler.disconnected();
        assertEquals(1, rec.lostCalls);
        assertEquals(2, rec.lost.size());
        assertArrayEquals(new String[] { "two" }, rec.lost.get(0).getTextArgs());
        assertArrayEquals(new String[] { "three" }, rec.lost.get(1).getTextArgs());
    }

    @Test
    public void closingTheConnectionAlsoHandsOverTheUnansweredCommands() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        session.get("one", new Results());
        handler.close();
        assertEquals(1, rec.lost.size());
    }

    @Test
    public void theUnansweredCommandsAreHandedOverOnce() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        session.get("one", new Results());
        handler.close();
        handler.disconnected();
        assertEquals(1, rec.lost.size());
    }

    @Test
    public void nothingIsHandedOverWhenNothingIsPending() {
        Recorder rec = new Recorder();
        handler.setRedirectListener(rec);
        handler.disconnected();
        assertEquals(0, rec.lostCalls);
    }

    @Test
    public void withoutAListenerADropStillLeavesHandlersUntold() {
        Results r = new Results();
        session.get("one", r);
        handler.disconnected();
        assertTrue(r.events.isEmpty());
    }
}
