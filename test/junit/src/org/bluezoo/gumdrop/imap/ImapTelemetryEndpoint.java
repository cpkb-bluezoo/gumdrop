/*
 * ImapTelemetryEndpoint.java
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

package org.bluezoo.gumdrop.imap;

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;

/**
 * Telemetry helpers for IMAP session tests: a configuration that keeps the
 * traces it creates in memory (no exporter or network is involved) and a
 * span-tree collector.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ImapTelemetryEndpoint {

    private ImapTelemetryEndpoint() {
    }

    /** Config that remembers the traces it created. */
    static final class CapturingConfig extends TelemetryConfig {
        final List<Trace> traces = new ArrayList<Trace>();

        CapturingConfig() {
            exporter(new RecordingExporter());
        }

        @Override
        public Trace createTrace(String rootSpanName, SpanKind kind) {
            Trace trace = super.createTrace(rootSpanName, kind);
            if (trace != null) {
                traces.add(trace);
            }
            return trace;
        }
    }

    /** Collects every event name and attribute key under a span tree. */
    static void collect(Span span, List<String> events, List<String> attrs) {
        for (SpanEvent e : span.getEvents()) {
            events.add(e.getName());
        }
        for (Attribute a : span.getAttributes()) {
            attrs.add(a.getKey());
        }
        for (Span child : span.getChildren()) {
            collect(child, events, attrs);
        }
    }
}
