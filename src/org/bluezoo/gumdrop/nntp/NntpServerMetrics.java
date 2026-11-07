/*
 * NntpServerMetrics.java
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

package org.bluezoo.gumdrop.nntp;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * Metrics of an NNTP server listener, under the meter
 * {@code org.bluezoo.gumdrop.nntp}: connections, session duration, commands,
 * authentication by mechanism, articles read and posted, bytes sent and
 * STARTTLS upgrades. A listener has one when metrics are enabled in the
 * telemetry configuration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.nntp";

    private final LongCounter connections;
    private final LongUpDownCounter activeConnections;
    private final DoubleHistogram sessionDuration;
    private final LongCounter commands;
    private final LongCounter authAttempts;
    private final LongCounter authSuccesses;
    private final LongCounter authFailures;
    private final LongCounter articlesRetrieved;
    private final LongCounter bytesSent;
    private final LongCounter articlesPosted;
    private final LongCounter articlesRejected;
    private final LongCounter starttls;

    /**
     * Creates the metrics.
     *
     * @param config the telemetry configuration
     */
    public NntpServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);
        connections = meter.counterBuilder("nntp.server.connections")
                .setDescription("Total number of NNTP connections").setUnit("connections").build();
        activeConnections = meter.upDownCounterBuilder("nntp.server.active_connections")
                .setDescription("Number of active NNTP connections").setUnit("connections").build();
        sessionDuration = meter.histogramBuilder("nntp.server.session.duration")
                .setDescription("Duration of NNTP sessions").setUnit("ms")
                .setExplicitBuckets(1000, 5000, 10000, 30000, 60000, 300000, 600000).build();
        commands = meter.counterBuilder("nntp.server.commands")
                .setDescription("Total NNTP commands executed").setUnit("commands").build();
        authAttempts = meter.counterBuilder("nntp.server.authentications")
                .setDescription("Total authentication attempts").setUnit("attempts").build();
        authSuccesses = meter.counterBuilder("nntp.server.authentications.success")
                .setDescription("Successful authentication attempts").setUnit("attempts").build();
        authFailures = meter.counterBuilder("nntp.server.authentications.failure")
                .setDescription("Failed authentication attempts").setUnit("attempts").build();
        articlesRetrieved = meter.counterBuilder("nntp.server.articles.retrieved")
                .setDescription("Articles, heads and bodies sent to clients").setUnit("articles").build();
        bytesSent = meter.counterBuilder("nntp.server.bytes.sent")
                .setDescription("Article bytes sent to clients, as sent on the wire").setUnit("bytes").build();
        articlesPosted = meter.counterBuilder("nntp.server.articles.posted")
                .setDescription("Articles accepted from clients").setUnit("articles").build();
        articlesRejected = meter.counterBuilder("nntp.server.articles.rejected")
                .setDescription("Articles refused or not accepted").setUnit("articles").build();
        starttls = meter.counterBuilder("nntp.server.starttls")
                .setDescription("STARTTLS upgrades completed").setUnit("upgrades").build();
    }

    /** A connection was opened. */
    public void connectionOpened() {
        connections.add(1);
        activeConnections.add(1);
    }

    /**
     * A connection was closed.
     *
     * @param durationMs how long it lasted
     */
    public void connectionClosed(double durationMs) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    /**
     * A command was executed.
     *
     * @param command the command name
     */
    public void commandExecuted(String command) {
        commands.add(1, Attributes.of("nntp.command", command));
    }

    /**
     * An authentication was attempted.
     *
     * @param mechanism USER/PASS or the SASL mechanism
     */
    public void authAttempt(String mechanism) {
        authAttempts.add(1, Attributes.of("nntp.auth.mechanism", mechanism));
    }

    /**
     * An authentication succeeded.
     *
     * @param mechanism USER/PASS or the SASL mechanism
     */
    public void authSuccess(String mechanism) {
        authSuccesses.add(1, Attributes.of("nntp.auth.mechanism", mechanism));
    }

    /**
     * An authentication failed.
     *
     * @param mechanism USER/PASS or the SASL mechanism
     */
    public void authFailure(String mechanism) {
        authFailures.add(1, Attributes.of("nntp.auth.mechanism", mechanism));
    }

    /**
     * An article, head or body was sent in full.
     *
     * @param bytes the bytes written, dot-stuffing and terminator included
     */
    public void articleRetrieved(long bytes) {
        articlesRetrieved.add(1);
        bytesSent.add(bytes);
    }

    /** An article was accepted from a client. */
    public void articlePosted() {
        articlesPosted.add(1);
    }

    /** An article from a client was refused or not accepted. */
    public void articleRejected() {
        articlesRejected.add(1);
    }

    /** A connection was upgraded with STARTTLS. */
    public void starttlsUpgraded() {
        starttls.add(1);
    }

}
