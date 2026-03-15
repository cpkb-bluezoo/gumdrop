/*
 * PublishState.java
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

package org.bluezoo.gumdrop.mqtt.handler;

/**
 * Operations for responding to a PUBLISH authorization check.
 *
 * <p>Provided to {@link PublishHandler#authorizePublish} so the
 * handler can accept or reject asynchronously.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see PublishHandler
 */
public interface PublishState {

    /**
     * Allows the publish. The message will be routed to subscribers
     * and QoS acknowledgment sent to the publisher.
     */
    void allowPublish();

    /**
     * Rejects the publish. For QoS 1, a PUBACK with a failure reason
     * code is sent (MQTT 5.0) or the message is silently dropped
     * (MQTT 3.1.1). For QoS 0, the message is silently dropped.
     */
    void rejectPublish();
}
