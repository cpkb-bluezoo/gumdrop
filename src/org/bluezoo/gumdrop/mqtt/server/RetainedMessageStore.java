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

package org.bluezoo.gumdrop.mqtt.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageContent;

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
        private final MqttMessageContent content;
        private final QoS qos;

        public RetainedMessage(String topic, MqttMessageContent content,
                               QoS qos) {
            this.topic = topic;
            this.content = content;
            this.qos = qos;
        }

        public String getTopic() {
            return topic;
        }

        public MqttMessageContent getContent() {
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
    public void set(String topic, MqttMessageContent content, QoS qos) {
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
        matchRecursive(root, topicFilter, 0, result);
        return result;
    }

    /**
     * Walks the filter one level at a time by index. {@code pos} is the
     * start of the next level, or -1 once every level has been consumed;
     * wildcard levels are recognised in place without extracting them.
     */
    private void matchRecursive(Node node, String filter, int pos,
            List<RetainedMessage> result) {
        if (pos < 0) {
            if (node.retained != null) {
                result.add(node.retained);
            }
            return;
        }
        int end = filter.indexOf('/', pos);
        int next = end < 0 ? -1 : end + 1;
        int levelEnd = end < 0 ? filter.length() : end;
        boolean single = levelEnd - pos == 1;
        if (single && filter.charAt(pos) == '#') {
            // Matches this node and all descendants (zero or more levels).
            // $-topics don't match a root-level # (pos 0 only).
            collectAll(node, pos == 0, result);
        } else if (single && filter.charAt(pos) == '+') {
            // $-topics don't match a root-level + (pos 0 only).
            for (Map.Entry<String, Node> entry : node.children.entrySet()) {
                if (pos == 0 && entry.getKey().startsWith("$")) {
                    continue;
                }
                matchRecursive(entry.getValue(), filter, next, result);
            }
        } else {
            Node child = node.children.get(filter.substring(pos, levelEnd));
            if (child != null) {
                matchRecursive(child, filter, next, result);
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
        Node current = root;
        int pos = 0;
        while (true) {
            int end = topic.indexOf('/', pos);
            String level = end < 0 ? topic.substring(pos) : topic.substring(pos, end);
            Node child = current.children.get(level);
            if (child == null) {
                child = new Node();
                Node existing = current.children.putIfAbsent(level, child);
                if (existing != null) {
                    child = existing;
                }
            }
            current = child;
            if (end < 0) {
                break;
            }
            pos = end + 1;
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
