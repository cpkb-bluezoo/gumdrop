/*
 * SMTPServerMetrics.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for SMTP servers.
 * 
 * <p>This class provides standardized SMTP server metrics for monitoring
 * mail transfer operations.
 * 
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code smtp.server.connections} - Total number of SMTP connections</li>
 *   <li>{@code smtp.server.active_connections} - Number of active connections</li>
 *   <li>{@code smtp.server.messages} - Total messages received</li>
 *   <li>{@code smtp.server.message.size} - Size of received messages in bytes</li>
 *   <li>{@code smtp.server.recipients} - Recipients per message</li>
 *   <li>{@code smtp.server.session.duration} - SMTP session duration in milliseconds</li>
 *   <li>{@code smtp.server.authentications} - Authentication attempts</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.smtp";

    // Connection metrics
    private final LongCounter connectionCounter;
    private final LongUpDownCounter activeConnections;

    // Message metrics
    private final LongCounter messageCounter;
    private final DoubleHistogram messageSize;
    private final DoubleHistogram recipientsPerMessage;

    // Session metrics
    private final DoubleHistogram sessionDuration;

    // Authentication metrics
    private final LongCounter authAttempts;
    private final LongCounter authSuccesses;
    private final LongCounter authFailures;

    // TLS metrics
    private final LongCounter starttlsUpgrades;

    /**
     * Creates SMTP server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public SMTPServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);

        // Connection counters
        this.connectionCounter = meter.counterBuilder("smtp.server.connections")
                .setDescription("Total number of SMTP connections")
                .setUnit("connections")
                .build();

        this.activeConnections = meter.upDownCounterBuilder("smtp.server.active_connections")
                .setDescription("Number of active SMTP connections")
                .setUnit("connections")
                .build();

        // Message counters
        this.messageCounter = meter.counterBuilder("smtp.server.messages")
                .setDescription("Total number of messages received")
                .setUnit("messages")
                .build();

        // Message size histogram (email sizes vary widely)
        this.messageSize = meter.histogramBuilder("smtp.server.message.size")
                .setDescription("Size of received messages")
                .setUnit("bytes")
                .setExplicitBuckets(1000, 10000, 100000, 500000, 1000000, 5000000, 10000000, 35000000)
                .build();

        // Recipients per message histogram
        this.recipientsPerMessage = meter.histogramBuilder("smtp.server.recipients")
                .setDescription("Number of recipients per message")
                .setUnit("recipients")
                .setExplicitBuckets(1, 2, 5, 10, 25, 50, 100, 500)
                .build();

        // Session duration histogram
        this.sessionDuration = meter.histogramBuilder("smtp.server.session.duration")
                .setDescription("Duration of SMTP sessions")
                .setUnit("ms")
                .setExplicitBuckets(100, 500, 1000, 5000, 10000, 30000, 60000, 300000)
                .build();

        // Authentication counters
        this.authAttempts = meter.counterBuilder("smtp.server.authentications")
                .setDescription("Total authentication attempts")
                .setUnit("attempts")
                .build();

        this.authSuccesses = meter.counterBuilder("smtp.server.authentications.success")
                .setDescription("Successful authentication attempts")
                .setUnit("attempts")
                .build();

        this.authFailures = meter.counterBuilder("smtp.server.authentications.failure")
                .setDescription("Failed authentication attempts")
                .setUnit("attempts")
                .build();

        // TLS upgrade counter
        this.starttlsUpgrades = meter.counterBuilder("smtp.server.starttls")
                .setDescription("STARTTLS upgrades completed")
                .setUnit("upgrades")
                .build();
    }

    /**
     * Records a new SMTP connection.
     */
    public void connectionOpened() {
        connectionCounter.add(1);
        activeConnections.add(1);
    }

    /**
     * Records an SMTP connection closing.
     *
     * @param durationMs the connection duration in milliseconds
     */
    public void connectionClosed(double durationMs) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    /**
     * Records a message received.
     *
     * @param sizeBytes the message size in bytes
     * @param recipientCount the number of recipients
     */
    public void messageReceived(long sizeBytes, int recipientCount) {
        messageCounter.add(1);
        messageSize.record(sizeBytes);
        recipientsPerMessage.record(recipientCount);
    }

    /**
     * Records a message received with status attribute.
     *
     * @param sizeBytes the message size in bytes
     * @param recipientCount the number of recipients
     * @param accepted true if the message was accepted, false if rejected
     */
    public void messageReceived(long sizeBytes, int recipientCount, boolean accepted) {
        Attributes attrs = Attributes.of("smtp.accepted", accepted);
        messageCounter.add(1, attrs);
        if (accepted && sizeBytes > 0) {
            messageSize.record(sizeBytes);
            recipientsPerMessage.record(recipientCount);
        }
    }

    /**
     * Records an authentication attempt.
     *
     * @param mechanism the authentication mechanism used (PLAIN, LOGIN, etc.)
     */
    public void authAttempt(String mechanism) {
        authAttempts.add(1, Attributes.of("smtp.auth.mechanism", mechanism));
    }

    /**
     * Records a successful authentication.
     *
     * @param mechanism the authentication mechanism used
     */
    public void authSuccess(String mechanism) {
        authSuccesses.add(1, Attributes.of("smtp.auth.mechanism", mechanism));
    }

    /**
     * Records a failed authentication.
     *
     * @param mechanism the authentication mechanism used
     */
    public void authFailure(String mechanism) {
        authFailures.add(1, Attributes.of("smtp.auth.mechanism", mechanism));
    }

    /**
     * Records a STARTTLS upgrade.
     */
    public void starttlsUpgraded() {
        starttlsUpgrades.add(1);
    }

}

