/*
 * MqttServerSessionProvider.java
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

package org.bluezoo.gumdrop.mqtt.server;

import org.bluezoo.gumdrop.ServerSessionProvider;

/**
 * MQTT server composition SPI — mints a {@link ConnectHandler} per accepted
 * connection.
 *
 * <p>The returned {@link ConnectHandler} decides whether to accept the
 * CONNECT and, on acceptance, supplies the {@link
 * org.bluezoo.gumdrop.mqtt.server.MqttSessionHandler} that authorizes
 * PUBLISH and SUBSCRIBE for the rest of that connection (see {@link
 * org.bluezoo.gumdrop.mqtt.server.ConnectState#acceptConnection}).
 *
 * <p>{@link MqttServer} implements this interface directly; with no provider
 * configured, {@link MqttServer#openSession} returns {@code null} and every
 * connection is accepted with no publish/subscribe restrictions (open
 * broker — see the security warning on {@link MqttServer#compose()}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerSessionProvider
 * @see web/configuration.html
 */
public interface MqttServerSessionProvider
        extends ServerSessionProvider<ConnectHandler> {

    /**
     * Initialises shared resources before listeners accept connections.
     *
     * <p>Called from {@link MqttServer#start}. Default: no-op.
     */
    default void start() {
    }

    /**
     * Releases resources after all listeners have stopped.
     *
     * <p>Called from {@link MqttServer#stop}. Default: no-op.
     */
    default void stop() {
    }

}
