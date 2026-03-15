/*
 * MQTTMessageListener.java
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

package org.bluezoo.gumdrop.mqtt.client;

import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;

/**
 * Callback interface for receiving MQTT messages.
 *
 * <p>The listener <b>owns</b> the content and <b>must</b> call
 * {@link MQTTMessageContent#release()} when done, typically in a
 * {@code finally} block.
 *
 * <p>For small messages, materialize the payload and release:
 * <pre>{@code
 * public void messageReceived(String topic, MQTTMessageContent content,
 *                             int qos, boolean retain) {
 *     byte[] data = content.asByteArray();
 *     try {
 *         process(data);
 *     } finally {
 *         content.release();
 *     }
 * }
 * }</pre>
 *
 * <p>For large messages, stream via {@link MQTTMessageContent#openChannel()}
 * to avoid loading the entire payload into memory:
 * <pre>{@code
 * public void messageReceived(String topic, MQTTMessageContent content,
 *                             int qos, boolean retain) {
 *     try (ReadableByteChannel ch = content.openChannel()) {
 *         streamToFile(ch, Path.of("received.dat"));
 *     } catch (IOException e) {
 *         // handle
 *     } finally {
 *         content.release();
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MQTTMessageListener {

    /**
     * Called when a message is received on a subscribed topic.
     *
     * <p>Ownership of the content transfers to the listener. The listener
     * must call {@link MQTTMessageContent#release()} when done.
     *
     * @param topic the topic name
     * @param content the message payload; call {@link MQTTMessageContent#release()}
     *        when done
     * @param qos the QoS level of the delivered message
     * @param retain whether this is a retained message
     */
    void messageReceived(String topic, MQTTMessageContent content, int qos, boolean retain);
}
