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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A subscription command is answered by confirmations, one per channel, not
 * by a single reply. Once they have all arrived the command must leave the
 * queue of commands awaiting a reply, or every later reply is matched with
 * the wrong command.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientSubscriptionAccountingTest {

    static final class Quiet implements MessageHandler {
        @Override
        public void handleMessage(String channel, byte[] message) {
        }

        @Override
        public void handlePatternMessage(String pattern, String channel, byte[] message) {
        }

        @Override
        public void handleSubscribed(String channel, int count) {
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
        }

        @Override
        public void handlePatternSubscribed(String pattern, int count) {
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
        }

        @Override
        public void handleError(String error) {
        }
    }

    private static final class Bulk implements BulkResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(byte[] value, RedisSession s) {
            events.add("value:" + new String(value, StandardCharsets.UTF_8));
        }

        @Override
        public void handleNull(RedisSession s) {
            events.add("null");
        }

        @Override
        public void handleError(String error, RedisSession s) {
            events.add("error:" + error);
        }
    }

    private static final class Errors implements RedirectListener {
        final List<String> commands = new ArrayList<String>();

        @Override
        public boolean handleError(SentCommand command, String error) {
            StringBuilder sb = new StringBuilder(command.getCommand());
            for (byte[] arg : command.toBinaryArgs()) {
                sb.append(' ').append(new String(arg, StandardCharsets.UTF_8));
            }
            commands.add(sb.toString());
            return false;
        }

        @Override
        public void connectionLost(List<SentCommand> unanswered) {
        }
    }

    private RedisClientProtocolHandler handler;
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
        handler.connected(new RedisClientProtocolHandlerTest.StubEndpoint());
        session = ref.get();
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    private static String confirm(String kind, String name, int count) {
        return ">3\r\n$" + kind.length() + "\r\n" + kind + "\r\n$" + name.length() + "\r\n"
                + name + "\r\n:" + count + "\r\n";
    }

    @Test
    public void aReplyAfterAConfirmedSubscribeBelongsToItsOwnCommand() {
        session.subscribe(new Quiet(), "a");
        feed(confirm("subscribe", "a", 1));
        Bulk b = new Bulk();
        session.get("k", b);
        feed("$1\r\nv\r\n");
        assertEquals("[value:v]", b.events.toString());
    }

    @Test
    public void aSubscribeOfSeveralChannelsIsSettledByTheLastConfirmation() {
        session.subscribe(new Quiet(), "a", "b");
        Bulk b = new Bulk();
        session.get("k", b);
        feed(confirm("subscribe", "a", 1));
        feed(confirm("subscribe", "b", 2));
        feed("$1\r\nv\r\n");
        assertEquals("[value:v]", b.events.toString());
    }

    @Test
    public void everyKindOfSubscriptionCommandIsSettled() {
        Quiet q = new Quiet();
        session.subscribe(q, "a");
        session.psubscribe(q, "p*");
        session.ssubscribe(q, "s");
        session.unsubscribe("a");
        session.punsubscribe("p*");
        session.sunsubscribe("s");
        feed(confirm("subscribe", "a", 1));
        feed(confirm("psubscribe", "p*", 2));
        feed(confirm("ssubscribe", "s", 1));
        feed(confirm("unsubscribe", "a", 1));
        feed(confirm("punsubscribe", "p*", 0));
        feed(confirm("sunsubscribe", "s", 0));
        Bulk b = new Bulk();
        session.get("k", b);
        feed("$1\r\nv\r\n");
        assertEquals("[value:v]", b.events.toString());
    }

    @Test
    public void aBareUnsubscribeIsSettledByItsFirstConfirmation() {
        session.subscribe(new Quiet(), "a", "b");
        feed(confirm("subscribe", "a", 1));
        feed(confirm("subscribe", "b", 2));
        session.unsubscribe();
        feed(confirm("unsubscribe", "a", 1));
        Bulk b = new Bulk();
        session.get("k", b);
        feed(confirm("unsubscribe", "b", 0));
        feed("$1\r\nv\r\n");
        assertEquals("[value:v]", b.events.toString());
    }

    @Test
    public void anErrorForALaterSubscribeNamesThatCommand() {
        Errors errors = new Errors();
        handler.setRedirectListener(errors);
        Quiet q = new Quiet();
        session.ssubscribe(q, "foo");
        feed(confirm("ssubscribe", "foo", 1));
        session.ssubscribe(q, "bar");
        feed("-MOVED 5061 127.0.0.1:7001\r\n");
        assertEquals("[SSUBSCRIBE bar]", errors.commands.toString());
    }
}
