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

package org.bluezoo.gumdrop.mqtt.handler;

import org.bluezoo.gumdrop.mqtt.codec.MQTTEventHandler;

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
     * Accepts the connection. Sends CONNACK with return code 0 and
     * completes session setup.
     */
    void acceptConnection();

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
     *                   (see {@link MQTTEventHandler} CONNACK constants)
     */
    void reject(int returnCode);
}
