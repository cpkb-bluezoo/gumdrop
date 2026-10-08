/*
 * RecordingExporter.java
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

package org.bluezoo.gumdrop.testsupport;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;

/**
 * An exporter that keeps everything it is given, for tests. It accepts
 * the levels it is created with, and traces.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RecordingExporter implements TelemetryExporter {

    public final List<LogRecord> records = new ArrayList<LogRecord>();
    public final List<Trace> traces = new ArrayList<Trace>();
    public final List<List<MetricData>> metrics = new ArrayList<List<MetricData>>();
    public int flushes;
    public int forceFlushes;
    public int shutdowns;

    private final EnumSet<LogLevel> levels;

    /** Creates an exporter accepting every level. */
    public RecordingExporter() {
        this(LogLevel.values());
    }

    /**
     * Creates an exporter accepting the given levels.
     *
     * @param levels the levels
     */
    public RecordingExporter(LogLevel... levels) {
        this.levels = EnumSet.noneOf(LogLevel.class);
        for (LogLevel level : levels) {
            this.levels.add(level);
        }
    }

    @Override
    public boolean accepts(LogLevel level) {
        return levels.contains(level);
    }

    @Override
    public boolean acceptsTraces() {
        return true;
    }

    @Override
    public synchronized void export(LogRecord record) {
        if (record != null && levels.contains(record.getLevel())) {
            records.add(record);
        }
    }

    @Override
    public synchronized void export(Trace trace) {
        traces.add(trace);
    }

    @Override
    public synchronized void export(List<MetricData> metrics) {
        this.metrics.add(metrics);
    }

    @Override
    public void flush() {
        flushes++;
    }

    @Override
    public void forceFlush() {
        forceFlushes++;
    }

    @Override
    public void shutdown() {
        shutdowns++;
    }

    /**
     * Returns the records with a key.
     *
     * @param key the event name
     * @return the records, in order
     */
    public synchronized List<LogRecord> named(String key) {
        List<LogRecord> result = new ArrayList<LogRecord>();
        for (LogRecord r : records) {
            if (key.equals(r.getKey())) {
                result.add(r);
            }
        }
        return result;
    }

}
