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

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * The qlog event sink of one connection: turns an event into a telemetry
 * log record of level {@link LogLevel#QLOG} and hands it to the telemetry
 * pipeline. Whether a connection logs is decided once, when it is created,
 * by whether any configured exporter accepts that level; a connection that
 * does not log holds no instance, so the packet path pays a null test.
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
        if (config == null || originalDcid == null || !config.accepts(LogLevel.QLOG)) {
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
        emit(QlogAttributes.SCHEMA_QUIC, name, nanoTime, data.build());
    }

    /**
     * Emits an event of any schema whose data is already JSON text.
     *
     * @param schema the event schema URI
     * @param name the event name
     * @param nanoTime the time of the event, as for {@link #emit(String, long, QlogJson)}
     * @param dataJson the event's data object
     */
    void emit(String schema, String name, long nanoTime, String dataJson) {
        LogRecord record = new LogRecord(nanoTime + wallClockOffsetNanos, LogLevel.QLOG, name)
                .body(dataJson)
                .attr(QlogAttributes.SCHEMA, schema)
                .attr(QlogAttributes.GROUP_ID, groupId)
                .attr(QlogAttributes.VANTAGE_POINT, vantagePoint);
        config.getExporter().export(record);
    }
}
