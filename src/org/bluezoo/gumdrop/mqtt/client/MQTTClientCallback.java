/*
 * MQTTClientCallback.java
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

package org.bluezoo.gumdrop.mqtt.client;

/**
 * Callback interface for MQTT client lifecycle events.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MQTTClientCallback {

    /**
     * Called when the CONNACK is received and the connection is established.
     *
     * @param sessionPresent whether a previous session exists on the server
     * @param returnCode the CONNACK return code (0 = accepted)
     */
    void connected(boolean sessionPresent, int returnCode);

    /**
     * Called when the connection is lost.
     *
     * @param cause the exception, or null for clean disconnect
     */
    void connectionLost(Exception cause);

    /**
     * Called when a SUBACK is received.
     *
     * @param packetId the subscribe packet identifier
     * @param grantedQoS the granted QoS levels
     */
    void subscribeAcknowledged(int packetId, int[] grantedQoS);

    /**
     * Called when a publish is acknowledged (QoS 1 PUBACK or QoS 2 PUBCOMP).
     *
     * @param packetId the publish packet identifier
     */
    void publishComplete(int packetId);
}
