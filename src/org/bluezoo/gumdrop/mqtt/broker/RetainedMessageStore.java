/*
 * RetainedMessageStore.java
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;

/**
 * In-memory store for MQTT retained messages.
 *
 * <p>Each topic holds at most one retained message. Publishing a retained
 * message with an empty payload removes the retained message for that topic.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RetainedMessageStore {

    /**
     * An immutable retained message.
     */
    public static class RetainedMessage {
        private final String topic;
        private final MQTTMessageContent content;
        private final QoS qos;

        public RetainedMessage(String topic, MQTTMessageContent content,
                               QoS qos) {
            this.topic = topic;
            this.content = content;
            this.qos = qos;
        }

        public String getTopic() {
            return topic;
        }

        public MQTTMessageContent getContent() {
            return content;
        }

        public QoS getQoS() {
            return qos;
        }
    }

    private final ConcurrentHashMap<String, RetainedMessage> store =
            new ConcurrentHashMap<>();

    /**
     * Trie node indexing retained messages by literal topic level, so
     * {@link #match} can walk directly to the matching subset instead of
     * scanning every retained topic (issue #143). This is the mirror image
     * of {@link TopicTree}: there, wildcard-capable subscriber filters are
     * indexed and matched against a literal publish topic; here, literal
     * retained topics are indexed and matched against a (possibly
     * wildcard-capable) subscribe topic filter.
     */
    private static class Node {
        final ConcurrentHashMap<String, Node> children = new ConcurrentHashMap<>();
        volatile RetainedMessage retained;
    }

    private final Node root = new Node();

    /**
     * Sets or removes a retained message for the given topic.
     *
     * <p>If the content is null or has zero size, the retained message
     * for the topic is removed and any previously stored content is
     * released. If a previous retained message exists for the topic,
     * its content is released before the new one is stored.
     *
     * @param topic the topic name
     * @param content the message content (null or empty to remove)
     * @param qos the message QoS
     */
    public void set(String topic, MQTTMessageContent content, QoS qos) {
        if (content == null || content.size() == 0) {
            RetainedMessage old = store.remove(topic);
            if (old != null) {
                old.getContent().release();
            }
            removeFromTrie(topic);
        } else {
            RetainedMessage message = new RetainedMessage(topic, content, qos);
            RetainedMessage old = store.put(topic, message);
            if (old != null) {
                old.getContent().release();
            }
            addToTrie(topic, message);
        }
    }

    /**
     * Returns the retained message for the given topic, or null.
     */
    public RetainedMessage get(String topic) {
        return store.get(topic);
    }

    /**
     * Returns all retained messages matching the given topic filter.
     *
     * @param topicFilter a topic filter (may contain + and # wildcards)
     * @return matching retained messages
     */
    public List<RetainedMessage> match(String topicFilter) {
        List<RetainedMessage> result = new ArrayList<>();
        String[] filterLevels = topicFilter.split("/", -1);
        matchRecursive(root, filterLevels, 0, result);
        return result;
    }

    private void matchRecursive(Node node, String[] filterLevels, int depth,
            List<RetainedMessage> result) {
        if (depth == filterLevels.length) {
            if (node.retained != null) {
                result.add(node.retained);
            }
            return;
        }
        String fl = filterLevels[depth];
        if ("#".equals(fl)) {
            // Matches this node and all descendants (zero or more levels).
            // $-topics don't match a root-level # (depth 0 only).
            collectAll(node, depth == 0, result);
        } else if ("+".equals(fl)) {
            // $-topics don't match a root-level + (depth 0 only).
            for (Map.Entry<String, Node> entry : node.children.entrySet()) {
                if (depth == 0 && entry.getKey().startsWith("$")) {
                    continue;
                }
                matchRecursive(entry.getValue(), filterLevels, depth + 1, result);
            }
        } else {
            Node child = node.children.get(fl);
            if (child != null) {
                matchRecursive(child, filterLevels, depth + 1, result);
            }
        }
    }

    private void collectAll(Node node, boolean skipDollarChildren,
            List<RetainedMessage> result) {
        if (node.retained != null) {
            result.add(node.retained);
        }
        for (Map.Entry<String, Node> entry : node.children.entrySet()) {
            if (skipDollarChildren && entry.getKey().startsWith("$")) {
                continue;
            }
            collectAll(entry.getValue(), false, result);
        }
    }

    private void addToTrie(String topic, RetainedMessage message) {
        String[] levels = topic.split("/", -1);
        Node current = root;
        for (String level : levels) {
            Node child = current.children.get(level);
            if (child == null) {
                child = new Node();
                Node existing = current.children.putIfAbsent(level, child);
                if (existing != null) {
                    child = existing;
                }
            }
            current = child;
        }
        current.retained = message;
    }

    /**
     * Clears the retained message at {@code topic}'s trie node and prunes
     * any now-empty nodes (no retained message, no children) back up the
     * path, so the trie doesn't grow without bound under topic churn - same
     * approach as {@link TopicTree#unsubscribe}.
     */
    private void removeFromTrie(String topic) {
        String[] levels = topic.split("/", -1);
        List<Node> path = new ArrayList<>(levels.length + 1);
        path.add(root);
        Node current = root;
        for (String level : levels) {
            Node child = current.children.get(level);
            if (child == null) {
                return;
            }
            path.add(child);
            current = child;
        }
        current.retained = null;
        for (int i = levels.length; i > 0; i--) {
            Node node = path.get(i);
            if (node.retained != null || !node.children.isEmpty()) {
                break;
            }
            Node parent = path.get(i - 1);
            parent.children.remove(levels[i - 1], node);
        }
    }

    /**
     * Removes all retained messages.
     */
    public void clear() {
        store.clear();
        root.children.clear();
        root.retained = null;
    }

    /**
     * Returns the number of retained messages.
     */
    public int size() {
        return store.size();
    }
}
