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
 * MQTT client (3.1.1 and 5.0), over TCP or a UNIX domain socket.
 *
 * <p>{@link org.bluezoo.gumdrop.mqtt.client.MQTTClient} is the facade
 * applications connect and publish/subscribe through; {@link
 * org.bluezoo.gumdrop.mqtt.client.MQTTClientProtocolHandler} drives the
 * wire protocol ({@link org.bluezoo.gumdrop.mqtt.codec}); {@link
 * org.bluezoo.gumdrop.mqtt.client.MQTTClientCallback} reports connection
 * lifecycle events, and {@link
 * org.bluezoo.gumdrop.mqtt.client.MQTTMessageListener} delivers incoming
 * messages as {@link org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent},
 * so a large payload streams to the application rather than being
 * buffered whole.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mqtt
 * @see org.bluezoo.gumdrop.mqtt.codec
 */
package org.bluezoo.gumdrop.mqtt.client;
