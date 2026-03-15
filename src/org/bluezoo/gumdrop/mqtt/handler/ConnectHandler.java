/*
 * ConnectHandler.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;

/**
 * Application-level handler for MQTT CONNECT processing.
 *
 * <p>Follows the same async continuation-passing pattern as
 * {@code ClientConnected} in the SMTP stack: the handler receives
 * a {@link ConnectState} and calls back into it when the authorization
 * decision is ready. This allows async operations (LDAP lookup,
 * database check, external auth service) without blocking the
 * SelectorLoop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectState
 */
public interface ConnectHandler {

    /**
     * Called when a client sends a CONNECT packet.
     *
     * <p>The handler should evaluate the connection (client ID,
     * credentials, TLS state, source address) and call the
     * appropriate method on the state interface when ready.
     * This call may be asynchronous.
     *
     * @param state operations for accepting or rejecting
     * @param packet the CONNECT packet
     * @param endpoint the transport endpoint
     */
    void handleConnect(ConnectState state, ConnectPacket packet,
                       Endpoint endpoint);

    /**
     * Called when the connection is closed for any reason.
     */
    void disconnected();
}
