/*
 * DefaultExporterTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.util.LaconicFormatter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The default exporter publishes through the JUL logger of the record's
 * scope, with the bundle and the attribute values as parameters, so the
 * usual formatter prints the localised sentence.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultExporterTest {

    /** The scope: a class of this test's own, so its logger is nobody else's. */
    static final class Scope {
    }

    static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");

    static final class Capture extends Handler {
        final List<java.util.logging.LogRecord> records = new ArrayList<java.util.logging.LogRecord>();
        final CountDownLatch first = new CountDownLatch(1);

        @Override
        public synchronized void publish(java.util.logging.LogRecord record) {
            records.add(record);
            first.countDown();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private Logger scopeLogger;
    private Capture capture;
    private TelemetryConfig config;
    private EventLogger events;

    @Before
    public void attachHandler() {
        scopeLogger = Logger.getLogger(Scope.class.getName());
        scopeLogger.setUseParentHandlers(false);
        scopeLogger.setLevel(Level.ALL);
        capture = new Capture();
        scopeLogger.addHandler(capture);
        config = new TelemetryConfig();
        events = config.getLogger(Scope.class, BUNDLE);
    }

    @After
    public void detachHandler() {
        scopeLogger.removeHandler(capture);
        scopeLogger.setUseParentHandlers(true);
    }

    private static java.util.logging.LogRecord only(Capture capture) {
        assertEquals(1, capture.records.size());
        return capture.records.get(0);
    }

    @Test
    public void aFreshConfigurationHasTheDefaultExporter() {
        assertTrue(config.getExporter() instanceof DefaultExporter);
        assertTrue(config.accepts(LogLevel.INFO));
        assertTrue(config.accepts(LogLevel.WARN));
        assertTrue(config.accepts(LogLevel.ERROR));
        assertFalse(config.accepts(LogLevel.ACCESS));
        assertFalse(config.accepts(LogLevel.QLOG));
        assertFalse(config.getExporter().acceptsTraces());
    }

    @Test
    public void eventIsPublishedThroughTheScopeLoggerWithBundleAndParameters() {
        DefaultExporter exporter = (DefaultExporter) config.getExporter();
        IllegalStateException boom = new IllegalStateException("boom");
        events.warn("warn.qlog_events_dropped").attr("count", 7L).thrown(boom).emit();
        exporter.flush();
        java.util.logging.LogRecord jul = only(capture);
        assertEquals(Level.WARNING, jul.getLevel());
        assertEquals(Scope.class.getName(), jul.getLoggerName());
        assertEquals("warn.qlog_events_dropped", jul.getMessage());
        assertSame(BUNDLE, jul.getResourceBundle());
        assertEquals(1, jul.getParameters().length);
        assertEquals(Long.valueOf(7L), jul.getParameters()[0]);
        assertSame(boom, jul.getThrown());
        String line = new LaconicFormatter().format(jul);
        assertTrue(line, line.startsWith("WARNING: 7 qlog events did not fit in the queue and were dropped"));
        assertTrue(line, line.contains("IllegalStateException: boom"));
    }

    @Test
    public void levelsMapOntoJul() {
        DefaultExporter exporter = (DefaultExporter) config.getExporter();
        events.info("info.qlog_exporter_shutdown").emit();
        events.error("info.qlog_exporter_shutdown").emit();
        exporter.flush();
        assertEquals(2, capture.records.size());
        assertEquals(Level.INFO, capture.records.get(0).getLevel());
        assertEquals(Level.SEVERE, capture.records.get(1).getLevel());
        assertEquals(DefaultExporter.julLevel(LogLevel.ACCESS), Level.INFO);
        assertEquals(DefaultExporter.julLevel(LogLevel.QLOG), Level.FINE);
    }

    @Test
    public void publishingIsAsynchronous() throws Exception {
        DefaultExporter exporter = new DefaultExporter();
        config.setExporter(exporter);
        events.info("info.qlog_exporter_shutdown").emit();
        // the record is queued; the exporter's own thread publishes it
        assertTrue(capture.first.await(5, TimeUnit.SECONDS));
        assertEquals(1, capture.records.size());
        exporter.shutdown();
    }

    @Test
    public void onlyConfiguredLevelsArePublished() {
        DefaultExporter exporter = new DefaultExporter();
        config.setExporter(exporter);
        exporter.export(new LogRecord(LogLevel.ACCESS, "http.server.request"));
        exporter.export(new LogRecord(LogLevel.QLOG, "quic:packet_sent"));
        exporter.flush();
        assertTrue(capture.records.isEmpty());
        exporter.setLevels(LogLevel.ACCESS);
        assertTrue(exporter.accepts(LogLevel.ACCESS));
        assertFalse(exporter.accepts(LogLevel.INFO));
        LogRecord access = new LogRecord(LogLevel.ACCESS, "http.server.request")
                .attr("client.address", "10.0.0.1").attr("http.response.status_code", 200L);
        exporter.export(access);
        exporter.flush();
        // no scope: published through the exporter's own logger, so it is
        // not on the scope logger
        assertTrue(capture.records.isEmpty());
        java.util.logging.LogRecord jul = DefaultExporter.toJul(access);
        assertEquals("http.server.request client.address=10.0.0.1 http.response.status_code=200", jul.getMessage());
        exporter.shutdown();
    }

    @Test
    public void bodyIsTheMessageWhenThereIsNoBundle() {
        LogRecord record = new LogRecord(LogLevel.QLOG, "quic:packet_sent").body("{\"x\":1}");
        assertEquals("{\"x\":1}", DefaultExporter.toJul(record).getMessage());
    }

    @Test
    public void afterShutdownRecordsArePublishedOnTheCallingThread() {
        DefaultExporter exporter = new DefaultExporter();
        config.setExporter(exporter);
        exporter.shutdown();
        events.info("info.qlog_exporter_shutdown").emit();
        // no flush, no wait: it is already there
        assertEquals(1, capture.records.size());
        assertEquals("info.qlog_exporter_shutdown", capture.records.get(0).getMessage());
    }

    @Test
    public void withoutAHandlerTheLineGoesToStderr() throws Exception {
        scopeLogger.removeHandler(capture);
        assertFalse(DefaultExporter.hasHandlers(scopeLogger));
        DefaultExporter exporter = new DefaultExporter();
        config.setExporter(exporter);
        exporter.shutdown();
        PrintStream err = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, "UTF-8"));
        try {
            events.warn("warn.qlog_events_dropped").attr("count", 3L).emit();
        } finally {
            System.setErr(err);
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(text, text.startsWith("WARNING: 3 qlog events did not fit in the queue and were dropped"));
    }

    @Test
    public void aParentHandlerCounts() {
        Logger child = Logger.getLogger(Scope.class.getName() + ".child");
        assertTrue(DefaultExporter.hasHandlers(child));
        child.setUseParentHandlers(false);
        assertFalse(DefaultExporter.hasHandlers(child));
        child.setUseParentHandlers(true);
    }

    @Test
    public void overflowIsCounted() {
        // no publisher thread, so nothing drains until we flush
        DefaultExporter exporter = new DefaultExporter(2, false);
        config.setExporter(exporter);
        for (int i = 0; i < 5; i++) {
            events.info("info.qlog_exporter_shutdown").emit();
        }
        assertEquals(3L, exporter.getDroppedCount());
        assertTrue(capture.records.isEmpty());
        exporter.flush();
        assertEquals(2, capture.records.size());
        exporter.shutdown();
        assertNotNull(exporter);
    }

    @Test
    public void tracesAndMetricsAreIgnored() {
        DefaultExporter exporter = new DefaultExporter();
        exporter.export(new Trace("t", SpanKind.SERVER));
        exporter.export(TelemetryTestData.metrics());
        exporter.flush();
        exporter.shutdown();
        exporter.shutdown();
        assertEquals(0L, exporter.getDroppedCount());
    }

}
