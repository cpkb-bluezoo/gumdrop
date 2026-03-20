/*
 * SOCKSServerMetrics.java
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

package org.bluezoo.gumdrop.socks;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for SOCKS proxy servers.
 *
 * <p>Provides standardized SOCKS server metrics for monitoring proxy
 * operations, following the same pattern as {@code MQTTServerMetrics}
 * and {@code SMTPServerMetrics}.
 *
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code socks.server.connections} — total connections</li>
 *   <li>{@code socks.server.active_connections} — active connections</li>
 *   <li>{@code socks.server.session.duration} — session duration in ms</li>
 *   <li>{@code socks.server.connect_requests} — CONNECT requests by
 *       SOCKS version</li>
 *   <li>{@code socks.server.bind_requests} — BIND requests by
 *       SOCKS version</li>
 *   <li>{@code socks.server.relays} — total relays established</li>
 *   <li>{@code socks.server.active_relays} — active relays</li>
 *   <li>{@code socks.server.relay.duration} — relay duration in ms</li>
 *   <li>{@code socks.server.relay.bytes} — bytes relayed by
 *       direction</li>
 *   <li>{@code socks.server.authentications} — auth attempts</li>
 *   <li>{@code socks.server.authentications.success} — successful
 *       auths</li>
 *   <li>{@code socks.server.authentications.failure} — failed
 *       auths</li>
 *   <li>{@code socks.server.udp_associations} — total UDP ASSOCIATE
 *       sessions</li>
 *   <li>{@code socks.server.active_udp_associations} — active UDP
 *       associations</li>
 *   <li>{@code socks.server.udp_association.duration} — UDP
 *       association duration in ms</li>
 *   <li>{@code socks.server.destinations_blocked} — blocked
 *       destination requests</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SOCKSServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.socks";
    private static final String UNIT_ATTEMPTS = "attempts";

    // Connection metrics
    private final LongCounter connectionCounter;
    private final LongUpDownCounter activeConnections;
    private final DoubleHistogram sessionDuration;

    // Request metrics
    private final LongCounter connectRequests;
    private final LongCounter bindRequests;

    // Relay metrics
    private final LongCounter relayCounter;
    private final LongUpDownCounter activeRelays;
    private final DoubleHistogram relayDuration;
    private final LongCounter relayBytes;

    // Authentication metrics
    private final LongCounter authAttempts;
    private final LongCounter authSuccesses;
    private final LongCounter authFailures;

    // UDP association metrics
    private final LongCounter udpAssociations;
    private final LongUpDownCounter activeUdpAssociations;
    private final DoubleHistogram udpAssociationDuration;

    // Policy metrics
    private final LongCounter destinationsBlocked;

    /**
     * Creates SOCKS server metrics using the given telemetry
     * configuration.
     *
     * @param config the telemetry configuration
     */
    public SOCKSServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);

        this.connectionCounter = meter.counterBuilder(
                        "socks.server.connections")
                .setDescription("Total number of SOCKS connections")
                .setUnit("connections")
                .build();

        this.activeConnections = meter.upDownCounterBuilder(
                        "socks.server.active_connections")
                .setDescription("Number of active SOCKS connections")
                .setUnit("connections")
                .build();

        this.sessionDuration = meter.histogramBuilder(
                        "socks.server.session.duration")
                .setDescription("Duration of SOCKS sessions")
                .setUnit("ms")
                .setExplicitBuckets(100, 500, 1000, 5000, 10000,
                        30000, 60000, 300000, 600000)
                .build();

        this.connectRequests = meter.counterBuilder(
                        "socks.server.connect_requests")
                .setDescription("Total SOCKS CONNECT requests")
                .setUnit("requests")
                .build();

        this.bindRequests = meter.counterBuilder(
                        "socks.server.bind_requests")
                .setDescription("Total SOCKS BIND requests")
                .setUnit("requests")
                .build();

        this.relayCounter = meter.counterBuilder(
                        "socks.server.relays")
                .setDescription("Total relay connections established")
                .setUnit("relays")
                .build();

        this.activeRelays = meter.upDownCounterBuilder(
                        "socks.server.active_relays")
                .setDescription("Number of active relay connections")
                .setUnit("relays")
                .build();

        this.relayDuration = meter.histogramBuilder(
                        "socks.server.relay.duration")
                .setDescription("Duration of relay connections")
                .setUnit("ms")
                .setExplicitBuckets(100, 500, 1000, 5000, 10000,
                        30000, 60000, 300000, 600000, 3600000)
                .build();

        this.relayBytes = meter.counterBuilder(
                        "socks.server.relay.bytes")
                .setDescription("Total bytes relayed")
                .setUnit("bytes")
                .build();

        this.authAttempts = meter.counterBuilder(
                        "socks.server.authentications")
                .setDescription("Total authentication attempts")
                .setUnit(UNIT_ATTEMPTS)
                .build();

        this.authSuccesses = meter.counterBuilder(
                        "socks.server.authentications.success")
                .setDescription("Successful authentication attempts")
                .setUnit(UNIT_ATTEMPTS)
                .build();

        this.authFailures = meter.counterBuilder(
                        "socks.server.authentications.failure")
                .setDescription("Failed authentication attempts")
                .setUnit(UNIT_ATTEMPTS)
                .build();

        this.udpAssociations = meter.counterBuilder(
                        "socks.server.udp_associations")
                .setDescription("Total UDP ASSOCIATE sessions")
                .setUnit("associations")
                .build();

        this.activeUdpAssociations = meter.upDownCounterBuilder(
                        "socks.server.active_udp_associations")
                .setDescription("Number of active UDP ASSOCIATE sessions")
                .setUnit("associations")
                .build();

        this.udpAssociationDuration = meter.histogramBuilder(
                        "socks.server.udp_association.duration")
                .setDescription("Duration of UDP ASSOCIATE sessions")
                .setUnit("ms")
                .setExplicitBuckets(100, 500, 1000, 5000, 10000,
                        30000, 60000, 300000, 600000, 3600000)
                .build();

        this.destinationsBlocked = meter.counterBuilder(
                        "socks.server.destinations_blocked")
                .setDescription("Destination connect requests blocked by policy")
                .setUnit("requests")
                .build();
    }

    // ── Connection metrics ──

    /**
     * Records a new SOCKS client connection.
     */
    public void connectionOpened() {
        connectionCounter.add(1);
        activeConnections.add(1);
    }

    /**
     * Records a SOCKS client connection closing.
     *
     * @param durationMs the connection duration in milliseconds
     */
    public void connectionClosed(double durationMs) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    // ── Request metrics ──

    /**
     * Records a SOCKS CONNECT request.
     *
     * @param socksVersion the SOCKS version string
     *        (e.g. "4", "4a", "5")
     */
    public void connectRequest(String socksVersion) {
        Attributes attrs = Attributes.of("socks.version", socksVersion);
        connectRequests.add(1, attrs);
    }

    /**
     * Records a SOCKS BIND request.
     *
     * @param socksVersion the SOCKS version string
     *        (e.g. "4", "4a", "5")
     */
    public void bindRequest(String socksVersion) {
        Attributes attrs = Attributes.of("socks.version", socksVersion);
        bindRequests.add(1, attrs);
    }

    // ── Relay metrics ──

    /**
     * Records a relay being established.
     */
    public void relayOpened() {
        relayCounter.add(1);
        activeRelays.add(1);
    }

    /**
     * Records a relay being closed.
     *
     * @param durationMs the relay duration in milliseconds
     */
    public void relayClosed(double durationMs) {
        activeRelays.add(-1);
        relayDuration.record(durationMs);
    }

    /**
     * Records bytes relayed in a given direction.
     *
     * @param bytes the number of bytes
     * @param direction "upstream" or "downstream"
     */
    public void bytesRelayed(long bytes, String direction) {
        Attributes attrs = Attributes.of("socks.direction", direction);
        relayBytes.add(bytes, attrs);
    }

    // ── UDP association metrics ──

    /**
     * Records a UDP ASSOCIATE session being established.
     */
    public void udpAssociationOpened() {
        udpAssociations.add(1);
        activeUdpAssociations.add(1);
    }

    /**
     * Records a UDP ASSOCIATE session being closed.
     *
     * @param durationMs the association duration in milliseconds
     */
    public void udpAssociationClosed(double durationMs) {
        activeUdpAssociations.add(-1);
        udpAssociationDuration.record(durationMs);
    }

    // ── Authentication metrics ──

    /**
     * Records an authentication attempt.
     *
     * @param method the auth method name
     *        (e.g. "username_password", "gssapi", "none")
     */
    public void authAttempt(String method) {
        Attributes attrs = Attributes.of("socks.auth_method", method);
        authAttempts.add(1, attrs);
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

    // ── Policy metrics ──

    /**
     * Records a destination blocked by policy.
     */
    public void destinationBlocked() {
        destinationsBlocked.add(1);
    }

}
