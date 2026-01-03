/*
 * POP3ServerMetrics.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.pop3;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for POP3 servers.
 * 
 * <p>This class provides standardized POP3 server metrics for monitoring
 * mail retrieval operations.
 * 
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code pop3.server.connections} - Total number of POP3 connections</li>
 *   <li>{@code pop3.server.active_connections} - Number of active connections</li>
 *   <li>{@code pop3.server.session.duration} - Session duration in milliseconds</li>
 *   <li>{@code pop3.server.authentications} - Authentication attempts</li>
 *   <li>{@code pop3.server.messages.retrieved} - Messages retrieved by clients</li>
 *   <li>{@code pop3.server.messages.deleted} - Messages marked for deletion</li>
 *   <li>{@code pop3.server.bytes.transferred} - Bytes transferred to clients</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.pop3";

    // Connection metrics
    private final LongCounter connectionCounter;
    private final LongUpDownCounter activeConnections;

    // Session metrics
    private final DoubleHistogram sessionDuration;

    // Authentication metrics
    private final LongCounter authAttempts;
    private final LongCounter authSuccesses;
    private final LongCounter authFailures;

    // Message access metrics
    private final LongCounter messagesRetrieved;
    private final LongCounter messagesDeleted;
    private final LongCounter bytesTransferred;

    // TLS metrics
    private final LongCounter starttlsUpgrades;

    // Command metrics
    private final LongCounter commandCounter;

    /**
     * Creates POP3 server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public POP3ServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);

        // Connection counters
        this.connectionCounter = meter.counterBuilder("pop3.server.connections")
                .setDescription("Total number of POP3 connections")
                .setUnit("connections")
                .build();

        this.activeConnections = meter.upDownCounterBuilder("pop3.server.active_connections")
                .setDescription("Number of active POP3 connections")
                .setUnit("connections")
                .build();

        // Session duration histogram
        this.sessionDuration = meter.histogramBuilder("pop3.server.session.duration")
                .setDescription("Duration of POP3 sessions")
                .setUnit("ms")
                .setExplicitBuckets(1000, 5000, 10000, 30000, 60000, 300000, 600000)
                .build();

        // Authentication counters
        this.authAttempts = meter.counterBuilder("pop3.server.authentications")
                .setDescription("Total authentication attempts")
                .setUnit("attempts")
                .build();

        this.authSuccesses = meter.counterBuilder("pop3.server.authentications.success")
                .setDescription("Successful authentication attempts")
                .setUnit("attempts")
                .build();

        this.authFailures = meter.counterBuilder("pop3.server.authentications.failure")
                .setDescription("Failed authentication attempts")
                .setUnit("attempts")
                .build();

        // Message access counters
        this.messagesRetrieved = meter.counterBuilder("pop3.server.messages.retrieved")
                .setDescription("Total messages retrieved by clients")
                .setUnit("messages")
                .build();

        this.messagesDeleted = meter.counterBuilder("pop3.server.messages.deleted")
                .setDescription("Total messages deleted after retrieval")
                .setUnit("messages")
                .build();

        this.bytesTransferred = meter.counterBuilder("pop3.server.bytes.transferred")
                .setDescription("Total bytes transferred to clients")
                .setUnit("bytes")
                .build();

        // TLS upgrade counter
        this.starttlsUpgrades = meter.counterBuilder("pop3.server.starttls")
                .setDescription("STLS upgrades completed")
                .setUnit("upgrades")
                .build();

        // Command counter
        this.commandCounter = meter.counterBuilder("pop3.server.commands")
                .setDescription("Total POP3 commands executed")
                .setUnit("commands")
                .build();
    }

    /**
     * Records a new POP3 connection.
     */
    public void connectionOpened() {
        connectionCounter.add(1);
        activeConnections.add(1);
    }

    /**
     * Records a POP3 connection closing.
     *
     * @param durationMs the session duration in milliseconds
     */
    public void connectionClosed(double durationMs) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    /**
     * Records a POP3 command execution.
     *
     * @param command the command name (e.g., "RETR", "DELE", "LIST")
     */
    public void commandExecuted(String command) {
        commandCounter.add(1, Attributes.of("pop3.command", command));
    }

    /**
     * Records an authentication attempt.
     *
     * @param mechanism the authentication mechanism used (USER/PASS, APOP, AUTH)
     */
    public void authAttempt(String mechanism) {
        authAttempts.add(1, Attributes.of("pop3.auth.mechanism", mechanism));
    }

    /**
     * Records a successful authentication.
     *
     * @param mechanism the authentication mechanism used
     */
    public void authSuccess(String mechanism) {
        authSuccesses.add(1, Attributes.of("pop3.auth.mechanism", mechanism));
    }

    /**
     * Records a failed authentication.
     *
     * @param mechanism the authentication mechanism used
     */
    public void authFailure(String mechanism) {
        authFailures.add(1, Attributes.of("pop3.auth.mechanism", mechanism));
    }

    /**
     * Records a message retrieved by a client.
     *
     * @param sizeBytes the size of the message in bytes
     */
    public void messageRetrieved(long sizeBytes) {
        messagesRetrieved.add(1);
        bytesTransferred.add(sizeBytes);
    }

    /**
     * Records a message marked for deletion.
     */
    public void messageDeleted() {
        messagesDeleted.add(1);
    }

    /**
     * Records a STLS (STARTTLS) upgrade.
     */
    public void starttlsUpgraded() {
        starttlsUpgrades.add(1);
    }

}

