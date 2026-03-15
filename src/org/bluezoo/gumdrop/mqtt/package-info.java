/*
 * package-info.java
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

/**
 * MQTT protocol implementation (client and server).
 *
 * <p>Supports MQTT 3.1.1 and MQTT 5.0 over raw TCP and WebSocket.
 *
 * <h2>Server (broker)</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.MQTTListener} — TCP listener (ports 1883/8883)</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.MQTTService} — service base class</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.DefaultMQTTService} — default broker</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.MQTTProtocolHandler} — protocol state machine</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.MQTTWebSocketHandler} — WebSocket bridge</li>
 * </ul>
 *
 * <h2>Client</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.client.MQTTClient} — high-level client API</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.client.MQTTMessageListener} — receives messages
 *       as {@link org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent} for streaming
 *       large payloads without buffering</li>
 * </ul>
 *
 * <h2>Store</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.store.MQTTMessageStore} — payload storage factory</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent} — readable payload handle</li>
 * </ul>
 *
 * <h2>Codec</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.codec.MQTTFrameParser} — packet parser</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.codec.MQTTPacketEncoder} — packet encoder</li>
 * </ul>
 *
 * <h2>Broker</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.broker.TopicTree} — topic matching</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt.broker.SubscriptionManager} — subscription management</li>
 * </ul>
 *
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html">MQTT 3.1.1</a>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html">MQTT 5.0</a>
 */
package org.bluezoo.gumdrop.mqtt;
