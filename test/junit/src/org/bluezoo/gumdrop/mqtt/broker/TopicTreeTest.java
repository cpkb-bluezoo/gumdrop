/*
 * TopicTreeTest.java
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

package org.bluezoo.gumdrop.mqtt.broker;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.mqtt.codec.QoS;

public class TopicTreeTest {

    private TopicTree tree;

    @Before
    public void setUp() {
        tree = new TopicTree();
    }

    @Test
    public void testExactMatch() {
        tree.subscribe("sensors/temp", "c1", QoS.AT_LEAST_ONCE);
        Map<String, QoS> result = tree.matchWithMaxQoS("sensors/temp");
        assertEquals(1, result.size());
        assertEquals(QoS.AT_LEAST_ONCE, result.get("c1"));
    }

    @Test
    public void testExactMatchNoMatch() {
        tree.subscribe("sensors/temp", "c1", QoS.AT_LEAST_ONCE);
        Map<String, QoS> result = tree.matchWithMaxQoS("sensors/humidity");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testSingleLevelWildcard() {
        tree.subscribe("sensors/+/temp", "c1", QoS.AT_MOST_ONCE);
        assertTrue(tree.matchWithMaxQoS("sensors/room1/temp").containsKey("c1"));
        assertTrue(tree.matchWithMaxQoS("sensors/room2/temp").containsKey("c1"));
        assertTrue(tree.matchWithMaxQoS("sensors//temp").containsKey("c1"));
        assertFalse(tree.matchWithMaxQoS("sensors/temp").containsKey("c1"));
        assertFalse(tree.matchWithMaxQoS("sensors/room1/humidity").containsKey("c1"));
    }

    @Test
    public void testMultiLevelWildcard() {
        tree.subscribe("sensors/#", "c1", QoS.AT_MOST_ONCE);
        assertTrue(tree.matchWithMaxQoS("sensors").containsKey("c1"));
        assertTrue(tree.matchWithMaxQoS("sensors/temp").containsKey("c1"));
        assertTrue(tree.matchWithMaxQoS("sensors/room/temp").containsKey("c1"));
        assertFalse(tree.matchWithMaxQoS("other/topic").containsKey("c1"));
    }

    @Test
    public void testHashAlone() {
        tree.subscribe("#", "c1", QoS.AT_MOST_ONCE);
        assertTrue(tree.matchWithMaxQoS("any").containsKey("c1"));
        assertTrue(tree.matchWithMaxQoS("any/topic/here").containsKey("c1"));
    }

    @Test
    public void testDollarTopicExcludesRootWildcards() {
        tree.subscribe("#", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("+/info", "c2", QoS.AT_MOST_ONCE);
        tree.subscribe("$SYS/info", "c3", QoS.AT_MOST_ONCE);

        Map<String, QoS> result = tree.matchWithMaxQoS("$SYS/info");
        assertFalse("# should not match $SYS topics", result.containsKey("c1"));
        assertFalse("+ should not match $SYS topics", result.containsKey("c2"));
        assertTrue("Explicit $SYS filter should match", result.containsKey("c3"));
    }

    @Test
    public void testMultipleSubscribers() {
        tree.subscribe("topic/a", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("topic/a", "c2", QoS.AT_LEAST_ONCE);
        tree.subscribe("topic/a", "c3", QoS.EXACTLY_ONCE);

        Map<String, QoS> result = tree.matchWithMaxQoS("topic/a");
        assertEquals(3, result.size());
    }

    @Test
    public void testOverlappingSubscriptions() {
        tree.subscribe("a/b", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("a/+", "c1", QoS.AT_LEAST_ONCE);
        tree.subscribe("a/#", "c1", QoS.EXACTLY_ONCE);

        Map<String, QoS> result = tree.matchWithMaxQoS("a/b");
        assertEquals(1, result.size());
        assertEquals(QoS.EXACTLY_ONCE, result.get("c1")); // max QoS
    }

    @Test
    public void testUnsubscribe() {
        tree.subscribe("topic/a", "c1", QoS.AT_LEAST_ONCE);
        tree.unsubscribe("topic/a", "c1");
        assertTrue(tree.matchWithMaxQoS("topic/a").isEmpty());
    }

    @Test
    public void testUnsubscribeAll() {
        tree.subscribe("a/b", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("c/d", "c1", QoS.AT_LEAST_ONCE);
        tree.subscribe("a/b", "c2", QoS.AT_MOST_ONCE);

        tree.unsubscribeAll("c1");

        assertTrue(tree.matchWithMaxQoS("a/b").containsKey("c2"));
        assertFalse(tree.matchWithMaxQoS("a/b").containsKey("c1"));
        assertFalse(tree.matchWithMaxQoS("c/d").containsKey("c1"));
    }

    @Test
    public void testEmptyLevel() {
        tree.subscribe("a//b", "c1", QoS.AT_MOST_ONCE);
        assertTrue(tree.matchWithMaxQoS("a//b").containsKey("c1"));
        assertFalse(tree.matchWithMaxQoS("a/b").containsKey("c1"));
    }

    // ─── Reverse-index / pruning regression tests (issue #144) ──────────

    @Test
    public void testUnsubscribeThenResubscribeSameFilter() {
        // Exercises node pruning (unsubscribe should remove the now-empty
        // path) followed by re-creation of that same path.
        tree.subscribe("a/b/c", "c1", QoS.AT_MOST_ONCE);
        tree.unsubscribe("a/b/c", "c1");
        assertTrue(tree.matchWithMaxQoS("a/b/c").isEmpty());

        tree.subscribe("a/b/c", "c1", QoS.AT_LEAST_ONCE);
        Map<String, QoS> result = tree.matchWithMaxQoS("a/b/c");
        assertEquals(QoS.AT_LEAST_ONCE, result.get("c1"));
    }

    @Test
    public void testUnsubscribeDoesNotPruneSharedAncestor() {
        // "a/b" and "a/c" share the "a" node - unsubscribing c1 from "a/b"
        // must not disturb c2's subscription to "a/c".
        tree.subscribe("a/b", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("a/c", "c2", QoS.AT_MOST_ONCE);

        tree.unsubscribe("a/b", "c1");

        assertTrue(tree.matchWithMaxQoS("a/b").isEmpty());
        assertTrue(tree.matchWithMaxQoS("a/c").containsKey("c2"));
    }

    @Test
    public void testUnsubscribeAllThenResubscribe() {
        tree.subscribe("a/b", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("c/d/e", "c1", QoS.AT_LEAST_ONCE);

        tree.unsubscribeAll("c1");
        assertTrue(tree.matchWithMaxQoS("a/b").isEmpty());
        assertTrue(tree.matchWithMaxQoS("c/d/e").isEmpty());

        // Client can subscribe again after a full unsubscribeAll, and the
        // reverse index (clientFilters) must reflect the new subscription
        // so a subsequent unsubscribeAll finds it.
        tree.subscribe("a/b", "c1", QoS.AT_MOST_ONCE);
        assertTrue(tree.matchWithMaxQoS("a/b").containsKey("c1"));
        tree.unsubscribeAll("c1");
        assertTrue(tree.matchWithMaxQoS("a/b").isEmpty());
    }

    @Test
    public void testUnsubscribeAllOnlyAffectsThatClient() {
        tree.subscribe("a/b", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("a/b", "c2", QoS.AT_LEAST_ONCE);
        tree.subscribe("x/y", "c2", QoS.EXACTLY_ONCE);

        tree.unsubscribeAll("c1");

        Map<String, QoS> result = tree.matchWithMaxQoS("a/b");
        assertFalse(result.containsKey("c1"));
        assertEquals(QoS.AT_LEAST_ONCE, result.get("c2"));
        assertTrue(tree.matchWithMaxQoS("x/y").containsKey("c2"));
    }

    @Test
    public void testUnsubscribeUnknownFilterIsNoOp() {
        tree.subscribe("a/b", "c1", QoS.AT_MOST_ONCE);
        tree.unsubscribe("never/subscribed", "c1");
        assertTrue(tree.matchWithMaxQoS("a/b").containsKey("c1"));
    }

    // ─── Publish-routing result map (issue #327) ────────────────────────

    @Test
    public void testMatchWithMaxQoSReturnsPlainHashMap() {
        tree.subscribe("topic/a", "c1", QoS.AT_MOST_ONCE);
        tree.subscribe("topic/a", "c2", QoS.AT_LEAST_ONCE);

        Map<String, QoS> result = tree.matchWithMaxQoS("topic/a");

        assertFalse("match result is built and consumed on one thread only",
                result instanceof ConcurrentHashMap);
        assertEquals(QoS.AT_MOST_ONCE, result.get("c1"));
        assertEquals(QoS.AT_LEAST_ONCE, result.get("c2"));
    }

    @Test
    public void testResolveSubscribersRoutingUnchanged() {
        SubscriptionManager manager = new SubscriptionManager();
        manager.subscribe("c1", "sensors/+/temp", QoS.AT_MOST_ONCE);
        manager.subscribe("c2", "sensors/room1/temp", QoS.EXACTLY_ONCE);
        manager.subscribe("c3", "other/#", QoS.AT_LEAST_ONCE);

        Map<String, QoS> matched = manager.resolveSubscribers("sensors/room1/temp");
        assertEquals(2, matched.size());
        assertEquals(QoS.AT_MOST_ONCE, matched.get("c1"));
        assertEquals(QoS.EXACTLY_ONCE, matched.get("c2"));
        assertFalse(matched.containsKey("c3"));
        assertFalse(matched instanceof ConcurrentHashMap);
    }
}
