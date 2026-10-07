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

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.json.JSONException;

/**
 * A telemetry exporter that keeps the qlog events it is given, for tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QlogCapture implements TelemetryExporter {

    private final List<LogRecord> records = new ArrayList<LogRecord>();

    @Override
    public boolean claimsChannel(String channel) {
        return QlogAttributes.CHANNEL.equals(channel);
    }

    @Override
    public synchronized void export(LogRecord record) {
        records.add(record);
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

    /** Returns the value of a string attribute of a record, or null. */
    public static String attribute(LogRecord record, String key) {
        for (Attribute a : record.getAttributes()) {
            if (a.getKey().equals(key) && a.getType() == Attribute.TYPE_STRING) {
                return a.getStringValue();
            }
        }
        return null;
    }

    /** Returns the data of every event of this name, flattened, in the order they were reported. */
    public synchronized List<Map<String, String>> events(String name) throws JSONException {
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (LogRecord r : records) {
            if (name.equals(attribute(r, QlogAttributes.NAME))) {
                result.add(FlatJson.flatten(r.getBody()));
            }
        }
        return result;
    }

    /** Returns the event schema URI of the first event of this name, or null. */
    public synchronized String schemaOf(String name) {
        for (LogRecord r : records) {
            if (name.equals(attribute(r, QlogAttributes.NAME))) {
                return attribute(r, QlogAttributes.SCHEMA);
            }
        }
        return null;
    }

    /** Returns the group id of the first event, or null if there is none. */
    public synchronized String firstGroupId() {
        return records.isEmpty() ? null : attribute(records.get(0), QlogAttributes.GROUP_ID);
    }

    public synchronized int size() {
        return records.size();
    }
}
