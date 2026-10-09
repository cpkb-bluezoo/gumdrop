/*
 * QlogCapture.java
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
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.json.JSONException;

/**
 * An exporter that keeps the qlog records it is given, for tests that
 * look at what the transport reports.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QlogCapture implements TelemetryExporter {

    private final List<LogRecord> records = new ArrayList<LogRecord>();

    @Override
    public boolean accepts(LogLevel level) {
        return level == LogLevel.QLOG;
    }

    @Override
    public boolean acceptsTraces() {
        return false;
    }

    @Override
    public synchronized void export(LogRecord record) {
        if (record != null && record.getLevel() == LogLevel.QLOG) {
            records.add(record);
        }
    }

    @Override
    public void export(Trace trace) {
    }

    @Override
    public void export(List<MetricData> metrics) {
    }

    @Override
    public void flush() {
    }

    @Override
    public void shutdown() {
    }

    public synchronized List<Map<String, String>> events(String name) throws JSONException {
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (LogRecord r : records) {
            if (name.equals(r.getKey())) {
                result.add(FlatJson.flatten(r.getBody()));
            }
        }
        return result;
    }

    public synchronized String schemaOf(String name) {
        for (LogRecord r : records) {
            if (name.equals(r.getKey())) {
                return r.getString(QlogAttributes.SCHEMA);
            }
        }
        return null;
    }

    public synchronized String firstGroupId() {
        return records.isEmpty() ? null : records.get(0).getString(QlogAttributes.GROUP_ID);
    }

    public synchronized int size() {
        return records.size();
    }
}
