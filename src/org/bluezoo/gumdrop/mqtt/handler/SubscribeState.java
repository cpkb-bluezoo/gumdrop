/*
 * SubscribeState.java
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
 * Operations for responding to a subscription authorization check.
 *
 * <p>Provided to {@link SubscribeHandler#authorizeSubscription} so
 * the handler can grant or reject asynchronously.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SubscribeHandler
 */
public interface SubscribeState {

    /**
     * Grants the subscription at the specified QoS level.
     * The granted QoS may be lower than what the client requested.
     *
     * @param grantedQoS the QoS level to grant
     */
    void grantSubscription(QoS grantedQoS);

    /**
     * Rejects the subscription. A failure return code (0x80) will
     * be included in the SUBACK for this topic filter.
     */
    void rejectSubscription();
}
