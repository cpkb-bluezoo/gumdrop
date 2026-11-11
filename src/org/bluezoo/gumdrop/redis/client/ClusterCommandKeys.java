/*
 * ClusterCommandKeys.java
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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which arguments of a command are keys, for routing in a Redis Cluster.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ClusterCommandKeys {

    /** {@link #slotOf} result: the command names no key. */
    static final int NO_KEY = -1;

    /** {@link #slotOf} result: the keys hash to more than one slot. */
    static final int CROSS_SLOT = -2;

    private static final int[] NONE = new int[0];

    private static final Set<String> KEYLESS = new HashSet<String>(Arrays.asList(
            "PING", "ECHO", "AUTH", "SELECT", "QUIT", "HELLO", "CLIENT", "RESET",
            "DBSIZE", "FLUSHDB", "FLUSHALL", "INFO", "TIME", "KEYS", "SCAN",
            "MULTI", "EXEC", "DISCARD", "UNWATCH", "SUBSCRIBE", "PSUBSCRIBE",
            "UNSUBSCRIBE", "PUNSUBSCRIBE", "PUBLISH", "SCRIPT", "CLUSTER",
            "ASKING", "READONLY", "READWRITE", "WAIT", "COMMAND", "CONFIG",
            "SAVE", "BGSAVE", "LASTSAVE", "SHUTDOWN", "SLOWLOG", "PUBSUB"));

    /** Every argument is a key. */
    private static final Set<String> ALL_KEYS = new HashSet<String>(Arrays.asList(
            "MGET", "DEL", "EXISTS", "UNLINK", "WATCH", "TOUCH", "SINTER",
            "SUNION", "SDIFF", "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE",
            "PFCOUNT", "PFMERGE", "SINTERCARD"));

    /** The first two arguments are keys. */
    private static final Set<String> TWO_KEYS = new HashSet<String>(Arrays.asList(
            "RENAME", "RENAMENX", "BLMOVE", "LMOVE", "SMOVE", "RPOPLPUSH",
            "BRPOPLPUSH", "COPY"));

    /** Alternate arguments (0, 2, 4, ...) are keys. */
    private static final Set<String> ALTERNATE = new HashSet<String>(Arrays.asList(
            "MSET", "MSETNX"));

    /** The key follows a subcommand. */
    private static final Set<String> AFTER_SUBCOMMAND = new HashSet<String>(Arrays.asList(
            "XGROUP", "XINFO", "OBJECT"));

    /** Keys are given as every argument. */
    private static final Set<String> SHARD_CHANNELS = new HashSet<String>(Arrays.asList(
            "SSUBSCRIBE", "SUNSUBSCRIBE"));

    /** {@code script|numkeys|key...|arg...}: numkeys is the second argument. */
    private static final Set<String> NUMKEYS_SCRIPT = new HashSet<String>(Arrays.asList(
            "EVAL", "EVALSHA", "EVAL_RO", "EVALSHA_RO", "FCALL", "FCALL_RO"));

    /** {@code dest|numkeys|key...}: destination plus numkeys keys. */
    private static final Set<String> STORE_NUMKEYS = new HashSet<String>(Arrays.asList(
            "ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE"));

    private ClusterCommandKeys() {
    }

    /**
     * Indexes into {@code args} of the arguments that are keys. A command
     * this table does not know is assumed to take its key first, which is
     * the convention of nearly every Redis command.
     */
    static int[] keyPositions(String command, byte[][] args) {
        String name = command.toUpperCase(Locale.ROOT);
        int n = args.length;
        if (KEYLESS.contains(name)) {
            return NONE;
        }
        if (ALL_KEYS.contains(name) || SHARD_CHANNELS.contains(name)) {
            return range(0, n, 1);
        }
        if (ALTERNATE.contains(name)) {
            return range(0, n, 2);
        }
        if (TWO_KEYS.contains(name)) {
            return range(0, Math.min(2, n), 1);
        }
        if (name.equals("BLPOP") || name.equals("BRPOP")) {
            return range(0, n - 1, 1); // the last argument is the timeout
        }
        if (AFTER_SUBCOMMAND.contains(name)) {
            return n >= 2 ? new int[] { 1 } : NONE;
        }
        if (NUMKEYS_SCRIPT.contains(name)) {
            return numkeys(args, 1, 2, false);
        }
        if (STORE_NUMKEYS.contains(name)) {
            return numkeys(args, 1, 2, true);
        }
        if (name.equals("XREAD") || name.equals("XREADGROUP")) {
            return streamKeys(args);
        }
        return n > 0 ? new int[] { 0 } : NONE;
    }

    /**
     * The single slot all of the command's keys hash to, {@link #NO_KEY}
     * if it has none, or {@link #CROSS_SLOT} if they differ.
     */
    static int slotOf(String command, byte[][] args) {
        int[] positions = keyPositions(command, args);
        int slot = NO_KEY;
        for (int i = 0; i < positions.length; i++) {
            int s = ClusterSlots.slot(args[positions[i]]);
            if (slot == NO_KEY) {
                slot = s;
            } else if (slot != s) {
                return CROSS_SLOT;
            }
        }
        return slot;
    }

    private static int[] range(int from, int to, int step) {
        if (to <= from) {
            return NONE;
        }
        int[] out = new int[(to - from + step - 1) / step];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i * step;
        }
        return out;
    }

    // numkeys is at args[countAt] and the keys start at args[firstKey]; the
    // count is clamped to the arguments present (the server rejects a bad
    // count itself). With withDestination, args[0] is a key as well.
    private static int[] numkeys(byte[][] args, int countAt, int firstKey,
                                 boolean withDestination) {
        int count = 0;
        if (args.length > countAt) {
            try {
                count = Integer.parseInt(new String(args[countAt], StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                count = 0;
            }
        }
        int last = Math.min(args.length, firstKey + Math.max(count, 0));
        int[] keys = range(firstKey, last, 1);
        if (!withDestination || args.length == 0) {
            return keys;
        }
        int[] out = new int[keys.length + 1];
        out[0] = 0;
        System.arraycopy(keys, 0, out, 1, keys.length);
        return out;
    }

    // XREAD/XREADGROUP: STREAMS key... id..., the keys being the first half
    // of what follows the STREAMS token.
    private static int[] streamKeys(byte[][] args) {
        for (int i = 0; i < args.length; i++) {
            String token = new String(args[i], StandardCharsets.US_ASCII);
            if (token.equalsIgnoreCase("STREAMS")) {
                int remaining = args.length - i - 1;
                return range(i + 1, i + 1 + remaining / 2, 1);
            }
        }
        return NONE;
    }
}
