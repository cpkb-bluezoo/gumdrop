/*
 * TeeExporterTest.java
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.junit.Test;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TeeExporterTest {

    /** An exporter that fails at everything. */
    static final class Broken implements TelemetryExporter {
        @Override
        public void export(Trace trace) {
            throw new IllegalStateException("trace");
        }

        @Override
        public void export(LogRecord record) {
            throw new IllegalStateException("record");
        }

        @Override
        public void export(List<MetricData> metrics) {
            throw new IllegalStateException("metrics");
        }

        @Override
        public boolean accepts(LogLevel level) {
            return level == LogLevel.QLOG;
        }

        @Override
        public boolean acceptsTraces() {
            return false;
        }

        @Override
        public void flush() {
            throw new IllegalStateException("flush");
        }

        @Override
        public void shutdown() {
            throw new IllegalStateException("shutdown");
        }
    }

    @Test
    public void bothChildrenReceiveEverySignal() {
        RecordingExporter a = new RecordingExporter();
        RecordingExporter b = new RecordingExporter();
        TeeExporter tee = new TeeExporter(a, b);
        LogRecord record = new LogRecord(LogLevel.INFO, "k");
        Trace trace = new Trace("t", SpanKind.SERVER);
        List<MetricData> metrics = TelemetryTestData.metrics();
        tee.export(record);
        tee.export(trace);
        tee.export(metrics);
        assertSame(record, a.records.get(0));
        assertSame(record, b.records.get(0));
        assertSame(trace, a.traces.get(0));
        assertSame(trace, b.traces.get(0));
        assertSame(metrics, a.metrics.get(0));
        assertSame(metrics, b.metrics.get(0));
    }

    @Test
    public void aFailingChildDoesNotSkipTheOther() {
        RecordingExporter good = new RecordingExporter();
        TeeExporter brokenFirst = new TeeExporter(new Broken(), good);
        TeeExporter brokenSecond = new TeeExporter(good, new Broken());
        for (TeeExporter tee : new TeeExporter[] {brokenFirst, brokenSecond}) {
            tee.export(new LogRecord(LogLevel.WARN, "k"));
            tee.export(new Trace("t", SpanKind.SERVER));
            tee.export(TelemetryTestData.metrics());
            tee.flush();
            tee.forceFlush();
            tee.shutdown();
        }
        assertEquals(2, good.records.size());
        assertEquals(2, good.traces.size());
        assertEquals(2, good.metrics.size());
        assertEquals(2, good.flushes);
        assertEquals(2, good.forceFlushes);
        assertEquals(2, good.shutdowns);
    }

    @Test
    public void nestedTeesReachEveryMember() {
        RecordingExporter a = new RecordingExporter();
        RecordingExporter b = new RecordingExporter();
        RecordingExporter c = new RecordingExporter();
        TeeExporter tee = new TeeExporter(a, new TeeExporter(b, c));
        LogRecord record = new LogRecord(LogLevel.ERROR, "k");
        tee.export(record);
        assertSame(record, a.records.get(0));
        assertSame(record, b.records.get(0));
        assertSame(record, c.records.get(0));
        tee.shutdown();
        assertEquals(1, a.shutdowns);
        assertEquals(1, b.shutdowns);
        assertEquals(1, c.shutdowns);
    }

    @Test
    public void theSameExporterMayBeAChildOfTwoTees() {
        RecordingExporter shared = new RecordingExporter();
        TeeExporter one = new TeeExporter(shared, new RecordingExporter());
        TeeExporter two = new TeeExporter(new RecordingExporter(), shared);
        one.export(new LogRecord(LogLevel.INFO, "a"));
        two.export(new LogRecord(LogLevel.INFO, "b"));
        assertEquals(2, shared.records.size());
    }

    @Test
    public void aTeeAcceptsWhatEitherChildAccepts() {
        TeeExporter tee = new TeeExporter(new Broken(), new RecordingExporter(LogLevel.ACCESS));
        assertTrue(tee.accepts(LogLevel.QLOG));
        assertTrue(tee.accepts(LogLevel.ACCESS));
        assertFalse(tee.accepts(LogLevel.INFO));
        assertTrue(tee.acceptsTraces());
        assertFalse(new TeeExporter(new Broken(), new Broken()).acceptsTraces());
    }

    @Test
    public void aTeeNeedsTwoChildren() {
        try {
            new TeeExporter(null, new RecordingExporter());
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            new TeeExporter(new RecordingExporter(), null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

}
