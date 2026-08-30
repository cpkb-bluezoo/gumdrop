/*
 * SubscriptionManagerTest.java
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

import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Regression coverage for {@link SubscriptionManager}, including issue
 * #331: per-client subscription listing must come from {@link TopicTree}'s
 * reverse index rather than a duplicated map in the manager.
 */
public class SubscriptionManagerTest {

    private SubscriptionManager manager;

    @Before
    public void setUp() {
        manager = new SubscriptionManager();
    }

    @Test
    public void testGetSubscriptionsListsClientTopicFilters() {
        assertTrue(manager.getSubscriptions("c1").isEmpty());

        manager.subscribe("c1", "sensors/temp", QoS.AT_MOST_ONCE);
        manager.subscribe("c1", "sensors/#", QoS.AT_LEAST_ONCE);
        manager.subscribe("c2", "other/topic", QoS.EXACTLY_ONCE);

        Set<String> c1Filters = manager.getSubscriptions("c1");
        assertEquals(2, c1Filters.size());
        assertTrue(c1Filters.contains("sensors/temp"));
        assertTrue(c1Filters.contains("sensors/#"));
        assertEquals(Set.of("other/topic"), manager.getSubscriptions("c2"));
    }

    @Test
    public void testUnsubscribeUpdatesClientListing() {
        manager.subscribe("c1", "a/b", QoS.AT_MOST_ONCE);
        manager.subscribe("c1", "c/d", QoS.AT_LEAST_ONCE);

        manager.unsubscribe("c1", "a/b");

        Set<String> filters = manager.getSubscriptions("c1");
        assertEquals(Set.of("c/d"), filters);
    }

    @Test
    public void testRemoveClientClearsClientListing() {
        manager.subscribe("c1", "a/b", QoS.AT_MOST_ONCE);
        manager.subscribe("c2", "x/y", QoS.AT_LEAST_ONCE);

        manager.removeClient("c1");

        assertTrue(manager.getSubscriptions("c1").isEmpty());
        assertEquals(Set.of("x/y"), manager.getSubscriptions("c2"));
        assertTrue(manager.resolveSubscribers("a/b").isEmpty());
        assertTrue(manager.resolveSubscribers("x/y").containsKey("c2"));
    }

    @Test
    public void testResolveSubscribersUnchanged() {
        manager.subscribe("c1", "sensors/+/temp", QoS.AT_MOST_ONCE);
        manager.subscribe("c2", "sensors/room1/temp", QoS.EXACTLY_ONCE);
        manager.subscribe("c3", "other/#", QoS.AT_LEAST_ONCE);

        Map<String, QoS> matched = manager.resolveSubscribers("sensors/room1/temp");
        assertEquals(2, matched.size());
        assertEquals(QoS.AT_MOST_ONCE, matched.get("c1"));
        assertEquals(QoS.EXACTLY_ONCE, matched.get("c2"));
        assertFalse(matched.containsKey("c3"));
    }
}
