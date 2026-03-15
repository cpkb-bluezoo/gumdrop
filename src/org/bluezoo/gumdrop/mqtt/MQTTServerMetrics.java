/*
 * MQTTServerMetrics.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for MQTT servers.
 *
 * <p>Provides standardized MQTT server metrics for monitoring
 * broker operations, following the same pattern as
 * {@code SMTPServerMetrics} and {@code HTTPServerMetrics}.
 *
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code mqtt.server.connections} — total MQTT connections</li>
 *   <li>{@code mqtt.server.active_connections} — active connections</li>
 *   <li>{@code mqtt.server.session.duration} — session duration in ms</li>
 *   <li>{@code mqtt.server.publishes} — messages published</li>
 *   <li>{@code mqtt.server.publish.size} — publish payload size in bytes</li>
 *   <li>{@code mqtt.server.subscribes} — subscribe operations</li>
 *   <li>{@code mqtt.server.unsubscribes} — unsubscribe operations</li>
 *   <li>{@code mqtt.server.authentications} — authentication attempts</li>
 *   <li>{@code mqtt.server.authentications.success} — successful auths</li>
 *   <li>{@code mqtt.server.authentications.failure} — failed auths</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MQTTServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.mqtt";
    private static final String UNIT_ATTEMPTS = "attempts";

    private final LongCounter connectionCounter;
    private final LongUpDownCounter activeConnections;
    private final DoubleHistogram sessionDuration;

    private final LongCounter publishCounter;
    private final DoubleHistogram publishSize;

    private final LongCounter subscribeCounter;
    private final LongCounter unsubscribeCounter;

    private final LongCounter authAttempts;
    private final LongCounter authSuccesses;
    private final LongCounter authFailures;

    /**
     * Creates MQTT server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public MQTTServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);

        this.connectionCounter = meter.counterBuilder("mqtt.server.connections")
                .setDescription("Total number of MQTT connections")
                .setUnit("connections")
                .build();

        this.activeConnections = meter.upDownCounterBuilder(
                        "mqtt.server.active_connections")
                .setDescription("Number of active MQTT connections")
                .setUnit("connections")
                .build();

        this.sessionDuration = meter.histogramBuilder("mqtt.server.session.duration")
                .setDescription("Duration of MQTT sessions")
                .setUnit("ms")
                .setExplicitBuckets(100, 500, 1000, 5000, 10000,
                        30000, 60000, 300000, 600000)
                .build();

        this.publishCounter = meter.counterBuilder("mqtt.server.publishes")
                .setDescription("Total PUBLISH messages received")
                .setUnit("messages")
                .build();

        this.publishSize = meter.histogramBuilder("mqtt.server.publish.size")
                .setDescription("Size of PUBLISH payloads")
                .setUnit("bytes")
                .setExplicitBuckets(64, 256, 1024, 4096, 16384,
                        65536, 262144, 1048576, 16777216)
                .build();

        this.subscribeCounter = meter.counterBuilder("mqtt.server.subscribes")
                .setDescription("Total SUBSCRIBE operations")
                .setUnit("operations")
                .build();

        this.unsubscribeCounter = meter.counterBuilder("mqtt.server.unsubscribes")
                .setDescription("Total UNSUBSCRIBE operations")
                .setUnit("operations")
                .build();

        this.authAttempts = meter.counterBuilder("mqtt.server.authentications")
                .setDescription("Total authentication attempts")
                .setUnit(UNIT_ATTEMPTS)
                .build();

        this.authSuccesses = meter.counterBuilder(
                        "mqtt.server.authentications.success")
                .setDescription("Successful authentication attempts")
                .setUnit(UNIT_ATTEMPTS)
                .build();

        this.authFailures = meter.counterBuilder(
                        "mqtt.server.authentications.failure")
                .setDescription("Failed authentication attempts")
                .setUnit(UNIT_ATTEMPTS)
                .build();
    }

    /**
     * Records a new MQTT connection.
     */
    public void connectionOpened() {
        connectionCounter.add(1);
        activeConnections.add(1);
    }

    /**
     * Records an MQTT connection closing.
     *
     * @param durationMs the connection duration in milliseconds
     */
    public void connectionClosed(double durationMs) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    /**
     * Records a PUBLISH message received.
     *
     * @param payloadSize the payload size in bytes
     * @param qos the QoS level (0, 1, or 2)
     */
    public void publishReceived(long payloadSize, int qos) {
        Attributes attrs = Attributes.of("mqtt.qos", qos);
        publishCounter.add(1, attrs);
        if (payloadSize > 0) {
            publishSize.record(payloadSize, attrs);
        }
    }

    /**
     * Records a SUBSCRIBE operation.
     */
    public void subscribeReceived() {
        subscribeCounter.add(1);
    }

    /**
     * Records an UNSUBSCRIBE operation.
     */
    public void unsubscribeReceived() {
        unsubscribeCounter.add(1);
    }

    /**
     * Records an authentication attempt.
     */
    public void authAttempt() {
        authAttempts.add(1);
    }

    /**
     * Records a successful authentication.
     */
    public void authSuccess() {
        authSuccesses.add(1);
    }

    /**
     * Records a failed authentication.
     */
    public void authFailure() {
        authFailures.add(1);
    }
}
