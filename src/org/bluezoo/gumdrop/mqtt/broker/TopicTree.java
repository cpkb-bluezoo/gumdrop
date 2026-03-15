/*
 * TopicTree.java
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.mqtt.codec.QoS;

/**
 * Trie-based topic tree for MQTT topic filter matching.
 *
 * <p>Supports MQTT wildcard semantics:
 * <ul>
 *   <li>{@code +} — single-level wildcard, matches exactly one topic level</li>
 *   <li>{@code #} — multi-level wildcard, matches zero or more levels
 *       (must be the last level in the filter)</li>
 * </ul>
 *
 * <p>Topics starting with {@code $} (e.g. {@code $SYS/}) do not match
 * wildcard filters at the root level per the MQTT specification.
 *
 * <p>This class is thread-safe. All structural modifications use
 * {@link ConcurrentHashMap} for lock-free reads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TopicTree {

    /**
     * A subscription entry associating a subscriber identity with a QoS level.
     */
    public static class SubscriptionEntry {
        private final String clientId;
        private final QoS qos;

        public SubscriptionEntry(String clientId, QoS qos) {
            this.clientId = clientId;
            this.qos = qos;
        }

        public String getClientId() {
            return clientId;
        }

        public QoS getQoS() {
            return qos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubscriptionEntry)) return false;
            SubscriptionEntry that = (SubscriptionEntry) o;
            return clientId.equals(that.clientId);
        }

        @Override
        public int hashCode() {
            return clientId.hashCode();
        }
    }

    private static class Node {
        final ConcurrentHashMap<String, Node> children = new ConcurrentHashMap<>();
        final Set<SubscriptionEntry> subscribers =
                ConcurrentHashMap.newKeySet();
    }

    private final Node root = new Node();

    /**
     * Adds a subscription for the given topic filter.
     *
     * @param topicFilter the topic filter (may contain + and # wildcards)
     * @param clientId the subscriber's client identifier
     * @param qos the requested QoS level
     */
    public void subscribe(String topicFilter, String clientId, QoS qos) {
        String[] levels = topicFilter.split("/", -1);
        Node current = root;
        for (String level : levels) {
            current = current.children.computeIfAbsent(level, k -> new Node());
        }
        // Remove any existing subscription for this client (to update QoS)
        current.subscribers.remove(new SubscriptionEntry(clientId, qos));
        current.subscribers.add(new SubscriptionEntry(clientId, qos));
    }

    /**
     * Removes a subscription for the given topic filter.
     *
     * @param topicFilter the topic filter
     * @param clientId the subscriber's client identifier
     */
    public void unsubscribe(String topicFilter, String clientId) {
        String[] levels = topicFilter.split("/", -1);
        Node current = root;
        for (String level : levels) {
            Node child = current.children.get(level);
            if (child == null) return;
            current = child;
        }
        current.subscribers.removeIf(e -> e.getClientId().equals(clientId));
    }

    /**
     * Removes all subscriptions for the given client.
     *
     * @param clientId the client identifier
     */
    public void unsubscribeAll(String clientId) {
        unsubscribeAllRecursive(root, clientId);
    }

    private void unsubscribeAllRecursive(Node node, String clientId) {
        node.subscribers.removeIf(e -> e.getClientId().equals(clientId));
        for (Node child : node.children.values()) {
            unsubscribeAllRecursive(child, clientId);
        }
    }

    /**
     * Returns all subscriptions matching the given publish topic name.
     *
     * <p>The topic name must not contain wildcards. Topics starting
     * with {@code $} do not match root-level wildcard filters.
     *
     * @param topicName the publish topic name
     * @return matching subscription entries (may contain duplicates from
     *         overlapping filters; the caller should deduplicate by clientId
     *         and use the highest QoS)
     */
    public List<SubscriptionEntry> match(String topicName) {
        String[] levels = topicName.split("/", -1);
        boolean dollarTopic = topicName.startsWith("$");
        List<SubscriptionEntry> result = new ArrayList<>();
        matchRecursive(root, levels, 0, dollarTopic, result);
        return result;
    }

    private void matchRecursive(Node node, String[] levels, int depth,
                                boolean dollarTopic,
                                List<SubscriptionEntry> result) {
        if (depth == levels.length) {
            result.addAll(node.subscribers);
            // # at the end matches zero trailing levels
            Node hashNode = node.children.get("#");
            if (hashNode != null) {
                result.addAll(hashNode.subscribers);
            }
            return;
        }

        String level = levels[depth];

        // Exact match
        Node exactChild = node.children.get(level);
        if (exactChild != null) {
            matchRecursive(exactChild, levels, depth + 1, false, result);
        }

        // $-prefixed topics don't match root-level wildcards
        if (dollarTopic && depth == 0) {
            return;
        }

        // Single-level wildcard (+)
        Node plusChild = node.children.get("+");
        if (plusChild != null) {
            matchRecursive(plusChild, levels, depth + 1, false, result);
        }

        // Multi-level wildcard (#)
        Node hashChild = node.children.get("#");
        if (hashChild != null) {
            result.addAll(hashChild.subscribers);
        }
    }

    /**
     * Returns the set of client IDs with active subscriptions matching
     * the given topic, deduplicated with the maximum QoS per client.
     *
     * @param topicName the publish topic name
     * @return map of client ID to effective QoS
     */
    public Map<String, QoS> matchWithMaxQoS(String topicName) {
        List<SubscriptionEntry> entries = match(topicName);
        Map<String, QoS> result = new ConcurrentHashMap<>();
        for (SubscriptionEntry entry : entries) {
            result.merge(entry.getClientId(), entry.getQoS(),
                    (existing, newQos) ->
                            existing.getValue() >= newQos.getValue() ? existing : newQos);
        }
        return result;
    }
}
