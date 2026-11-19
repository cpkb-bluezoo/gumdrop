/*
 * CommandInfo.java
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the client needs to know about a command by its name: which of its
 * arguments are keys, whether and how it blocks on the server, whether it
 * subscribes, and what part it plays in a transaction. Looked up once for a
 * command, by name in any case and without making a string of it, then
 * carried with the command.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class CommandInfo {

    /** Which arguments of a command are its keys. */
    enum Keys {
        /** None: the command goes to any node. */
        NONE,
        /** The first argument; the layout of nearly every command. */
        FIRST,
        /** Every argument. */
        ALL,
        /** Every other argument, from the first (MSET). */
        ALTERNATE,
        /** The first two arguments. */
        FIRST_TWO,
        /** Every argument but the last, which is a timeout (BLPOP). */
        ALL_BUT_LAST,
        /** The second argument, after a subcommand (XINFO STREAM key). */
        SECOND,
        /** A key count in the second argument, then that many keys (EVAL). */
        NUMKEYS_SCRIPT,
        /** As NUMKEYS_SCRIPT, and the first argument is a key too (ZUNIONSTORE). */
        NUMKEYS_STORE,
        /** The keys after the STREAMS token (XREAD). */
        STREAMS
    }

    /** How a command that waits on the server says for how long. */
    enum Blocking {
        /** It does not wait. */
        NONE,
        /** Seconds, in the last argument (BLPOP). */
        SECONDS_LAST,
        /** Seconds, in the first argument (BLMPOP). */
        SECONDS_FIRST,
        /** Milliseconds, in the last argument (WAIT). */
        MILLIS_LAST,
        /** Milliseconds, after a BLOCK option (XREAD). */
        BLOCK_OPTION
    }

    /** The part a command plays in a transaction. */
    enum Role {
        NONE, MULTI, EXEC, DISCARD, WATCH, UNWATCH,
        /** WAIT and WAITAOF, which cannot be queued in a transaction. */
        WAIT
    }

    /** The descriptor of a command this table does not know. */
    static final CommandInfo UNKNOWN = new CommandInfo("", Keys.FIRST, Blocking.NONE, null, Role.NONE);

    private static final Map<String, CommandInfo> TABLE = build();

    /** The command name, in upper case; empty if it is not in the table. */
    final String name;
    final Keys keys;
    final Blocking blocking;
    /** The kind of subscription command, in lower case, or null. */
    final String subscriptionKind;
    final Role role;

    private CommandInfo(String name, Keys keys, Blocking blocking, String subscriptionKind, Role role) {
        this.name = name;
        this.keys = keys;
        this.blocking = blocking;
        this.subscriptionKind = subscriptionKind;
        this.role = role;
    }

    /** The descriptor of the named command, in any case; never null. */
    static CommandInfo of(String command) {
        CommandInfo info = TABLE.get(command);
        return info != null ? info : UNKNOWN;
    }

    // The table is searched ignoring case, which needs no copy of the name
    // as upper case; names are few, so the search is short.
    private static Map<String, CommandInfo> build() {
        Map<String, Mutable> all = new HashMap<String, Mutable>();
        keys(all, Keys.NONE, "PING", "ECHO", "AUTH", "SELECT", "QUIT", "HELLO", "CLIENT", "RESET",
                "DBSIZE", "FLUSHDB", "FLUSHALL", "INFO", "TIME", "KEYS", "SCAN",
                "MULTI", "EXEC", "DISCARD", "UNWATCH", "SUBSCRIBE", "PSUBSCRIBE",
                "UNSUBSCRIBE", "PUNSUBSCRIBE", "PUBLISH", "SCRIPT", "CLUSTER",
                "ASKING", "READONLY", "READWRITE", "WAIT", "COMMAND", "CONFIG",
                "SAVE", "BGSAVE", "LASTSAVE", "SHUTDOWN", "SLOWLOG", "PUBSUB");
        keys(all, Keys.ALL, "MGET", "DEL", "EXISTS", "UNLINK", "WATCH", "TOUCH", "SINTER",
                "SUNION", "SDIFF", "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE",
                "PFCOUNT", "PFMERGE", "SINTERCARD", "SSUBSCRIBE", "SUNSUBSCRIBE");
        keys(all, Keys.ALTERNATE, "MSET", "MSETNX");
        keys(all, Keys.FIRST_TWO, "RENAME", "RENAMENX", "BLMOVE", "LMOVE", "SMOVE", "RPOPLPUSH",
                "BRPOPLPUSH", "COPY");
        keys(all, Keys.ALL_BUT_LAST, "BLPOP", "BRPOP");
        keys(all, Keys.SECOND, "XGROUP", "XINFO", "OBJECT");
        keys(all, Keys.NUMKEYS_SCRIPT, "EVAL", "EVALSHA", "EVAL_RO", "EVALSHA_RO", "FCALL", "FCALL_RO");
        keys(all, Keys.NUMKEYS_STORE, "ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE");
        keys(all, Keys.STREAMS, "XREAD", "XREADGROUP");

        blocking(all, Blocking.SECONDS_LAST, "BLPOP", "BRPOP", "BZPOPMIN", "BZPOPMAX", "BRPOPLPUSH", "BLMOVE");
        blocking(all, Blocking.SECONDS_FIRST, "BLMPOP", "BZMPOP");
        blocking(all, Blocking.MILLIS_LAST, "WAIT", "WAITAOF");
        blocking(all, Blocking.BLOCK_OPTION, "XREAD", "XREADGROUP");

        for (String kind : new String[] { "subscribe", "psubscribe", "ssubscribe",
                "unsubscribe", "punsubscribe", "sunsubscribe" }) {
            entry(all, kind.toUpperCase(java.util.Locale.ROOT)).subscriptionKind = kind;
        }

        entry(all, "MULTI").role = Role.MULTI;
        entry(all, "EXEC").role = Role.EXEC;
        entry(all, "DISCARD").role = Role.DISCARD;
        entry(all, "WATCH").role = Role.WATCH;
        entry(all, "UNWATCH").role = Role.UNWATCH;
        entry(all, "WAIT").role = Role.WAIT;
        entry(all, "WAITAOF").role = Role.WAIT;

        Map<String, CommandInfo> table = new TreeMap<String, CommandInfo>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Mutable> e : all.entrySet()) {
            Mutable m = e.getValue();
            table.put(e.getKey(), new CommandInfo(e.getKey(), m.keys, m.blocking, m.subscriptionKind, m.role));
        }
        return Collections.unmodifiableMap(table);
    }

    private static final class Mutable {
        Keys keys = Keys.FIRST;
        Blocking blocking = Blocking.NONE;
        String subscriptionKind;
        Role role = Role.NONE;
    }

    private static Mutable entry(Map<String, Mutable> all, String name) {
        Mutable m = all.get(name);
        if (m == null) {
            m = new Mutable();
            all.put(name, m);
        }
        return m;
    }

    private static void keys(Map<String, Mutable> all, Keys keys, String... names) {
        for (String name : names) {
            entry(all, name).keys = keys;
        }
    }

    private static void blocking(Map<String, Mutable> all, Blocking blocking, String... names) {
        for (String name : names) {
            entry(all, name).blocking = blocking;
        }
    }
}
