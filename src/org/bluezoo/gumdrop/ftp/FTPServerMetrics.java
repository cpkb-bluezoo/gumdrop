/*
 * FTPServerMetrics.java
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

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for FTP servers.
 * 
 * <p>This class provides standardized FTP server metrics for monitoring
 * file transfer operations.
 * 
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code ftp.server.connections} - Total number of FTP control connections</li>
 *   <li>{@code ftp.server.active_connections} - Number of active control connections</li>
 *   <li>{@code ftp.server.session.duration} - Session duration in milliseconds</li>
 *   <li>{@code ftp.server.authentications} - Authentication attempts</li>
 *   <li>{@code ftp.server.transfers} - File transfers (uploads/downloads)</li>
 *   <li>{@code ftp.server.bytes.uploaded} - Bytes uploaded by clients</li>
 *   <li>{@code ftp.server.bytes.downloaded} - Bytes downloaded by clients</li>
 *   <li>{@code ftp.server.transfer.duration} - File transfer duration</li>
 *   <li>{@code ftp.server.commands} - FTP commands executed</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.ftp";
    private static final String METER_VERSION = "0.4";

    // Connection metrics
    private final LongCounter connectionCounter;
    private final LongUpDownCounter activeConnections;
    private final LongUpDownCounter activeDataConnections;

    // Session metrics
    private final DoubleHistogram sessionDuration;

    // Authentication metrics
    private final LongCounter authAttempts;
    private final LongCounter authSuccesses;
    private final LongCounter authFailures;

    // Transfer metrics
    private final LongCounter transfersTotal;
    private final LongCounter uploadsCounter;
    private final LongCounter downloadsCounter;
    private final LongCounter bytesUploaded;
    private final LongCounter bytesDownloaded;
    private final DoubleHistogram transferDuration;
    private final DoubleHistogram transferSize;

    // Command metrics
    private final LongCounter commandCounter;

    // TLS metrics
    private final LongCounter authTlsUpgrades;

    // Directory operations
    private final LongCounter directoryListings;
    private final LongCounter directoryCreations;
    private final LongCounter fileDeletions;

    /**
     * Creates FTP server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public FTPServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, METER_VERSION);

        // Connection counters
        this.connectionCounter = meter.counterBuilder("ftp.server.connections")
                .setDescription("Total number of FTP control connections")
                .setUnit("connections")
                .build();

        this.activeConnections = meter.upDownCounterBuilder("ftp.server.active_connections")
                .setDescription("Number of active FTP control connections")
                .setUnit("connections")
                .build();

        this.activeDataConnections = meter.upDownCounterBuilder("ftp.server.active_data_connections")
                .setDescription("Number of active FTP data connections")
                .setUnit("connections")
                .build();

        // Session duration histogram
        this.sessionDuration = meter.histogramBuilder("ftp.server.session.duration")
                .setDescription("Duration of FTP sessions")
                .setUnit("ms")
                .setExplicitBuckets(1000, 10000, 60000, 300000, 600000, 1800000, 3600000)
                .build();

        // Authentication counters
        this.authAttempts = meter.counterBuilder("ftp.server.authentications")
                .setDescription("Total authentication attempts")
                .setUnit("attempts")
                .build();

        this.authSuccesses = meter.counterBuilder("ftp.server.authentications.success")
                .setDescription("Successful authentication attempts")
                .setUnit("attempts")
                .build();

        this.authFailures = meter.counterBuilder("ftp.server.authentications.failure")
                .setDescription("Failed authentication attempts")
                .setUnit("attempts")
                .build();

        // Transfer counters
        this.transfersTotal = meter.counterBuilder("ftp.server.transfers")
                .setDescription("Total file transfers")
                .setUnit("transfers")
                .build();

        this.uploadsCounter = meter.counterBuilder("ftp.server.uploads")
                .setDescription("Total file uploads (STOR)")
                .setUnit("files")
                .build();

        this.downloadsCounter = meter.counterBuilder("ftp.server.downloads")
                .setDescription("Total file downloads (RETR)")
                .setUnit("files")
                .build();

        this.bytesUploaded = meter.counterBuilder("ftp.server.bytes.uploaded")
                .setDescription("Total bytes uploaded by clients")
                .setUnit("bytes")
                .build();

        this.bytesDownloaded = meter.counterBuilder("ftp.server.bytes.downloaded")
                .setDescription("Total bytes downloaded by clients")
                .setUnit("bytes")
                .build();

        this.transferDuration = meter.histogramBuilder("ftp.server.transfer.duration")
                .setDescription("Duration of file transfers")
                .setUnit("ms")
                .setExplicitBuckets(100, 500, 1000, 5000, 10000, 30000, 60000, 300000)
                .build();

        this.transferSize = meter.histogramBuilder("ftp.server.transfer.size")
                .setDescription("Size of file transfers")
                .setUnit("bytes")
                .setExplicitBuckets(1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000L)
                .build();

        // Command counter
        this.commandCounter = meter.counterBuilder("ftp.server.commands")
                .setDescription("Total FTP commands executed")
                .setUnit("commands")
                .build();

        // TLS upgrade counter
        this.authTlsUpgrades = meter.counterBuilder("ftp.server.auth_tls")
                .setDescription("AUTH TLS upgrades completed")
                .setUnit("upgrades")
                .build();

        // Directory operation counters
        this.directoryListings = meter.counterBuilder("ftp.server.directory.listings")
                .setDescription("Total directory listings (LIST, NLST, MLSD)")
                .setUnit("operations")
                .build();

        this.directoryCreations = meter.counterBuilder("ftp.server.directory.created")
                .setDescription("Total directories created (MKD)")
                .setUnit("directories")
                .build();

        this.fileDeletions = meter.counterBuilder("ftp.server.files.deleted")
                .setDescription("Total files deleted (DELE)")
                .setUnit("files")
                .build();
    }

    /**
     * Records a new FTP control connection.
     */
    public void connectionOpened() {
        connectionCounter.add(1);
        activeConnections.add(1);
    }

    /**
     * Records an FTP control connection closing.
     *
     * @param durationMs the session duration in milliseconds
     */
    public void connectionClosed(double durationMs) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    /**
     * Records a data connection opening.
     */
    public void dataConnectionOpened() {
        activeDataConnections.add(1);
    }

    /**
     * Records a data connection closing.
     */
    public void dataConnectionClosed() {
        activeDataConnections.add(-1);
    }

    /**
     * Records an FTP command execution.
     *
     * @param command the command name (e.g., "RETR", "STOR", "LIST")
     */
    public void commandExecuted(String command) {
        commandCounter.add(1, Attributes.of("ftp.command", command));
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

    /**
     * Records a file upload (STOR).
     *
     * @param sizeBytes the file size in bytes
     * @param durationMs the transfer duration in milliseconds
     */
    public void fileUploaded(long sizeBytes, double durationMs) {
        transfersTotal.add(1, Attributes.of("ftp.direction", "upload"));
        uploadsCounter.add(1);
        bytesUploaded.add(sizeBytes);
        transferDuration.record(durationMs, Attributes.of("ftp.direction", "upload"));
        transferSize.record(sizeBytes, Attributes.of("ftp.direction", "upload"));
    }

    /**
     * Records a file download (RETR).
     *
     * @param sizeBytes the file size in bytes
     * @param durationMs the transfer duration in milliseconds
     */
    public void fileDownloaded(long sizeBytes, double durationMs) {
        transfersTotal.add(1, Attributes.of("ftp.direction", "download"));
        downloadsCounter.add(1);
        bytesDownloaded.add(sizeBytes);
        transferDuration.record(durationMs, Attributes.of("ftp.direction", "download"));
        transferSize.record(sizeBytes, Attributes.of("ftp.direction", "download"));
    }

    /**
     * Records a directory listing operation.
     *
     * @param command the listing command (LIST, NLST, MLSD)
     */
    public void directoryListed(String command) {
        directoryListings.add(1, Attributes.of("ftp.command", command));
    }

    /**
     * Records a directory creation.
     */
    public void directoryCreated() {
        directoryCreations.add(1);
    }

    /**
     * Records a file deletion.
     */
    public void fileDeleted() {
        fileDeletions.add(1);
    }

    /**
     * Records an AUTH TLS upgrade.
     */
    public void authTlsUpgraded() {
        authTlsUpgrades.add(1);
    }

}

