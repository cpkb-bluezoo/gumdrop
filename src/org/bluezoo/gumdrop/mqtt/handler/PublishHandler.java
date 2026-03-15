/*
 * PublishHandler.java
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
 * Application-level handler for MQTT PUBLISH authorization.
 *
 * <p>Follows the async continuation-passing pattern: the handler
 * receives a {@link PublishState} and calls back into it when the
 * authorization decision is ready. This avoids blocking the
 * SelectorLoop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see PublishState
 */
public interface PublishHandler {

    /**
     * Called to authorize a publish. The handler should evaluate the
     * message and call the appropriate method on the state interface.
     *
     * @param state operations for allowing or rejecting
     * @param clientId the publishing client's identifier
     * @param topicName the destination topic
     * @param qos the QoS level (0, 1, or 2)
     * @param retain whether the message should be retained
     */
    void authorizePublish(PublishState state, String clientId,
                          String topicName, int qos, boolean retain);
}
