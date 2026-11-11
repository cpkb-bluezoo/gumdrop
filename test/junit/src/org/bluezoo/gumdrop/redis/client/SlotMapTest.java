/*
 * SlotMapTest.java
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * {@link SlotMap#parse} against replies shaped like {@code CLUSTER SLOTS},
 * and the map's lookups and updates.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SlotMapTest {

    private static RespValue bulk(String s) {
        return RespValue.bulkString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static RespValue arr(RespValue... items) {
        List<RespValue> list = new ArrayList<RespValue>();
        for (RespValue v : items) {
            list.add(v);
        }
        return RespValue.array(list);
    }

    private static RespValue node(String host, int port, String id) {
        return id == null
                ? arr(bulk(host), RespValue.integer(port))
                : arr(bulk(host), RespValue.integer(port), bulk(id));
    }

    private static RespValue range(int from, int to, RespValue master, RespValue... replicas) {
        List<RespValue> parts = new ArrayList<RespValue>();
        parts.add(RespValue.integer(from));
        parts.add(RespValue.integer(to));
        parts.add(master);
        for (RespValue r : replicas) {
            parts.add(r);
        }
        return RespValue.array(parts);
    }

    private static RespValue threeMasters() {
        return arr(
                range(0, 5460, node("10.0.0.1", 7000, "id1"), node("10.0.0.4", 7003, "id4")),
                range(5461, 10922, node("10.0.0.2", 7001, "id2")),
                range(10923, 16383, node("10.0.0.3", 7002, "id3")));
    }

    @Test
    public void slotsAreAssignedToTheirMasters() {
        SlotMap map = SlotMap.parse(threeMasters(), "seed");
        assertEquals(7000, map.nodeFor(0).getPort());
        assertEquals(7000, map.nodeFor(5460).getPort());
        assertEquals(7001, map.nodeFor(5461).getPort());
        assertEquals(7001, map.nodeFor(10922).getPort());
        assertEquals(7002, map.nodeFor(10923).getPort());
        assertEquals(7002, map.nodeFor(16383).getPort());
        assertTrue(map.isComplete());
    }

    @Test
    public void nodeCarriesAddressAndId() {
        SlotMap map = SlotMap.parse(threeMasters(), "seed");
        ClusterNode n = map.nodeFor(6000);
        assertEquals("10.0.0.2", n.getHost());
        assertEquals(7001, n.getPort());
        assertEquals("id2", n.getId());
        assertEquals("10.0.0.2:7001", n.getAddress());
    }

    @Test
    public void replicasDoNotServeSlots() {
        SlotMap map = SlotMap.parse(threeMasters(), "seed");
        Collection<ClusterNode> nodes = map.nodes();
        assertEquals(3, nodes.size());
        for (ClusterNode n : nodes) {
            assertNotEquals(7003, n.getPort());
        }
    }

    @Test
    public void aNodeServingSeveralRangesIsOneObject() {
        RespValue reply = arr(
                range(0, 99, node("10.0.0.1", 7000, "id1")),
                range(100, 199, node("10.0.0.2", 7001, "id2")),
                range(200, 299, node("10.0.0.1", 7000, "id1")));
        SlotMap map = SlotMap.parse(reply, "seed");
        assertSame(map.nodeFor(0), map.nodeFor(250));
        assertEquals(2, map.nodes().size());
    }

    @Test
    public void blankHostMeansTheNodeThatWasAsked() {
        RespValue reply = arr(range(0, 16383, node("", 7000, "id1")));
        SlotMap map = SlotMap.parse(reply, "seed.example");
        assertEquals("seed.example", map.nodeFor(1).getHost());
        assertEquals(7000, map.nodeFor(1).getPort());
    }

    @Test
    public void nullHostMeansTheNodeThatWasAsked() {
        RespValue reply = arr(range(0, 16383,
                arr(RespValue.resp3Null(), RespValue.integer(7000), bulk("id1"))));
        SlotMap map = SlotMap.parse(reply, "seed.example");
        assertEquals("seed.example", map.nodeFor(1).getHost());
    }

    @Test
    public void nilBulkHostMeansTheNodeThatWasAsked() {
        RespValue reply = arr(range(0, 16383,
                arr(RespValue.nullValue(), RespValue.integer(7000))));
        SlotMap map = SlotMap.parse(reply, "seed.example");
        assertEquals("seed.example", map.nodeFor(1).getHost());
        assertNull(map.nodeFor(1).getId());
    }

    @Test
    public void nodeMetadataIsIgnored() {
        Map<RespValue, RespValue> meta = new LinkedHashMap<RespValue, RespValue>();
        meta.put(bulk("hostname"), bulk("redis-a"));
        RespValue master = arr(bulk("10.0.0.1"), RespValue.integer(7000), bulk("id1"),
                RespValue.map(meta));
        SlotMap map = SlotMap.parse(arr(range(0, 16383, master)), "seed");
        assertEquals("10.0.0.1", map.nodeFor(0).getHost());
        assertTrue(map.isComplete());
    }

    @Test
    public void ipv6HostsAreBracketedInTheAddress() {
        RespValue reply = arr(range(0, 16383, node("::1", 7000, null)));
        ClusterNode n = SlotMap.parse(reply, "seed").nodeFor(5);
        assertEquals("::1", n.getHost());
        assertEquals("[::1]:7000", n.getAddress());
    }

    @Test
    public void uncoveredSlotsHaveNoNode() {
        RespValue reply = arr(range(0, 99, node("10.0.0.1", 7000, "id1")));
        SlotMap map = SlotMap.parse(reply, "seed");
        assertNotNull(map.nodeFor(99));
        assertNull(map.nodeFor(100));
        assertNull(map.nodeFor(16383));
        assertFalse(map.isComplete());
    }

    @Test
    public void anEmptyReplyIsAnEmptyMap() {
        SlotMap map = SlotMap.parse(arr(), "seed");
        assertNull(map.nodeFor(0));
        assertFalse(map.isComplete());
        assertTrue(map.nodes().isEmpty());
    }

    @Test
    public void aNewMapIsEmpty() {
        SlotMap map = new SlotMap();
        assertNull(map.nodeFor(0));
        assertFalse(map.isComplete());
    }

    @Test
    public void setMovesASlotToAnotherNode() {
        SlotMap map = SlotMap.parse(threeMasters(), "seed");
        ClusterNode target = map.node("10.0.0.3", 7002);
        map.set(100, target);
        assertSame(target, map.nodeFor(100));
        assertEquals(7000, map.nodeFor(101).getPort());
    }

    @Test
    public void nodeLookupReturnsTheKnownObjectForAnAddress() {
        SlotMap map = SlotMap.parse(threeMasters(), "seed");
        assertSame(map.nodeFor(0), map.node("10.0.0.1", 7000));
        assertSame(map.node("10.9.9.9", 1), map.node("10.9.9.9", 1));
        assertNotSame(map.node("10.9.9.9", 1), map.node("10.9.9.9", 2));
    }

    @Test
    public void aNodeLearntFromARedirectCanServeSlots() {
        SlotMap map = SlotMap.parse(threeMasters(), "seed");
        ClusterNode fresh = map.node("10.0.0.9", 7009);
        map.set(42, fresh);
        assertSame(fresh, map.nodeFor(42));
        assertEquals(4, map.nodes().size());
    }

    @Test
    public void aNodeWithNoSlotsLeftIsNoLongerListed() {
        RespValue reply = arr(
                range(0, 99, node("10.0.0.1", 7000, "id1")),
                range(100, 199, node("10.0.0.2", 7001, "id2")));
        SlotMap map = SlotMap.parse(reply, "seed");
        ClusterNode one = map.nodeFor(0);
        for (int slot = 100; slot <= 199; slot++) {
            map.set(slot, one);
        }
        assertEquals(1, map.nodes().size());
    }

    // ── malformed replies ──

    @Test(expected = IllegalArgumentException.class)
    public void aNonArrayReplyIsRejected() {
        SlotMap.parse(bulk("nope"), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void anErrorReplyIsRejected() {
        SlotMap.parse(RespValue.error("CLUSTERDOWN The cluster is down"), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aRangeWithEndBeforeStartIsRejected() {
        SlotMap.parse(arr(range(10, 5, node("h", 1, null))), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSlotBeyondTheLastIsRejected() {
        SlotMap.parse(arr(range(0, 16384, node("h", 1, null))), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNegativeSlotIsRejected() {
        SlotMap.parse(arr(range(-1, 5, node("h", 1, null))), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aRangeWithNoMasterIsRejected() {
        SlotMap.parse(arr(arr(RespValue.integer(0), RespValue.integer(5))), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMasterWithNoPortIsRejected() {
        SlotMap.parse(arr(range(0, 5, arr(bulk("h")))), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMasterWithAnOutOfRangePortIsRejected() {
        SlotMap.parse(arr(range(0, 5, node("h", 70000, null))), "seed");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aRangeThatIsNotAnArrayIsRejected() {
        SlotMap.parse(arr(bulk("x")), "seed");
    }
}
