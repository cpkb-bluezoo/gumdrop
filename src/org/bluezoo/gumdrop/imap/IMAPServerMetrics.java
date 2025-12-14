/*
 * IMAPServerMetrics.java
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for IMAP servers.
 * 
 * <p>This class provides standardized IMAP server metrics for monitoring
 * mail access operations.
 * 
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code imap.server.connections} - Total number of IMAP connections</li>
 *   <li>{@code imap.server.active_connections} - Number of active connections</li>
 *   <li>{@code imap.server.commands} - Commands executed by type</li>
 *   <li>{@code imap.server.command.duration} - Command execution duration</li>
 *   <li>{@code imap.server.session.duration} - Session duration in milliseconds</li>
 *   <li>{@code imap.server.authentications} - Authentication attempts</li>
 *   <li>{@code imap.server.messages.fetched} - Messages fetched</li>
 *   <li>{@code imap.server.idle_connections} - Connections in IDLE state</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.imap";
    private static final String METER_VERSION = "0.4";

    // Connection metrics
    private final LongCounter connectionCounter;
    private final LongUpDownCounter activeConnections;
    private final LongUpDownCounter idleConnections;

    // Command metrics
    private final LongCounter commandCounter;
    private final DoubleHistogram commandDuration;

    // Session metrics
    private final DoubleHistogram sessionDuration;

    // Authentication metrics
    private final LongCounter authAttempts;
    private final LongCounter authSuccesses;
    private final LongCounter authFailures;

    // Message access metrics
    private final LongCounter messagesFetched;
    private final LongCounter messagesAppended;
    private final LongCounter messagesDeleted;
    private final LongCounter messagesCopied;

    // TLS metrics
    private final LongCounter starttlsUpgrades;

    /**
     * Creates IMAP server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public IMAPServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, METER_VERSION);

        // Connection counters
        this.connectionCounter = meter.counterBuilder("imap.server.connections")
                .setDescription("Total number of IMAP connections")
                .setUnit("connections")
                .build();

        this.activeConnections = meter.upDownCounterBuilder("imap.server.active_connections")
                .setDescription("Number of active IMAP connections")
                .setUnit("connections")
                .build();

        this.idleConnections = meter.upDownCounterBuilder("imap.server.idle_connections")
                .setDescription("Number of connections in IDLE state")
                .setUnit("connections")
                .build();

        // Command metrics
        this.commandCounter = meter.counterBuilder("imap.server.commands")
                .setDescription("Total IMAP commands executed")
                .setUnit("commands")
                .build();

        this.commandDuration = meter.histogramBuilder("imap.server.command.duration")
                .setDescription("Duration of IMAP command execution")
                .setUnit("ms")
                .setExplicitBuckets(1, 5, 10, 25, 50, 100, 250, 500, 1000, 5000)
                .build();

        // Session duration histogram
        this.sessionDuration = meter.histogramBuilder("imap.server.session.duration")
                .setDescription("Duration of IMAP sessions")
                .setUnit("ms")
                .setExplicitBuckets(1000, 10000, 60000, 300000, 600000, 1800000, 3600000)
                .build();

        // Authentication counters
        this.authAttempts = meter.counterBuilder("imap.server.authentications")
                .setDescription("Total authentication attempts")
                .setUnit("attempts")
                .build();

        this.authSuccesses = meter.counterBuilder("imap.server.authentications.success")
                .setDescription("Successful authentication attempts")
                .setUnit("attempts")
                .build();

        this.authFailures = meter.counterBuilder("imap.server.authentications.failure")
                .setDescription("Failed authentication attempts")
                .setUnit("attempts")
                .build();

        // Message access counters
        this.messagesFetched = meter.counterBuilder("imap.server.messages.fetched")
                .setDescription("Total messages fetched by clients")
                .setUnit("messages")
                .build();

        this.messagesAppended = meter.counterBuilder("imap.server.messages.appended")
                .setDescription("Total messages appended to mailboxes")
                .setUnit("messages")
                .build();

        this.messagesDeleted = meter.counterBuilder("imap.server.messages.deleted")
                .setDescription("Total messages deleted (expunged)")
                .setUnit("messages")
                .build();

        this.messagesCopied = meter.counterBuilder("imap.server.messages.copied")
                .setDescription("Total messages copied between mailboxes")
                .setUnit("messages")
                .build();

        // TLS upgrade counter
        this.starttlsUpgrades = meter.counterBuilder("imap.server.starttls")
                .setDescription("STARTTLS upgrades completed")
                .setUnit("upgrades")
                .build();
    }

    /**
     * Records a new IMAP connection.
     */
    public void connectionOpened() {
        connectionCounter.add(1);
        activeConnections.add(1);
    }

    /**
     * Records an IMAP connection closing.
     *
     * @param durationMs the session duration in milliseconds
     */
    public void connectionClosed(double durationMs) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    /**
     * Records an IMAP command execution.
     *
     * @param command the command name (e.g., "SELECT", "FETCH", "SEARCH")
     * @param durationMs the command execution duration
     * @param success true if the command succeeded
     */
    public void commandExecuted(String command, double durationMs, boolean success) {
        Attributes attrs = Attributes.of(
                "imap.command", command,
                "imap.success", success
        );
        commandCounter.add(1, attrs);
        commandDuration.record(durationMs, Attributes.of("imap.command", command));
    }

    /**
     * Records a connection entering IDLE state.
     */
    public void idleStarted() {
        idleConnections.add(1);
    }

    /**
     * Records a connection leaving IDLE state.
     */
    public void idleEnded() {
        idleConnections.add(-1);
    }

    /**
     * Records an authentication attempt.
     *
     * @param mechanism the authentication mechanism used
     */
    public void authAttempt(String mechanism) {
        authAttempts.add(1, Attributes.of("imap.auth.mechanism", mechanism));
    }

    /**
     * Records a successful authentication.
     *
     * @param mechanism the authentication mechanism used
     */
    public void authSuccess(String mechanism) {
        authSuccesses.add(1, Attributes.of("imap.auth.mechanism", mechanism));
    }

    /**
     * Records a failed authentication.
     *
     * @param mechanism the authentication mechanism used
     */
    public void authFailure(String mechanism) {
        authFailures.add(1, Attributes.of("imap.auth.mechanism", mechanism));
    }

    /**
     * Records messages fetched by a client.
     *
     * @param count the number of messages fetched
     */
    public void messagesFetched(int count) {
        messagesFetched.add(count);
    }

    /**
     * Records messages appended to a mailbox.
     *
     * @param count the number of messages appended
     */
    public void messagesAppended(int count) {
        messagesAppended.add(count);
    }

    /**
     * Records messages deleted (expunged).
     *
     * @param count the number of messages deleted
     */
    public void messagesDeleted(int count) {
        messagesDeleted.add(count);
    }

    /**
     * Records messages copied between mailboxes.
     *
     * @param count the number of messages copied
     */
    public void messagesCopied(int count) {
        messagesCopied.add(count);
    }

    /**
     * Records a STARTTLS upgrade.
     */
    public void starttlsUpgraded() {
        starttlsUpgrades.add(1);
    }

}

