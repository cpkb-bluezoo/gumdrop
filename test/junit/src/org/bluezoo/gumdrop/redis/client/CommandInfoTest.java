/*
 * CommandInfoTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * What the client knows of a command by its name.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CommandInfoTest {

    private static void keys(CommandInfo.Keys expected, String... names) {
        for (String name : names) {
            assertEquals(name, expected, CommandInfo.of(name).keys);
            assertEquals(name.toLowerCase(java.util.Locale.ROOT), expected,
                    CommandInfo.of(name.toLowerCase(java.util.Locale.ROOT)).keys);
        }
    }

    @Test
    public void keyLayouts() {
        keys(CommandInfo.Keys.NONE, "PING", "PUBLISH", "SUBSCRIBE", "KEYS", "MULTI", "WAIT", "CLUSTER");
        keys(CommandInfo.Keys.FIRST, "GET", "SET", "HGETALL", "LPUSH", "ZADD", "XADD", "NOSUCHCOMMAND");
        keys(CommandInfo.Keys.ALL, "MGET", "DEL", "WATCH", "SINTER", "SSUBSCRIBE", "SUNSUBSCRIBE");
        keys(CommandInfo.Keys.ALTERNATE, "MSET", "MSETNX");
        keys(CommandInfo.Keys.FIRST_TWO, "RENAME", "LMOVE", "BLMOVE", "SMOVE", "COPY");
        keys(CommandInfo.Keys.ALL_BUT_LAST, "BLPOP", "BRPOP");
        keys(CommandInfo.Keys.SECOND, "XINFO", "XGROUP", "OBJECT");
        keys(CommandInfo.Keys.NUMKEYS_SCRIPT, "EVAL", "EVALSHA", "FCALL_RO");
        keys(CommandInfo.Keys.NUMKEYS_STORE, "ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE");
        keys(CommandInfo.Keys.STREAMS, "XREAD", "XREADGROUP");
    }

    @Test
    public void blockingRules() {
        assertEquals(CommandInfo.Blocking.SECONDS_LAST, CommandInfo.of("blpop").blocking);
        assertEquals(CommandInfo.Blocking.SECONDS_LAST, CommandInfo.of("BZPOPMIN").blocking);
        assertEquals(CommandInfo.Blocking.SECONDS_FIRST, CommandInfo.of("BLMPOP").blocking);
        assertEquals(CommandInfo.Blocking.MILLIS_LAST, CommandInfo.of("Wait").blocking);
        assertEquals(CommandInfo.Blocking.MILLIS_LAST, CommandInfo.of("WAITAOF").blocking);
        assertEquals(CommandInfo.Blocking.BLOCK_OPTION, CommandInfo.of("xread").blocking);
        assertEquals(CommandInfo.Blocking.NONE, CommandInfo.of("GET").blocking);
        assertEquals(CommandInfo.Blocking.NONE, CommandInfo.of("LPOP").blocking);
    }

    @Test
    public void subscriptionKinds() {
        assertEquals("subscribe", CommandInfo.of("SUBSCRIBE").subscriptionKind);
        assertEquals("psubscribe", CommandInfo.of("psubscribe").subscriptionKind);
        assertEquals("ssubscribe", CommandInfo.of("SSubscribe").subscriptionKind);
        assertEquals("unsubscribe", CommandInfo.of("UNSUBSCRIBE").subscriptionKind);
        assertEquals("punsubscribe", CommandInfo.of("PUNSUBSCRIBE").subscriptionKind);
        assertEquals("sunsubscribe", CommandInfo.of("sunsubscribe").subscriptionKind);
        assertNull(CommandInfo.of("PUBLISH").subscriptionKind);
        assertNull(CommandInfo.of("GET").subscriptionKind);
    }

    @Test
    public void transactionRoles() {
        assertEquals(CommandInfo.Role.MULTI, CommandInfo.of("multi").role);
        assertEquals(CommandInfo.Role.EXEC, CommandInfo.of("EXEC").role);
        assertEquals(CommandInfo.Role.DISCARD, CommandInfo.of("Discard").role);
        assertEquals(CommandInfo.Role.WATCH, CommandInfo.of("watch").role);
        assertEquals(CommandInfo.Role.UNWATCH, CommandInfo.of("UNWATCH").role);
        assertEquals(CommandInfo.Role.WAIT, CommandInfo.of("wait").role);
        assertEquals(CommandInfo.Role.WAIT, CommandInfo.of("WAITAOF").role);
        assertEquals(CommandInfo.Role.NONE, CommandInfo.of("GET").role);
    }

    @Test
    public void anUnknownCommandIsTheDefault() {
        assertSame(CommandInfo.UNKNOWN, CommandInfo.of("NOSUCHCOMMAND"));
        assertSame(CommandInfo.UNKNOWN, CommandInfo.of(""));
        assertEquals(CommandInfo.Keys.FIRST, CommandInfo.UNKNOWN.keys);
    }
}
