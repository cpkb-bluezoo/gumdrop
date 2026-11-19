/*
 * ClusterCommandKeysTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link ClusterCommandKeys}: which arguments of each command are
 * keys, and the single-slot check.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterCommandKeysTest {

    private static byte[][] args(String... values) {
        byte[][] out = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    private static int[] keys(String command, String... values) {
        return ClusterCommandKeys.keyPositions(command, args(values));
    }

    private static int slot(String key) {
        return ClusterSlots.slot(key.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertKeys(int[] actual, int... expected) {
        assertArrayEquals(expected, actual);
    }

    private static SentCommand text(String command, String... values) {
        return new SentCommand(null, command, values, null);
    }

    private static void assertSameSlotAsBytes(String command, String... values) {
        assertEquals(command, ClusterCommandKeys.slotOf(command, args(values)),
                ClusterCommandKeys.slotOf(text(command, values)));
        assertEquals(command + " as binary", ClusterCommandKeys.slotOf(command, args(values)),
                ClusterCommandKeys.slotOf(new SentCommand(null, command, null, args(values))));
    }

    @Test
    public void slotOfASentCommandMatchesTheSlotOfItsBytes() {
        assertSameSlotAsBytes("GET", "k");
        assertSameSlotAsBytes("SET", "{user1}.a", "value");
        assertSameSlotAsBytes("MSET", "{t}a", "1", "{t}b", "2");
        assertSameSlotAsBytes("MSET", "a", "1", "b", "2");
        assertSameSlotAsBytes("DEL", "{t}a", "{t}b");
        assertSameSlotAsBytes("EVAL", "return 1", "2", "{t}a", "{t}b", "x");
        assertSameSlotAsBytes("ZUNIONSTORE", "{t}dst", "2", "{t}a", "{t}b");
        assertSameSlotAsBytes("XREAD", "COUNT", "2", "STREAMS", "{t}a", "{t}b", "0", "0");
        assertSameSlotAsBytes("OBJECT", "ENCODING", "k");
        assertSameSlotAsBytes("BLPOP", "{t}a", "{t}b", "5");
        assertSameSlotAsBytes("SET", "k\u00e9y\u20ac", "v\u00e9");
        assertSameSlotAsBytes("PING");
        assertSameSlotAsBytes("EVAL", "s", "x");
    }

    /**
     * Only the keys are needed to find the slot: a large value must not be
     * copied (it used to be encoded to bytes whole, then again to be sent).
     */
    @Test
    public void slotOfASetDoesNotCopyTheValue() {
        StringBuilder sb = new StringBuilder(1 << 20);
        for (int i = 0; i < (1 << 20); i++) {
            sb.append('v');
        }
        SentCommand set = text("SET", "key", sb.toString());
        ClusterCommandKeys.slotOf(set); // warm up
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        ClusterCommandKeys.slotOf(set);
        long allocated = mx.getThreadAllocatedBytes(id) - before;
        assertTrue("allocated " + allocated + " bytes", allocated < 64 * 1024);
    }

    @Test
    public void singleKeyCommandsUseTheFirstArgument() {
        assertKeys(keys("GET", "k"), 0);
        assertKeys(keys("SET", "k", "v"), 0);
        assertKeys(keys("HSET", "k", "f", "v"), 0);
        assertKeys(keys("LPUSH", "k", "a", "b"), 0);
        assertKeys(keys("ZADD", "k", "1", "m"), 0);
        assertKeys(keys("HSCAN", "k", "0"), 0);
        assertKeys(keys("XADD", "k", "*", "f", "v"), 0);
        assertKeys(keys("INCRBY", "k", "5"), 0);
    }

    @Test
    public void commandNamesAreCaseInsensitive() {
        assertKeys(keys("get", "k"), 0);
        assertKeys(keys("Mget", "a", "b"), 0, 1);
    }

    @Test
    public void keylessCommandsHaveNoKeys() {
        assertKeys(keys("PING"));
        assertKeys(keys("PING", "hello"));
        assertKeys(keys("ECHO", "hello"));
        assertKeys(keys("INFO", "memory"));
        assertKeys(keys("DBSIZE"));
        assertKeys(keys("KEYS", "*"));
        assertKeys(keys("SCAN", "0"));
        assertKeys(keys("TIME"));
        assertKeys(keys("PUBLISH", "chan", "msg"));
        assertKeys(keys("SUBSCRIBE", "a", "b"));
        assertKeys(keys("MULTI"));
        assertKeys(keys("EXEC"));
        assertKeys(keys("AUTH", "pw"));
        assertKeys(keys("SELECT", "0"));
        assertKeys(keys("CLIENT", "SETNAME", "x"));
    }

    @Test
    public void multiKeyCommandsTreatEveryArgumentAsAKey() {
        assertKeys(keys("MGET", "a", "b", "c"), 0, 1, 2);
        assertKeys(keys("DEL", "a", "b"), 0, 1);
        assertKeys(keys("EXISTS", "a", "b"), 0, 1);
        assertKeys(keys("WATCH", "a", "b"), 0, 1);
        assertKeys(keys("UNLINK", "a"), 0);
    }

    @Test
    public void msetTakesAlternateArgumentsAsKeys() {
        assertKeys(keys("MSET", "a", "1", "b", "2", "c", "3"), 0, 2, 4);
        assertKeys(keys("MSETNX", "a", "1"), 0);
    }

    @Test
    public void blockingPopsExcludeTheTrailingTimeout() {
        assertKeys(keys("BLPOP", "a", "b", "0.5"), 0, 1);
        assertKeys(keys("BRPOP", "a", "5"), 0);
    }

    @Test
    public void twoKeyCommandsTakeTheFirstTwoArguments() {
        assertKeys(keys("RENAME", "a", "b"), 0, 1);
        assertKeys(keys("RENAMENX", "a", "b"), 0, 1);
        assertKeys(keys("BLMOVE", "src", "dst", "LEFT", "RIGHT", "0"), 0, 1);
        assertKeys(keys("SMOVE", "src", "dst", "m"), 0, 1);
    }

    @Test
    public void evalKeysAreCountedByNumkeys() {
        assertKeys(keys("EVAL", "return 1", "2", "k1", "k2", "arg"), 2, 3);
        assertKeys(keys("EVALSHA", "abc", "1", "k1", "a1", "a2"), 2);
        assertKeys(keys("EVAL", "return 1", "0", "arg"));
    }

    @Test
    public void evalWithAMalformedNumkeysYieldsNoKeys() {
        assertKeys(keys("EVAL", "return 1", "x", "k"));
        assertKeys(keys("EVAL", "return 1", "-1", "k"));
        assertKeys(keys("EVAL", "return 1"));
    }

    @Test
    public void evalWithNumkeysBeyondTheArgumentsIsClamped() {
        assertKeys(keys("EVAL", "s", "9", "k1", "k2"), 2, 3);
    }

    @Test
    public void storeCommandsIncludeTheDestinationAndNumkeys() {
        assertKeys(keys("ZUNIONSTORE", "dst", "2", "a", "b", "WEIGHTS", "1", "2"), 0, 2, 3);
        assertKeys(keys("ZINTERSTORE", "dst", "1", "a"), 0, 2);
    }

    @Test
    public void setStoreCommandsTreatEveryArgumentAsAKey() {
        assertKeys(keys("SINTERSTORE", "dst", "a", "b"), 0, 1, 2);
        assertKeys(keys("SUNION", "a", "b"), 0, 1);
        assertKeys(keys("SDIFF", "a"), 0);
    }

    @Test
    public void xreadKeysFollowTheStreamsToken() {
        assertKeys(keys("XREAD", "COUNT", "10", "BLOCK", "0", "STREAMS", "s1", "s2", "0", "0"), 5, 6);
        assertKeys(keys("XREAD", "STREAMS", "s1", "$"), 1);
    }

    @Test
    public void xreadGroupKeysFollowTheStreamsToken() {
        assertKeys(keys("XREADGROUP", "GROUP", "g", "c", "STREAMS", "s1", ">"), 4);
    }

    @Test
    public void xreadWithoutStreamsHasNoKeys() {
        assertKeys(keys("XREAD", "COUNT", "1"));
    }

    @Test
    public void subcommandStyleCommandsKeyOnTheSecondArgument() {
        assertKeys(keys("XGROUP", "CREATE", "s", "g", "$"), 1);
        assertKeys(keys("XINFO", "STREAM", "s"), 1);
        assertKeys(keys("OBJECT", "ENCODING", "k"), 1);
    }

    @Test
    public void shardedPubSubChannelsAreKeys() {
        assertKeys(keys("SPUBLISH", "ch", "msg"), 0);
        assertKeys(keys("SSUBSCRIBE", "a", "b"), 0, 1);
        assertKeys(keys("SUNSUBSCRIBE", "a"), 0);
    }

    @Test
    public void unknownCommandsFallBackToTheFirstArgument() {
        assertKeys(keys("FUTURECMD", "k", "x"), 0);
        assertKeys(keys("FUTURECMD"));
    }

    @Test
    public void missingArgumentsNeverProduceOutOfRangePositions() {
        assertKeys(keys("GET"));
        assertKeys(keys("RENAME", "a"), 0);
        assertKeys(keys("BLPOP", "0"));
        assertKeys(keys("MSET"));
        assertKeys(keys("XGROUP", "CREATE"));
    }

    @Test
    public void slotOfASingleKeyIsItsSlot() {
        assertEquals(slot("foo"), ClusterCommandKeys.slotOf("GET", args("foo")));
    }

    @Test
    public void slotOfAKeylessCommandIsNoKey() {
        assertEquals(ClusterCommandKeys.NO_KEY, ClusterCommandKeys.slotOf("PING", args()));
    }

    @Test
    public void keysSharingAHashTagShareASlot() {
        assertEquals(slot("user1"),
                ClusterCommandKeys.slotOf("MGET", args("{user1}.a", "{user1}.b")));
        assertEquals(slot("user1"),
                ClusterCommandKeys.slotOf("MSET", args("{user1}.a", "1", "{user1}.b", "2")));
    }

    @Test
    public void keysInDifferentSlotsAreCrossSlot() {
        assertEquals(ClusterCommandKeys.CROSS_SLOT, ClusterCommandKeys.slotOf("MGET", args("foo", "bar")));
        assertEquals(ClusterCommandKeys.CROSS_SLOT,
                ClusterCommandKeys.slotOf("RENAME", args("foo", "bar")));
        assertEquals(ClusterCommandKeys.CROSS_SLOT,
                ClusterCommandKeys.slotOf("EVAL", args("s", "2", "foo", "bar")));
    }

    @Test
    public void mixedValuesOfMsetDoNotAffectTheSlot() {
        // values differ wildly but only keys count
        assertEquals(slot("{t}a"),
                ClusterCommandKeys.slotOf("MSET", args("{t}a", "foo", "{t}b", "bar")));
    }

    private static long allocatedBy(Runnable work) {
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        for (int i = 0; i < 2000; i++) { // warm up
            work.run();
        }
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 1000; i++) {
            work.run();
        }
        return mx.getThreadAllocatedBytes(id) - before;
    }

    /**
     * Work out which arguments are keys without upper-casing the command
     * name or making an array of positions: that was done for every
     * command, and was most of what finding the slot of a GET allocated.
     */
    @Test
    public void findingTheSlotOfASingleKeyCommandAllocatesLittle() {
        final SentCommand get = text("get", "k");
        final int[] sink = new int[1];
        long allocated = allocatedBy(new Runnable() {
            public void run() {
                sink[0] += ClusterCommandKeys.slotOf(get);
            }
        });
        // per call: the key's bytes and the view of the arguments
        assertTrue("allocated " + allocated + " bytes in 1000 calls, " + sink[0],
                allocated <= 1000 * 72);
    }

    /** The blocking rule is found without upper-casing the command name. */
    @Test
    public void classifyingACommandForTimeoutsAllocatesNothing() {
        final String[] args = { "k" };
        final long[] sink = new long[1];
        long allocated = allocatedBy(new Runnable() {
            public void run() {
                sink[0] += CommandTimeouts.blockingMillis("get", args, null);
            }
        });
        assertTrue("allocated " + allocated + " bytes in 1000 calls, " + sink[0], allocated < 1024);
    }
}
