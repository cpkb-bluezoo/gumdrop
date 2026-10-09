/*
 * TelemetryTestData.java
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;

/**
 * Shared fixture builders for telemetry serializer tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TelemetryTestData {

    private TelemetryTestData() {
    }

    public static Map<String, String> resourceAttributes() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("host.name", "testhost");
        return m;
    }

    /** Builds a trace with children, events, links, errors and all attribute types. */
    public static Trace richTrace() {
        Trace trace = new Trace("root-op", SpanKind.SERVER);
        Span root = trace.getRootSpan();
        root.addAttribute("s", "str");
        root.addAttribute("b", true);
        root.addAttribute("i", 42L);
        root.addAttribute("d", 2.5);
        SpanEvent ev = new SpanEvent("ev", 12345L);
        ev.addAttribute("es", "x");
        ev.addAttribute("eb", false);
        ev.addAttribute("ei", 7L);
        ev.addAttribute("ed", 1.5);
        root.addEvent(ev);
        byte[] tid = new byte[16];
        byte[] sid = new byte[8];
        tid[15] = 1;
        sid[7] = 2;
        SpanLink link = new SpanLink(new SpanContext(tid, sid, true));
        link.addAttribute("ls", "lv");
        root.addLink(link);
        root.setStatusOk();
        Span child = root.startChild("child", SpanKind.CLIENT);
        child.setStatusError("boom");
        child.recordException(new IllegalStateException("bad"));
        child.end();
        Span child2 = root.startChild("child2", SpanKind.PRODUCER);
        child2.end();
        Span child3 = root.startChild("child3", SpanKind.CONSUMER);
        child3.end();
        Span child4 = root.startChild("child4", SpanKind.INTERNAL);
        child4.end();
        root.end();
        trace.end();
        return trace;
    }

    public static List<LogRecord> logRecords() {
        List<LogRecord> list = new ArrayList<LogRecord>();
        Trace trace = new Trace("op", SpanKind.SERVER);
        LogRecord a = new LogRecord(LogLevel.INFO, "info.with_span").body("with span")
                .span(trace.getRootSpan());
        a.attr("k", "v");
        a.addAttribute(Attribute.bool("b", true));
        a.addAttribute(Attribute.integer("i", 5L));
        a.addAttribute(Attribute.doubleValue("d", 0.5));
        list.add(a);
        list.add(new LogRecord(LogLevel.INFO, "info.no_span").body("no span"));
        list.add(new LogRecord(LogLevel.WARN, "warn.thing").body("warn").span(trace.getRootSpan()));
        list.add(new LogRecord(LogLevel.ERROR, "err.thing").body("err").span(trace.getRootSpan()));
        list.add(new LogRecord(LogLevel.ERROR, "err.no_body"));
        return list;
    }

    public static List<MetricData> metrics() {
        long now = 2000000000L;
        List<MetricData> list = new ArrayList<MetricData>();

        List<NumberDataPoint> longs = new ArrayList<NumberDataPoint>();
        longs.add(new NumberDataPoint(Attributes.of("k", "v", "n", Long.valueOf(3L),
                "f", Double.valueOf(1.5), "t", Boolean.TRUE), 1000L, now, 42L));
        list.add(MetricData.gauge("g1").setDescription("d").setUnit("u")
                .setNumberDataPoints(longs).build());

        List<NumberDataPoint> doubles = new ArrayList<NumberDataPoint>();
        doubles.add(new NumberDataPoint(Attributes.empty(), 1000L, now, 3.25));
        list.add(MetricData.gauge("g2").setNumberDataPoints(doubles).build());

        list.add(MetricData.sum("s1").setMonotonic(true)
                .setTemporality(AggregationTemporality.DELTA)
                .setNumberDataPoints(longs).build());
        list.add(MetricData.sum("s2").setMonotonic(false)
                .setTemporality(AggregationTemporality.CUMULATIVE)
                .setNumberDataPoints(doubles).build());

        List<HistogramDataPoint> hist = new ArrayList<HistogramDataPoint>();
        hist.add(new HistogramDataPoint(Attributes.of("h", "x"), 1000L, now, 10L, 55.5,
                new long[] {1, 2, 3}, new double[] {1.0, 5.0}, 0.5, 9.5));
        list.add(MetricData.histogram("h1").setUnit("ms")
                .setTemporality(AggregationTemporality.DELTA)
                .setHistogramDataPoints(hist).build());
        return list;
    }
}
