/*
 * QuicQlog.java
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

package org.bluezoo.gumdrop.quic;

import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;

/**
 * The qlog event sink of one connection: turns an event into a telemetry
 * log record tagged for the qlog channel and hands it to the telemetry
 * pipeline. Whether a connection logs is decided once, when it is created,
 * and a connection that does not holds no instance, so the packet path
 * pays a null test.
 *
 * <p>This is independent of {@link TelemetryConfig#isLogsEnabled()}: that
 * switch is about log records for an OTLP endpoint, and per-packet events
 * must not start because it is on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QuicQlog {

    private final TelemetryConfig config;
    private final String groupId;
    private final String vantagePoint;
    // added to a System.nanoTime() reading to give nanoseconds since the Unix epoch
    private final long wallClockOffsetNanos;

    private QuicQlog(TelemetryConfig config, String groupId, String vantagePoint) {
        this.config = config;
        this.groupId = groupId;
        this.vantagePoint = vantagePoint;
        this.wallClockOffsetNanos = System.currentTimeMillis() * 1_000_000L - System.nanoTime();
    }

    /**
     * Returns the sink for a new connection, or null if it does not log.
     *
     * @param engine the engine the connection belongs to
     * @param server whether the connection is the server's end
     * @param originalDcid the connection's original destination connection ID
     * @return the sink, or null
     */
    static QuicQlog create(QuicEngine engine, boolean server, byte[] originalDcid) {
        TelemetryConfig config = engine.getTelemetryConfig();
        if (config == null || originalDcid == null) {
            return null;
        }
        if (!engine.isQlogEnabled() && !config.isQlogConfigured()) {
            return null;
        }
        return new QuicQlog(config, QlogJson.hex(originalDcid), server ? "server" : "client");
    }

    /**
     * Emits an event.
     *
     * @param name the event name, from {@link QlogEvents}
     * @param nanoTime the time of the event, as read from {@code System.nanoTime()}
     *                 (plus any test offset)
     * @param data the event's data object
     */
    void emit(String name, long nanoTime, QlogJson data) {
        TelemetryExporter exporter = config.getExporter();
        if (exporter == null) {
            return;
        }
        LogRecord record = new LogRecord(nanoTime + wallClockOffsetNanos, LogRecord.SEVERITY_DEBUG, data.build());
        record.addAttribute(LogRecord.CHANNEL_ATTRIBUTE, QlogAttributes.CHANNEL);
        record.addAttribute(QlogAttributes.NAME, name);
        record.addAttribute(QlogAttributes.SCHEMA, QlogAttributes.SCHEMA_QUIC);
        record.addAttribute(QlogAttributes.GROUP_ID, groupId);
        record.addAttribute(QlogAttributes.VANTAGE_POINT, vantagePoint);
        exporter.export(record);
    }
}
