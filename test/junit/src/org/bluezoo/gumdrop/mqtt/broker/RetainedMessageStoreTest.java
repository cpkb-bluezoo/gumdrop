/*
 * RetainedMessageStoreTest.java
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

import org.bluezoo.gumdrop.mqtt.broker.RetainedMessageStore.RetainedMessage;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link RetainedMessageStore}, in particular the
 * trie-based {@link RetainedMessageStore#match} added for issue #143.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RetainedMessageStoreTest {

    private RetainedMessageStore store;

    @Before
    public void setUp() {
        store = new RetainedMessageStore();
    }

    private static MQTTMessageContent content(String data) {
        return new InMemoryMessageStore.InMemoryContent(data.getBytes());
    }

    private static Set<String> topics(List<RetainedMessage> messages) {
        Set<String> result = new HashSet<>();
        for (RetainedMessage m : messages) {
            result.add(m.getTopic());
        }
        return result;
    }

    @Test
    public void testExactMatch() {
        store.set("sensors/temp", content("21.5"), QoS.AT_LEAST_ONCE);
        List<RetainedMessage> result = store.match("sensors/temp");
        assertEquals(1, result.size());
        assertEquals("sensors/temp", result.get(0).getTopic());
    }

    @Test
    public void testExactMatchNoMatch() {
        store.set("sensors/temp", content("21.5"), QoS.AT_LEAST_ONCE);
        assertTrue(store.match("sensors/humidity").isEmpty());
    }

    @Test
    public void testSingleLevelWildcard() {
        store.set("sensors/room1/temp", content("a"), QoS.AT_MOST_ONCE);
        store.set("sensors/room2/temp", content("b"), QoS.AT_MOST_ONCE);
        store.set("sensors/room1/humidity", content("c"), QoS.AT_MOST_ONCE);

        Set<String> matched = topics(store.match("sensors/+/temp"));
        assertEquals(2, matched.size());
        assertTrue(matched.contains("sensors/room1/temp"));
        assertTrue(matched.contains("sensors/room2/temp"));
        assertFalse(matched.contains("sensors/room1/humidity"));
    }

    @Test
    public void testMultiLevelWildcard() {
        store.set("sensors", content("a"), QoS.AT_MOST_ONCE);
        store.set("sensors/temp", content("b"), QoS.AT_MOST_ONCE);
        store.set("sensors/room/temp", content("c"), QoS.AT_MOST_ONCE);
        store.set("other/topic", content("d"), QoS.AT_MOST_ONCE);

        Set<String> matched = topics(store.match("sensors/#"));
        assertEquals(3, matched.size());
        assertTrue(matched.contains("sensors"));
        assertTrue(matched.contains("sensors/temp"));
        assertTrue(matched.contains("sensors/room/temp"));
        assertFalse(matched.contains("other/topic"));
    }

    @Test
    public void testHashAlone() {
        store.set("any", content("a"), QoS.AT_MOST_ONCE);
        store.set("any/topic/here", content("b"), QoS.AT_MOST_ONCE);

        Set<String> matched = topics(store.match("#"));
        assertTrue(matched.contains("any"));
        assertTrue(matched.contains("any/topic/here"));
    }

    @Test
    public void testDollarTopicExcludesRootWildcards() {
        store.set("$SYS/info", content("a"), QoS.AT_MOST_ONCE);
        store.set("normal/info", content("b"), QoS.AT_MOST_ONCE);

        assertFalse("# should not match $SYS topics",
                topics(store.match("#")).contains("$SYS/info"));
        assertFalse("+/info should not match $SYS/info",
                topics(store.match("+/info")).contains("$SYS/info"));
        assertTrue("Explicit $SYS filter should match",
                topics(store.match("$SYS/info")).contains("$SYS/info"));
        assertTrue("# should still match non-$ topics",
                topics(store.match("#")).contains("normal/info"));
    }

    @Test
    public void testEmptyContentRemovesRetainedMessage() {
        store.set("a/b", content("x"), QoS.AT_MOST_ONCE);
        assertNotNull(store.get("a/b"));

        store.set("a/b", null, QoS.AT_MOST_ONCE);
        assertNull(store.get("a/b"));
        assertTrue(store.match("a/b").isEmpty());
        assertTrue(store.match("a/#").isEmpty());
    }

    @Test
    public void testOverwriteReplacesRetainedMessage() {
        store.set("a/b", content("old"), QoS.AT_MOST_ONCE);
        store.set("a/b", content("new"), QoS.AT_LEAST_ONCE);

        List<RetainedMessage> result = store.match("a/b");
        assertEquals(1, result.size());
        assertEquals(QoS.AT_LEAST_ONCE, result.get(0).getQoS());
    }

    @Test
    public void testRemoveThenReAddSameTopic() {
        // Exercises trie node pruning followed by re-creation of the same
        // path (issue #143/#144-style pruning).
        store.set("a/b/c", content("1"), QoS.AT_MOST_ONCE);
        store.set("a/b/c", null, QoS.AT_MOST_ONCE);
        assertTrue(store.match("a/#").isEmpty());

        store.set("a/b/c", content("2"), QoS.AT_MOST_ONCE);
        List<RetainedMessage> result = store.match("a/b/c");
        assertEquals(1, result.size());
    }

    @Test
    public void testSizeAndClear() {
        store.set("a", content("1"), QoS.AT_MOST_ONCE);
        store.set("b", content("2"), QoS.AT_MOST_ONCE);
        assertEquals(2, store.size());

        store.clear();
        assertEquals(0, store.size());
        assertTrue(store.match("#").isEmpty());
    }

    @Test
    public void testEmptyLevel() {
        store.set("a//b", content("x"), QoS.AT_MOST_ONCE);
        assertFalse(store.match("a//b").isEmpty());
        assertTrue(store.match("a/b").isEmpty());
    }
}
