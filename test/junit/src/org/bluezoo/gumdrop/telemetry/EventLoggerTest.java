/*
 * EventLoggerTest.java
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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ResourceBundle;

import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.junit.Test;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EventLoggerTest {

    static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");

    @Test
    public void loggerCarriesScopeAndBundleOntoEachRecord() {
        TelemetryConfig config = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        config.setExporter(exporter);
        EventLogger events = config.getLogger(EventLoggerTest.class, BUNDLE);
        assertEquals(EventLoggerTest.class.getName(), events.getScope());
        assertSame(BUNDLE, events.getResourceBundle());

        Trace trace = new Trace("op", SpanKind.SERVER);
        IllegalStateException boom = new IllegalStateException("boom");
        events.warn("warn.something").attr("uri", "/x").attr("count", 2L).thrown(boom)
                .span(trace.getRootSpan()).emit();

        assertEquals(1, exporter.records.size());
        LogRecord r = exporter.records.get(0);
        assertEquals(LogLevel.WARN, r.getLevel());
        assertEquals("warn.something", r.getKey());
        assertEquals(EventLoggerTest.class.getName(), r.getScope());
        assertSame(BUNDLE, r.getResourceBundle());
        assertEquals("uri", r.getAttributes().get(0).getKey());
        assertEquals("count", r.getAttributes().get(1).getKey());
        assertSame(boom, r.getThrown());
        assertEquals(trace.getTraceId(), r.getTraceId());
    }

    @Test
    public void eachLevelHasItsStarter() {
        TelemetryConfig config = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        config.setExporter(exporter);
        EventLogger events = config.getLogger(EventLoggerTest.class, BUNDLE);
        events.info("i").emit();
        events.warn("w").emit();
        events.error("e").emit();
        assertEquals(LogLevel.INFO, exporter.records.get(0).getLevel());
        assertEquals(LogLevel.WARN, exporter.records.get(1).getLevel());
        assertEquals(LogLevel.ERROR, exporter.records.get(2).getLevel());
    }

    @Test
    public void theSameLoggerIsReturnedForAClass() {
        TelemetryConfig config = new TelemetryConfig();
        EventLogger a = config.getLogger(EventLoggerTest.class, BUNDLE);
        assertSame(a, config.getLogger(EventLoggerTest.class, BUNDLE));
        assertNotSame(a, config.getLogger(LogRecordTest.class, BUNDLE));
        assertNotSame(a, new TelemetryConfig().getLogger(EventLoggerTest.class, BUNDLE));
    }

    @Test
    public void aClassWithTwoBundlesHasALoggerForEach() {
        TelemetryConfig config = new TelemetryConfig();
        ResourceBundle other = ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");
        EventLogger a = config.getLogger(EventLoggerTest.class, BUNDLE);
        EventLogger b = config.getLogger(EventLoggerTest.class, other);
        assertNotSame(a, b);
        assertSame(BUNDLE, a.getResourceBundle());
        assertSame(other, b.getResourceBundle());
        assertEquals(a.getScope(), b.getScope());
        assertSame(a, config.getLogger(EventLoggerTest.class, BUNDLE));
        assertSame(b, config.getLogger(EventLoggerTest.class, other));
    }

    @Test
    public void nothingIsBuiltForALevelNobodyAccepts() {
        TelemetryConfig config = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter(LogLevel.ERROR);
        config.setExporter(exporter);
        EventLogger events = config.getLogger(EventLoggerTest.class, BUNDLE);
        assertFalse(events.accepts(LogLevel.INFO));
        assertTrue(events.accepts(LogLevel.ERROR));
        events.info("i").emit();
        events.error("e").emit();
        assertEquals(1, exporter.records.size());
        assertEquals("e", exporter.records.get(0).getKey());
    }

    @Test
    public void anExporterThatLogsThroughThePipelineDoesNotComeBackIn() {
        final TelemetryConfig config = new TelemetryConfig();
        final int[] seen = new int[1];
        config.setExporter(new RecordingExporter() {
            @Override
            public synchronized void export(LogRecord record) {
                seen[0]++;
                // what a badly behaved exporter might do: report through the events it exports
                config.getLogger(EventLoggerTest.class, BUNDLE).warn("warn.nested").emit();
            }
        });
        config.getLogger(EventLoggerTest.class, BUNDLE).info("i").emit();
        assertEquals(1, seen[0]);
    }

    @Test
    public void anExporterThatThrowsDoesNotBreakTheEmitter() {
        TelemetryConfig config = new TelemetryConfig();
        config.setExporter(new RecordingExporter() {
            @Override
            public synchronized void export(LogRecord record) {
                throw new IllegalStateException("broken");
            }
        });
        config.getLogger(EventLoggerTest.class, BUNDLE).info("i").emit();
    }

    @Test
    public void exporterMayNotBeNull() {
        try {
            new TelemetryConfig().setExporter(null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

}
