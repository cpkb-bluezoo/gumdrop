/*
 * TelemetryExporter.java
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

package org.bluezoo.gumdrop.telemetry;

import org.bluezoo.gumdrop.telemetry.metrics.MetricData;

import java.util.List;

/**
 * Interface for exporting trace, log, and metric data.
 * Implementations may send data to an OpenTelemetry Collector,
 * log to a file, or export to other observability backends.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface TelemetryExporter {

    /**
     * Exports a completed trace.
     *
     * @param trace the trace to export
     */
    void export(Trace trace);

    /**
     * Exports a log record.
     *
     * @param record the log record to export
     */
    void export(LogRecord record);

    /**
     * Exports a batch of metric data.
     *
     * @param metrics the metrics to export
     */
    void export(List<MetricData> metrics);

    /**
     * Flushes any buffered telemetry data.
     * This method blocks until the flush is complete.
     */
    void flush();

    /**
     * Shuts down the exporter, releasing any resources.
     */
    void shutdown();

}


