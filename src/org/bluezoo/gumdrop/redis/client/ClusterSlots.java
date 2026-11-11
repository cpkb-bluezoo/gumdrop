/*
 * ClusterSlots.java
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

/**
 * Redis Cluster key-to-slot mapping.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ClusterSlots {

    /** Number of hash slots in a Redis Cluster. */
    static final int SLOT_COUNT = 16384;

    private static final int[] CRC_TABLE = buildTable();

    private ClusterSlots() {
    }

    // CRC-16/XMODEM: polynomial 0x1021, initial value 0, no reflection
    private static int[] buildTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
            }
            table[i] = crc & 0xFFFF;
        }
        return table;
    }

    /** CRC16 (XMODEM) of {@code data[from, to)}. */
    static int crc16(byte[] data, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc = ((crc << 8) ^ CRC_TABLE[((crc >> 8) ^ data[i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * The hash slot of {@code key}. If the key contains a non-empty
     * {@code {hash tag}} (between the first {@code '{'} and the first
     * {@code '}'} after it) only the tag is hashed, so related keys can be
     * forced into one slot.
     */
    static int slot(byte[] key) {
        int from = 0;
        int to = key.length;
        int open = indexOf(key, (byte) '{', 0);
        if (open >= 0) {
            int close = indexOf(key, (byte) '}', open + 1);
            if (close > open + 1) {
                from = open + 1;
                to = close;
            }
        }
        return crc16(key, from, to) % SLOT_COUNT;
    }

    private static int indexOf(byte[] data, byte value, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
