/*
 * CompositeTelemetryExporterTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.junit.Test;

/**
 * Routing of telemetry between several exporters: a record that names a
 * channel reaches only the exporters that claim it, everything else
 * reaches all of them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CompositeTelemetryExporterTest {

    static final class Recording implements TelemetryExporter {
        final String claimed;
        final List<LogRecord> logs = new ArrayList<LogRecord>();
        final List<Trace> traces = new ArrayList<Trace>();
        final List<List<MetricData>> metrics = new ArrayList<List<MetricData>>();
        int flushes;
        int forceFlushes;
        int shutdowns;

        Recording(String claimed) {
            this.claimed = claimed;
        }

        @Override
        public boolean claimsChannel(String channel) {
            return channel.equals(claimed);
        }

        @Override
        public void export(Trace trace) {
            traces.add(trace);
        }

        @Override
        public void export(LogRecord record) {
            logs.add(record);
        }

        @Override
        public void export(List<MetricData> m) {
            metrics.add(m);
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
    }

    private static LogRecord tagged(String channel) {
        LogRecord r = new LogRecord(LogRecord.SEVERITY_INFO, "x");
        r.addAttribute(LogRecord.CHANNEL_ATTRIBUTE, channel);
        return r;
    }

    @Test
    public void taggedRecordGoesOnlyToExportersThatClaimItsChannel() {
        Recording otlp = new Recording(null);
        Recording qlog = new Recording("qlog");
        TelemetryExporter composite = new CompositeTelemetryExporter(Arrays.<TelemetryExporter>asList(otlp, qlog));
        LogRecord r = tagged("qlog");
        composite.export(r);
        assertEquals(0, otlp.logs.size());
        assertEquals(1, qlog.logs.size());
        assertSame(r, qlog.logs.get(0));
    }

    @Test
    public void untaggedRecordGoesToEveryExporter() {
        Recording a = new Recording(null);
        Recording b = new Recording("qlog");
        TelemetryExporter composite = new CompositeTelemetryExporter(Arrays.<TelemetryExporter>asList(a, b));
        composite.export(new LogRecord(LogRecord.SEVERITY_INFO, "plain"));
        assertEquals(1, a.logs.size());
        assertEquals(1, b.logs.size());
    }

    @Test
    public void taggedRecordNobodyClaimsIsDropped() {
        Recording a = new Recording(null);
        TelemetryExporter composite = new CompositeTelemetryExporter(Collections.<TelemetryExporter>singletonList(a));
        composite.export(tagged("qlog"));
        assertEquals(0, a.logs.size());
    }

    @Test
    public void tracesAndMetricsGoToEveryExporter() {
        Recording a = new Recording(null);
        Recording b = new Recording("qlog");
        TelemetryExporter composite = new CompositeTelemetryExporter(Arrays.<TelemetryExporter>asList(a, b));
        Trace trace = new Trace("t", SpanKind.SERVER);
        composite.export(trace);
        List<MetricData> metrics = new ArrayList<MetricData>();
        composite.export(metrics);
        assertEquals(1, a.traces.size());
        assertEquals(1, b.traces.size());
        assertEquals(1, a.metrics.size());
        assertEquals(1, b.metrics.size());
    }

    @Test
    public void lifecycleReachesEveryExporter() {
        Recording a = new Recording(null);
        Recording b = new Recording("qlog");
        TelemetryExporter composite = new CompositeTelemetryExporter(Arrays.<TelemetryExporter>asList(a, b));
        composite.flush();
        composite.forceFlush();
        composite.shutdown();
        assertEquals(1, a.flushes);
        assertEquals(1, b.flushes);
        assertEquals(1, a.forceFlushes);
        assertEquals(1, b.forceFlushes);
        assertEquals(1, a.shutdowns);
        assertEquals(1, b.shutdowns);
    }

    @Test
    public void aCompositeClaimsWhatAnyMemberClaims() {
        TelemetryExporter composite = new CompositeTelemetryExporter(
                Arrays.<TelemetryExporter>asList(new Recording(null), new Recording("qlog")));
        assertTrue(composite.claimsChannel("qlog"));
        assertFalse(composite.claimsChannel("other"));
    }

    @Test
    public void combiningExportersKeepsASingleOneAsItIs() {
        Recording a = new Recording(null);
        assertNull(TelemetryConfig.combine(Collections.<TelemetryExporter>emptyList()));
        assertSame(a, TelemetryConfig.combine(Collections.<TelemetryExporter>singletonList(a)));
        TelemetryExporter both = TelemetryConfig.combine(Arrays.<TelemetryExporter>asList(a, new Recording("qlog")));
        assertTrue(both instanceof CompositeTelemetryExporter);
    }
}
