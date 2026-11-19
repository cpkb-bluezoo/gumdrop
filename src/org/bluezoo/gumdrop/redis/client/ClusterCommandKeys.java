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
    private static final char[] STREAMS = { 'S', 'T', 'R', 'E', 'A', 'M', 'S' };

    private ClusterCommandKeys() {
    }

    /**
     * Indexes into {@code args} of the arguments that are keys. A command
     * this table does not know is assumed to take its key first, which is
     * the convention of nearly every Redis command.
     */
    static int[] keyPositions(String command, byte[][] args) {
        return keyPositions(CommandInfo.of(command), new BinaryArgs(args));
    }

    private static int[] keyPositions(CommandInfo info, Args args) {
        int n = args.length();
        switch (info.keys) {
            case NONE:
                return NONE;
            case ALL:
                return range(0, n, 1);
            case ALTERNATE:
                return range(0, n, 2);
            case FIRST_TWO:
                return range(0, Math.min(2, n), 1);
            case ALL_BUT_LAST:
                return range(0, n - 1, 1); // the last argument is the timeout
            case SECOND:
                return n >= 2 ? new int[] { 1 } : NONE;
            case NUMKEYS_SCRIPT:
                return numkeys(args, 1, 2, false);
            case NUMKEYS_STORE:
                return numkeys(args, 1, 2, true);
            case STREAMS:
                return streamKeys(args);
            default:
                return n > 0 ? new int[] { 0 } : NONE;
        }
    }

    /**
     * The single slot all of the command's keys hash to, {@link #NO_KEY}
     * if it has none, or {@link #CROSS_SLOT} if they differ.
     */
    static int slotOf(String command, byte[][] args) {
        return slotOf(CommandInfo.of(command), new BinaryArgs(args));
    }

    private static int slotOf(CommandInfo info, Args args) {
        // the common layouts are read in place, without a list of positions
        switch (info.keys) {
            case NONE:
                return NO_KEY;
            case FIRST:
                return args.length() > 0 ? ClusterSlots.slot(args.bytes(0)) : NO_KEY;
            default:
                break;
        }
        int[] positions = keyPositions(info, args);
        int slot = NO_KEY;
        for (int i = 0; i < positions.length; i++) {
            int s = ClusterSlots.slot(args.bytes(positions[i]));
            if (slot == NO_KEY) {
                slot = s;
            } else if (slot != s) {
                return CROSS_SLOT;
            }
        }
        return slot;
    }

    /** The slot of a command's keys, as for {@link #slotOf(String, byte[][])}. */
    static int slotOf(SentCommand command) {
        byte[][] binary = command.getBinaryArgs();
        if (binary != null) {
            return slotOf(command.info(), new BinaryArgs(binary));
        }
        String[] text = command.getTextArgs();
        if (text == null) {
            text = new String[0];
        }
        return slotOf(command.info(), new TextArgs(text));
    }

    // The arguments of a command, as text or as bytes. Only the arguments
    // that turn out to be keys are ever turned into bytes, so a large value
    // is not copied to find the slot.
    private interface Args {
        int length();

        // the argument as a key count, as Integer.parseInt reads it; false
        // in countValid() if it is not one
        int count(int i);

        boolean countValid();

        // whether the argument is the STREAMS token, in any case
        boolean isStreams(int i);

        byte[] bytes(int i);
    }

    private static final class BinaryArgs implements Args {
        private final byte[][] args;

        BinaryArgs(byte[][] args) {
            this.args = args;
        }

        public int length() {
            return args.length;
        }

        private boolean valid;

        public boolean countValid() {
            return valid;
        }

        // Integer.parseInt over the bytes, without a string
        public int count(int i) {
            valid = false;
            byte[] a = args[i];
            int n = a.length;
            if (n == 0) {
                return 0;
            }
            int j = 0;
            boolean negative = false;
            if (a[0] == '-' || a[0] == '+') {
                negative = a[0] == '-';
                j = 1;
                if (n == 1) {
                    return 0;
                }
            }
            long result = 0;
            for (; j < n; j++) {
                int digit = a[j] - '0';
                if (digit < 0 || digit > 9) {
                    return 0;
                }
                result = result * 10 + digit;
                if (result > 2147483648L) {
                    return 0;
                }
            }
            if (negative) {
                result = -result;
            } else if (result > Integer.MAX_VALUE) {
                return 0;
            }
            valid = true;
            return (int) result;
        }

        public boolean isStreams(int i) {
            byte[] a = args[i];
            if (a.length != STREAMS.length) {
                return false;
            }
            for (int j = 0; j < a.length; j++) {
                if (Character.toUpperCase((char) (a[j] & 0xFF)) != STREAMS[j]) {
                    return false;
                }
            }
            return true;
        }

        public byte[] bytes(int i) {
            return args[i];
        }
    }

    private static final class TextArgs implements Args {
        private final String[] args;

        TextArgs(String[] args) {
            this.args = args;
        }

        public int length() {
            return args.length;
        }

        private boolean valid;

        public boolean countValid() {
            return valid;
        }

        public int count(int i) {
            valid = false;
            try {
                int n = Integer.parseInt(args[i]);
                valid = true;
                return n;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public boolean isStreams(int i) {
            return args[i].equalsIgnoreCase("STREAMS");
        }

        public byte[] bytes(int i) {
            return args[i].getBytes(StandardCharsets.UTF_8);
        }
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
    private static int[] numkeys(Args args, int countAt, int firstKey,
                                 boolean withDestination) {
        int count = 0;
        if (args.length() > countAt) {
            count = args.count(countAt);
            if (!args.countValid()) {
                count = 0;
            }
        }
        int last = Math.min(args.length(), firstKey + Math.max(count, 0));
        int[] keys = range(firstKey, last, 1);
        if (!withDestination || args.length() == 0) {
            return keys;
        }
        int[] out = new int[keys.length + 1];
        out[0] = 0;
        System.arraycopy(keys, 0, out, 1, keys.length);
        return out;
    }

    // XREAD/XREADGROUP: STREAMS key... id..., the keys being the first half
    // of what follows the STREAMS token.
    private static int[] streamKeys(Args args) {
        for (int i = 0; i < args.length(); i++) {
            if (args.isStreams(i)) {
                int remaining = args.length() - i - 1;
                return range(i + 1, i + 1 + remaining / 2, 1);
            }
        }
        return NONE;
    }
}
