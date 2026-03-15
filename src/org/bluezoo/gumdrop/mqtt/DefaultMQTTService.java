/*
 * DefaultMQTTService.java
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

package org.bluezoo.gumdrop.mqtt;

import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.mqtt.handler.ConnectHandler;

/**
 * Default MQTT service implementation.
 *
 * <p>Provides a standard MQTT broker with no custom connection handling.
 * All connection, publish, and subscribe decisions use default policies
 * (accept all connections, allow all publishes and subscriptions).
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service id="mqtt" class="org.bluezoo.gumdrop.mqtt.DefaultMQTTService">
 *   <listener class="org.bluezoo.gumdrop.mqtt.MQTTListener" port="1883"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultMQTTService extends MQTTService {

    @Override
    protected ConnectHandler createConnectHandler(TCPListener listener) {
        return null; // Default: accept all connections
    }
}
