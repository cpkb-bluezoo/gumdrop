/*
 * SubscribeHandler.java
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

import org.bluezoo.gumdrop.mqtt.codec.QoS;

/**
 * Application-level handler for MQTT SUBSCRIBE authorization.
 *
 * <p>Follows the async continuation-passing pattern: the handler
 * receives a {@link SubscribeState} and calls back into it for each
 * topic filter when the authorization decision is ready.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SubscribeState
 */
public interface SubscribeHandler {

    /**
     * Called to authorize a single subscription within a SUBSCRIBE packet.
     * The handler should evaluate the topic filter and call the
     * appropriate method on the state interface.
     *
     * @param state operations for granting or rejecting
     * @param clientId the subscribing client's identifier
     * @param topicFilter the requested topic filter
     * @param requestedQoS the requested QoS level
     */
    void authorizeSubscription(SubscribeState state, String clientId,
                               String topicFilter, QoS requestedQoS);
}
