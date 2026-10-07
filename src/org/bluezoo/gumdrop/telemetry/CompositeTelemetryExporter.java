/*
 * CompositeTelemetryExporter.java
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

package org.bluezoo.gumdrop.telemetry;

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.metrics.MetricData;

/**
 * Passes telemetry to several exporters. Traces and metrics go to all of
 * them. A log record that names a channel goes only to the exporters that
 * claim it, and one that does not goes to all of them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CompositeTelemetryExporter implements TelemetryExporter {

    private final TelemetryExporter[] exporters;

    /**
     * Creates a composite of the given exporters.
     *
     * @param exporters the exporters
     */
    public CompositeTelemetryExporter(List<TelemetryExporter> exporters) {
        this.exporters = exporters.toArray(new TelemetryExporter[exporters.size()]);
    }

    /**
     * Returns the exporters this one passes telemetry to.
     *
     * @return the exporters
     */
    public List<TelemetryExporter> getExporters() {
        List<TelemetryExporter> result = new ArrayList<TelemetryExporter>(exporters.length);
        for (TelemetryExporter exporter : exporters) {
            result.add(exporter);
        }
        return result;
    }

    @Override
    public void export(Trace trace) {
        for (TelemetryExporter exporter : exporters) {
            exporter.export(trace);
        }
    }

    @Override
    public void export(LogRecord record) {
        String channel = record == null ? null : record.getChannel();
        for (TelemetryExporter exporter : exporters) {
            if (channel == null || exporter.claimsChannel(channel)) {
                exporter.export(record);
            }
        }
    }

    @Override
    public void export(List<MetricData> metrics) {
        for (TelemetryExporter exporter : exporters) {
            exporter.export(metrics);
        }
    }

    @Override
    public boolean claimsChannel(String channel) {
        for (TelemetryExporter exporter : exporters) {
            if (exporter.claimsChannel(channel)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void flush() {
        for (TelemetryExporter exporter : exporters) {
            exporter.flush();
        }
    }

    @Override
    public void forceFlush() {
        for (TelemetryExporter exporter : exporters) {
            exporter.forceFlush();
        }
    }

    @Override
    public void shutdown() {
        for (TelemetryExporter exporter : exporters) {
            exporter.shutdown();
        }
    }
}
