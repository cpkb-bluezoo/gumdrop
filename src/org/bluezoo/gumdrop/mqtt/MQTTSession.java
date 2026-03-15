/*
 * MQTTSession.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.mqtt.broker.QoSManager;
import org.bluezoo.gumdrop.mqtt.codec.MQTTVersion;

/**
 * Per-client MQTT session state.
 *
 * <p>Holds the connection metadata, QoS state machine, and endpoint
 * reference for an active MQTT client. Session state may persist across
 * reconnections when clean session is false.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MQTTSession {

    private final String clientId;
    private final MQTTVersion version;
    private final boolean cleanSession;
    private final QoSManager qosManager;
    private volatile Endpoint endpoint;
    private volatile String username;
    private volatile int keepAlive;

    public MQTTSession(String clientId, MQTTVersion version,
                       boolean cleanSession) {
        this.clientId = clientId;
        this.version = version;
        this.cleanSession = cleanSession;
        this.qosManager = new QoSManager();
    }

    public String getClientId() {
        return clientId;
    }

    public MQTTVersion getVersion() {
        return version;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public QoSManager getQoSManager() {
        return qosManager;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(int keepAlive) {
        this.keepAlive = keepAlive;
    }

    /**
     * Returns whether this session is currently connected.
     */
    public boolean isConnected() {
        Endpoint ep = endpoint;
        return ep != null && ep.isOpen();
    }

    @Override
    public String toString() {
        return "MQTTSession(clientId=" + clientId + ", version=" + version +
                ", cleanSession=" + cleanSession + ")";
    }
}
