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
 * Pluggable storage for MQTT message payloads, so a large PUBLISH
 * payload need not be held in memory end to end.
 *
 * <p>{@link org.bluezoo.gumdrop.mqtt.store.MQTTMessageStore} is the
 * factory a broker or client is configured with; {@link
 * org.bluezoo.gumdrop.mqtt.store.MQTTMessageWriter} is where an incoming
 * payload is written as it arrives, and {@link
 * org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent} is the readable
 * handle delivered to a subscriber. {@link
 * org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore} is the default,
 * simplest implementation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mqtt
 */
package org.bluezoo.gumdrop.mqtt.store;
