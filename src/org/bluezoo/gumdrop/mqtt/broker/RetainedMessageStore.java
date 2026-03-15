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
        } else {
            RetainedMessage old = store.put(topic,
                    new RetainedMessage(topic, content, qos));
            if (old != null) {
                old.getContent().release();
            }
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
        for (Map.Entry<String, RetainedMessage> entry : store.entrySet()) {
            if (topicMatches(topicFilter, entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Removes all retained messages.
     */
    public void clear() {
        store.clear();
    }

    /**
     * Returns the number of retained messages.
     */
    public int size() {
        return store.size();
    }

    static boolean topicMatches(String filter, String topic) {
        String[] filterLevels = filter.split("/", -1);
        String[] topicLevels = topic.split("/", -1);

        // $-topics don't match root wildcards
        if (topic.startsWith("$") &&
                (filterLevels[0].equals("+") || filterLevels[0].equals("#"))) {
            return false;
        }

        for (int i = 0; i < filterLevels.length; i++) {
            String fl = filterLevels[i];
            if ("#".equals(fl)) {
                return true;
            }
            if (i >= topicLevels.length) {
                return false;
            }
            if (!"+".equals(fl) && !fl.equals(topicLevels[i])) {
                return false;
            }
        }
        return filterLevels.length == topicLevels.length;
    }
}
