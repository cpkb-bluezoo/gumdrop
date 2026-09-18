/*
 * MqttListener.java
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

import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import java.net.InetAddress;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * TCP transport listener for MQTT connections.
 *
 * <p>Supports MQTT on port 1883 (plaintext) and port 8883 (TLS), following
 * the same pattern as {@code SmtpListener} and {@code ImapListener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html">MQTT 3.1.1</a>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html">MQTT 5.0</a>
 */
public class MqttListener extends TcpListener {

    private static final Logger LOGGER =
            Logger.getLogger(MqttListener.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mqtt.L10N");

    public static final int MQTT_DEFAULT_PORT = 1883;
    public static final int MQTTS_DEFAULT_PORT = 8883;

    private int port = -1;
    private int maxPacketSize = 1_048_576; // 1 MB default
    private int defaultKeepAlive = 60;
    private Realm realm;

    private org.bluezoo.gumdrop.mqtt.server.MqttServer server;
    private MqttServerMetrics metrics;

    @Override
    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? MQTTS_DEFAULT_PORT : MQTT_DEFAULT_PORT;
        }
        if (isMetricsEnabled()) {
            metrics = new MqttServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this listener, or null if telemetry is
     * not enabled.
     *
     * @return the MQTT server metrics
     */
    public MqttServerMetrics getMetrics() {
        return metrics;
    }

    @Override
    public String getDescription() {
        return secure ? "mqtts" : "mqtt";
    }

    @Override
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
    /**
     * Sets the port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public MqttListener port(int port) {
        setPort(port);
        return this;
    }

    @Override
    public MqttListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public MqttListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public MqttListener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public MqttListener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }


    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public void setMaxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    public int getDefaultKeepAlive() {
        return defaultKeepAlive;
    }

    public void setDefaultKeepAlive(int defaultKeepAlive) {
        this.defaultKeepAlive = defaultKeepAlive;
    }

    public Realm getRealm() {
        return realm;
    }

    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    public org.bluezoo.gumdrop.mqtt.server.MqttServer getServer() {
        return server;
    }

    public void setServer(org.bluezoo.gumdrop.mqtt.server.MqttServer server) {
        this.server = server;
    }

    @Override
    protected ProtocolHandler createHandler() {
        if (server != null) {
            try {
                return server.createProtocolHandler(this);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("log.handler_create_failed"), e);
            }
        }
        // Should not happen if server is correctly configured
        throw new IllegalStateException("MqttListener requires an MqttServer");
    }
}
