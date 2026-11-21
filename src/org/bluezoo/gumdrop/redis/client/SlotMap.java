/*
 * SlotMap.java
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

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Which node of a Redis Cluster serves each of the 16384 hash slots.
 *
 * <p>Not thread-safe: a cluster client uses it from its own loop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SlotMap {

    private final ClusterNode[] slots = new ClusterNode[ClusterSlots.SLOT_COUNT];
    private final Map<String, ClusterNode> known = new HashMap<String, ClusterNode>();
    private int assigned;
    // the slots each master serves, in the order the masters first took one
    private final Map<ClusterNode, int[]> slotCounts = new LinkedHashMap<ClusterNode, int[]>();
    // the keys of slotCounts as an array; null when they have changed
    private ClusterNode[] masters;

    SlotMap() {
    }

    /**
     * Builds a map from the reply to {@code CLUSTER SLOTS}: an array of
     * {@code [start, end, master, replica...]}, each node being
     * {@code [host, port, id, metadata]}. Replicas are ignored.
     *
     * @param reply the reply
     * @param defaultHost the host to use for a node the reply gives no
     *                    address for (the node that was asked)
     * @throws IllegalArgumentException if the reply is not well formed
     */
    static SlotMap parse(RespValue reply, String defaultHost) {
        if (reply == null || reply.isError() || !reply.isArray()) {
            throw new IllegalArgumentException("CLUSTER SLOTS reply is not an array");
        }
        SlotMap map = new SlotMap();
        List<RespValue> ranges = reply.asArray();
        for (int i = 0; i < ranges.size(); i++) {
            RespValue range = ranges.get(i);
            if (!range.isArray()) {
                throw new IllegalArgumentException("CLUSTER SLOTS range is not an array");
            }
            List<RespValue> parts = range.asArray();
            if (parts.size() < 3) {
                throw new IllegalArgumentException("CLUSTER SLOTS range has no master");
            }
            int from = number(parts.get(0), "slot");
            int to = number(parts.get(1), "slot");
            if (from < 0 || to >= ClusterSlots.SLOT_COUNT || to < from) {
                throw new IllegalArgumentException("CLUSTER SLOTS range out of bounds: " + from + "-" + to);
            }
            ClusterNode master = map.parseNode(parts.get(2), defaultHost);
            for (int slot = from; slot <= to; slot++) {
                map.set(slot, master);
            }
        }
        return map;
    }

    private ClusterNode parseNode(RespValue value, String defaultHost) {
        if (!value.isArray()) {
            throw new IllegalArgumentException("CLUSTER SLOTS node is not an array");
        }
        List<RespValue> fields = value.asArray();
        if (fields.size() < 2) {
            throw new IllegalArgumentException("CLUSTER SLOTS node has no port");
        }
        String host = null;
        RespValue hostValue = fields.get(0);
        if (!hostValue.isNull()) {
            host = hostValue.asString();
        }
        if (host == null || host.isEmpty()) {
            host = defaultHost;
        }
        int port = number(fields.get(1), "port");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("CLUSTER SLOTS port out of range: " + port);
        }
        String id = null;
        if (fields.size() > 2 && !fields.get(2).isNull() && !fields.get(2).isArray()
                && !fields.get(2).isMap()) {
            id = fields.get(2).asString();
        }
        String key = ClusterNode.address(host, port);
        ClusterNode existing = known.get(key);
        if (existing != null) {
            return existing;
        }
        ClusterNode created = new ClusterNode(host, port, id);
        known.put(key, created);
        return created;
    }

    private static int number(RespValue value, String what) {
        try {
            return (int) value.asLong();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("CLUSTER SLOTS " + what + " is not a number", e);
        }
    }

    /** The master serving {@code slot}, or null if none is known. */
    ClusterNode nodeFor(int slot) {
        return slots[slot];
    }

    /** Records that {@code node} now serves {@code slot}. */
    void set(int slot, ClusterNode node) {
        ClusterNode old = slots[slot];
        if (old == node) {
            return;
        }
        if (old == null) {
            assigned++;
        } else {
            release(old);
        }
        if (node == null) {
            assigned--;
        } else {
            int[] count = slotCounts.get(node);
            if (count == null) {
                count = new int[1];
                slotCounts.put(node, count);
                masters = null;
            }
            count[0]++;
        }
        slots[slot] = node;
    }

    // One slot fewer for the node; it stops being a master with none left.
    private void release(ClusterNode node) {
        int[] count = slotCounts.get(node);
        if (--count[0] == 0) {
            slotCounts.remove(node);
            masters = null;
        }
    }

    ClusterNode node(String host, int port) {
        String key = ClusterNode.address(host, port);
        ClusterNode node = known.get(key);
        if (node == null) {
            node = new ClusterNode(host, port, null);
            known.put(key, node);
        }
        return node;
    }

    /** The distinct masters that serve at least one slot. */
    Collection<ClusterNode> nodes() {
        return Arrays.asList(masters());
    }

    /** How many distinct masters serve at least one slot. */
    int masterCount() {
        return masters().length;
    }

    /** The master at the index, in the order they first took a slot. */
    ClusterNode masterAt(int index) {
        return masters()[index];
    }

    /** Whether the node serves at least one slot. */
    boolean serves(ClusterNode node) {
        return slotCounts.containsKey(node);
    }

    // The masters as an array, rebuilt only after one has come or gone.
    private ClusterNode[] masters() {
        ClusterNode[] current = masters;
        if (current == null) {
            current = slotCounts.keySet().toArray(new ClusterNode[0]);
            masters = current;
        }
        return current;
    }

    boolean isComplete() {
        return assigned == ClusterSlots.SLOT_COUNT;
    }
}
