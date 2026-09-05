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
 * MQTT broker-side state: subscriptions, topic matching, retained
 * messages, QoS delivery tracking, and Will messages.
 *
 * <p>{@link org.bluezoo.gumdrop.mqtt.broker.TopicTree} matches published
 * topics against subscription filters, including the {@code +}/{@code
 * #} wildcards; {@link org.bluezoo.gumdrop.mqtt.broker.SubscriptionManager}
 * tracks which clients are subscribed to what; {@link
 * org.bluezoo.gumdrop.mqtt.broker.RetainedMessageStore} holds the one
 * retained message per topic a new subscriber should immediately
 * receive; {@link org.bluezoo.gumdrop.mqtt.broker.QoSManager} tracks
 * in-flight QoS 1/2 delivery state; {@link
 * org.bluezoo.gumdrop.mqtt.broker.WillManager} publishes a client's Will
 * message on ungraceful disconnect.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mqtt
 */
package org.bluezoo.gumdrop.mqtt.broker;
