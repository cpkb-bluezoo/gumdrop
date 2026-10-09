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

import org.bluezoo.gumdrop.util.CidrNetwork;
import java.util.List;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import java.net.InetAddress;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

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

    private EventLogger events() {
        return eventTelemetry().getLogger(MqttListener.class, L10N);
    }
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

    /**
     * Sets the port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public MqttListener port(int port) {
        this.port = port;
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

    @Override
    public MqttListener maxConnections(int max) {
        super.maxConnections(max);
        return this;
    }

    @Override
    public MqttListener maxConnectionsPerIP(int max) {
        super.maxConnectionsPerIP(max);
        return this;
    }

    @Override
    public MqttListener rateLimit(String rateLimit) {
        super.rateLimit(rateLimit);
        return this;
    }

    @Override
    public MqttListener maxAuthFailures(int max) {
        super.maxAuthFailures(max);
        return this;
    }

    @Override
    public MqttListener authLockoutTimeMs(long lockoutMs) {
        super.authLockoutTimeMs(lockoutMs);
        return this;
    }

    @Override
    public MqttListener allowedNetworks(List<CidrNetwork> allowedNetworks) {
        super.allowedNetworks(allowedNetworks);
        return this;
    }

    @Override
    public MqttListener blockedNetworks(List<CidrNetwork> blockedNetworks) {
        super.blockedNetworks(blockedNetworks);
        return this;
    }

    @Override
    public MqttListener name(String name) {
        super.name(name);
        return this;
    }

    @Override
    public MqttListener maxNetInSize(int size) {
        super.maxNetInSize(size);
        return this;
    }

    @Override
    public MqttListener maxNetOutSize(int size) {
        super.maxNetOutSize(size);
        return this;
    }

    @Override
    public MqttListener idleTimeoutMs(long idleTimeoutMs) {
        super.idleTimeoutMs(idleTimeoutMs);
        return this;
    }

    @Override
    public MqttListener readTimeoutMs(long readTimeoutMs) {
        super.readTimeoutMs(readTimeoutMs);
        return this;
    }

    @Override
    public MqttListener connectionTimeoutMs(long connectionTimeoutMs) {
        super.connectionTimeoutMs(connectionTimeoutMs);
        return this;
    }

    @Override
    public MqttListener maxDtlsPeers(int max) {
        super.maxDtlsPeers(max);
        return this;
    }

    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public MqttListener maxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
        return this;
    }

    public int getDefaultKeepAlive() {
        return defaultKeepAlive;
    }

    public MqttListener defaultKeepAlive(int defaultKeepAlive) {
        this.defaultKeepAlive = defaultKeepAlive;
        return this;
    }

    public Realm getRealm() {
        return realm;
    }

    public MqttListener realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    public org.bluezoo.gumdrop.mqtt.server.MqttServer getServer() {
        return server;
    }

    public MqttListener server(org.bluezoo.gumdrop.mqtt.server.MqttServer server) {
        this.server = server;
        return this;
    }

    @Override
    protected ProtocolHandler createHandler() {
        if (server != null) {
            try {
                return server.createProtocolHandler(this);
            } catch (Exception e) {
                events().warn("log.handler_create_failed").thrown(e).emit();
            }
        }
        // Should not happen if server is correctly configured
        throw new IllegalStateException("MqttListener requires an MqttServer");
    }
}
