/*
 * WillManager.java
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

import java.util.concurrent.ConcurrentHashMap;

import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;

/**
 * Manages MQTT Last Will and Testament messages.
 *
 * <p>When a client connects with a will message, it is stored here.
 * On unclean disconnect, the will message is published through the broker.
 * On clean disconnect (DISCONNECT packet received), the will is cleared.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WillManager {

    /**
     * A stored will message.
     */
    public static class WillMessage {
        private final String topic;
        private final MQTTMessageContent content;
        private final QoS qos;
        private final boolean retain;

        public WillMessage(String topic, MQTTMessageContent content,
                           QoS qos, boolean retain) {
            this.topic = topic;
            this.content = content;
            this.qos = qos;
            this.retain = retain;
        }

        public String getTopic() { return topic; }
        public MQTTMessageContent getContent() { return content; }
        public QoS getQoS() { return qos; }
        public boolean isRetain() { return retain; }
    }

    private final ConcurrentHashMap<String, WillMessage> wills =
            new ConcurrentHashMap<>();

    /**
     * Stores a will message for the given client.
     */
    public void set(String clientId, String topic, MQTTMessageContent content,
                    QoS qos, boolean retain) {
        WillMessage old = wills.put(clientId,
                new WillMessage(topic, content, qos, retain));
        if (old != null) {
            old.getContent().release();
        }
    }

    /**
     * Removes and returns the will message for the given client.
     *
     * @return the will message, or null if none was set
     */
    public WillMessage remove(String clientId) {
        return wills.remove(clientId);
    }

    /**
     * Clears the will for a client (called on clean disconnect).
     */
    public void clear(String clientId) {
        WillMessage old = wills.remove(clientId);
        if (old != null) {
            old.getContent().release();
        }
    }

    /**
     * Returns whether a will is set for the given client.
     */
    public boolean has(String clientId) {
        return wills.containsKey(clientId);
    }
}
