/*
 * ConnectState.java
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

import org.bluezoo.gumdrop.mqtt.codec.MqttEventHandler;

/**
 * Operations for responding to an MQTT CONNECT.
 *
 * <p>Provided to {@link ConnectHandler#handleConnect} so the handler
 * can accept or reject the connection asynchronously without blocking
 * the SelectorLoop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectHandler
 */
public interface ConnectState {

    /**
     * Returns the TLS session details of this connection: the negotiated
     * protocol and cipher suite, the ALPN protocol, and the client's
     * certificate chain when the listener asks for one (mutual TLS), which a
     * handler can use to identify the client without a password.
     *
     * <p>The handshake is complete before CONNECT is read, so the details are
     * always final here.
     *
     * @return the TLS session details, or {@code null} if the connection is
     *         not encrypted. Over WebSocket they are those of the HTTP
     *         connection the WebSocket was upgraded on.
     */
    default org.bluezoo.gumdrop.SecurityInfo getSecurityInfo() {
        return null;
    }

    /**
     * Accepts the connection. Sends CONNACK with return code 0 and
     * completes session setup.
     *
     * @param handler PUBLISH/SUBSCRIBE authorization for the remaining
     *                lifetime of the session, or {@code null} to allow
     *                all publishes and subscriptions
     */
    void acceptConnection(MqttSessionHandler handler);

    /**
     * Rejects the connection with a bad username/password return code
     * (CONNACK 0x04).
     */
    void rejectBadCredentials();

    /**
     * Rejects the connection as not authorized (CONNACK 0x05).
     */
    void rejectNotAuthorized();

    /**
     * Rejects the connection with a specific CONNACK return code.
     *
     * @param returnCode the CONNACK return code
     *                   (see {@link MqttEventHandler} CONNACK constants)
     */
    void reject(int returnCode);
}
