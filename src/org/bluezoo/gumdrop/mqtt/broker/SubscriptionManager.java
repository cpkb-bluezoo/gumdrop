/*
 * SubscriptionManager.java
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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.mqtt.MQTTSession;
import org.bluezoo.gumdrop.mqtt.codec.QoS;

/**
 * Manages the mapping between MQTT client sessions and their subscriptions.
 *
 * <p>Coordinates the {@link TopicTree} (for topic matching) with
 * per-client session tracking. Provides the primary API for
 * subscribe, unsubscribe, publish-routing, and session cleanup.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SubscriptionManager {

    private final TopicTree topicTree = new TopicTree();
    private final RetainedMessageStore retainedStore = new RetainedMessageStore();

    private final ConcurrentHashMap<String, MQTTSession> sessions =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Set<String>> clientSubscriptions =
            new ConcurrentHashMap<>();

    public TopicTree getTopicTree() {
        return topicTree;
    }

    public RetainedMessageStore getRetainedStore() {
        return retainedStore;
    }

    /**
     * Registers a session. If a session with the same client ID already
     * exists, it is replaced (the old session should be disconnected first).
     */
    public void registerSession(MQTTSession session) {
        sessions.put(session.getClientId(), session);
    }

    /**
     * Removes a session registration.
     */
    public MQTTSession removeSession(String clientId) {
        return sessions.remove(clientId);
    }

    /**
     * Returns the session for the given client ID, or null.
     */
    public MQTTSession getSession(String clientId) {
        return sessions.get(clientId);
    }

    /**
     * Adds a subscription for a client.
     */
    public void subscribe(String clientId, String topicFilter, QoS qos) {
        topicTree.subscribe(topicFilter, clientId, qos);
        clientSubscriptions.computeIfAbsent(clientId,
                k -> ConcurrentHashMap.newKeySet()).add(topicFilter);
    }

    /**
     * Removes a subscription for a client.
     */
    public void unsubscribe(String clientId, String topicFilter) {
        topicTree.unsubscribe(topicFilter, clientId);
        Set<String> subs = clientSubscriptions.get(clientId);
        if (subs != null) {
            subs.remove(topicFilter);
        }
    }

    /**
     * Removes all subscriptions and session state for a client.
     */
    public void removeClient(String clientId) {
        topicTree.unsubscribeAll(clientId);
        clientSubscriptions.remove(clientId);
        sessions.remove(clientId);
    }

    /**
     * Returns the set of topic filters a client is subscribed to.
     */
    public Set<String> getSubscriptions(String clientId) {
        Set<String> subs = clientSubscriptions.get(clientId);
        return subs != null ? Collections.unmodifiableSet(subs) : Collections.emptySet();
    }

    /**
     * Resolves which sessions should receive a published message.
     *
     * @param topicName the publish topic
     * @return map of client ID to effective QoS for each matching subscriber
     */
    public Map<String, QoS> resolveSubscribers(String topicName) {
        return topicTree.matchWithMaxQoS(topicName);
    }

    /**
     * Returns the number of active sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }
}
