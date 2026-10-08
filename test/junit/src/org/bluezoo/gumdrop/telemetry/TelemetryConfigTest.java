/*
 * TelemetryConfigTest.java
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

import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the pure configuration, trace and meter factory behaviour
 * of {@link TelemetryConfig}. Lifecycle methods that register JMX beans
 * ({@code init}) are not exercised.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryConfigTest {

    private static final String TRACEPARENT =
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    public void defaults() {
        TelemetryConfig c = new TelemetryConfig();
        assertTrue(c.isMetricsEnabled());
        assertEquals("gumdrop", c.getServiceName());
        assertFalse(c.isIncludeExceptionDetails());
        assertTrue(c.isJmxBridgeEnabled());
        assertFalse(c.isShuttingDown());
        assertTrue(c.getExporter() instanceof DefaultExporter);
        assertTrue(c.accepts(LogLevel.WARN));
        assertFalse(c.accepts(LogLevel.QLOG));
    }

    @Test
    public void simpleAccessorsRoundTrip() {
        TelemetryConfig c = new TelemetryConfig();
        c.setMetricsEnabled(false);
        c.setServiceName("svc");
        c.setServiceVersion("1.2");
        c.setServiceNamespace("ns");
        c.setServiceInstanceId("i-1");
        c.setDeploymentEnvironment("prod");
        c.setJmxBridgeEnabled(false);
        c.setIncludeExceptionDetails(true);

        assertFalse(c.isMetricsEnabled());
        assertEquals("svc", c.getServiceName());
        assertEquals("1.2", c.getServiceVersion());
        assertEquals("ns", c.getServiceNamespace());
        assertEquals("i-1", c.getServiceInstanceId());
        assertEquals("prod", c.getDeploymentEnvironment());
        assertFalse(c.isJmxBridgeEnabled());
        assertTrue(c.isIncludeExceptionDetails());
    }

    @Test
    public void resourceAttributes() {
        TelemetryConfig c = new TelemetryConfig();
        c.addResourceAttribute("k", "v");
        assertEquals("v", c.getResourceAttributes().get("k"));
    }

    @Test
    public void tracesAreCreatedOnlyWhenAnExporterTakesThem() {
        TelemetryConfig c = new TelemetryConfig();
        assertNull(c.createTrace("root"));
        assertNull(c.createTrace("root", SpanKind.CLIENT));
        assertNull(c.createTraceFromTraceparent(TRACEPARENT, "root", SpanKind.SERVER));
        c.setExporter(new RecordingExporter());
        Trace trace = c.createTrace("root");
        assertNotNull(trace);
        assertNotNull(trace.getRootSpan());
        assertNotNull(c.createTrace("root", SpanKind.CLIENT));
    }

    @Test
    public void createTraceContinuesIncomingTraceparent() {
        TelemetryConfig c = new TelemetryConfig();
        c.setExporter(new RecordingExporter());
        Trace trace = c.createTraceFromTraceparent(TRACEPARENT, "root", SpanKind.SERVER);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.getTraceIdHex());
    }

    @Test
    public void metersAreCachedPerNameAndVersion() {
        TelemetryConfig c = new TelemetryConfig();
        Meter a = c.getMeter("m");
        assertSame(a, c.getMeter("m"));
        Meter v1 = c.getMeter("m", "1");
        assertNotSame(a, v1);
        assertSame(v1, c.getMeter("m", "1", "http://schema"));
        assertEquals(2, c.getMeters().size());
    }

    @Test
    public void shutdownIsIdempotent() {
        TelemetryConfig c = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        c.setExporter(exporter);
        c.shutdown();
        assertTrue(c.isShuttingDown());
        c.shutdown();
        assertEquals(1, exporter.forceFlushes);
        assertEquals(1, exporter.shutdowns);
    }

    @Test
    public void exporterTreeDecidesWhatIsAccepted() {
        TelemetryConfig c = new TelemetryConfig();
        RecordingExporter access = new RecordingExporter(LogLevel.ACCESS);
        c.setExporter(new TeeExporter(new DefaultExporter(), access));
        assertTrue(c.accepts(LogLevel.INFO));
        assertTrue(c.accepts(LogLevel.ACCESS));
        assertFalse(c.accepts(LogLevel.QLOG));
        Trace trace = c.createTrace("root");
        trace.end();
        assertSame(trace, access.traces.get(0));
    }

    @Test
    public void toStringSummarises() {
        TelemetryConfig c = new TelemetryConfig();
        String text = c.toString();
        assertTrue(text.startsWith("TelemetryConfig["));
        assertTrue(text.contains("service=gumdrop"));
        assertTrue(text.contains("exporter=DefaultExporter"));
    }

    @Test
    public void initStartsEveryExporterInTheTree() {
        TelemetryConfig c = new TelemetryConfig();
        RecordingExporter a = new RecordingExporter();
        RecordingExporter b = new RecordingExporter();
        c.setExporter(new TeeExporter(a, b));
        assertEquals(0, a.inits);
        c.setJmxBridgeEnabled(false);
        c.init();
        assertEquals(1, a.inits);
        assertEquals(1, b.inits);
    }

    @Test
    public void anExporterSetAfterInitIsStartedAtOnce() {
        TelemetryConfig c = new TelemetryConfig();
        c.setJmxBridgeEnabled(false);
        c.init();
        RecordingExporter late = new RecordingExporter();
        c.setExporter(late);
        assertEquals(1, late.inits);
    }

    @Test
    public void anExporterThatFailsToStartDoesNotStopItsSibling() {
        TelemetryConfig c = new TelemetryConfig();
        RecordingExporter ok = new RecordingExporter();
        c.setExporter(new TeeExporter(new RecordingExporter() {
            @Override
            public void init(TelemetryConfig config) {
                throw new IllegalStateException("cannot start");
            }
        }, ok));
        c.setJmxBridgeEnabled(false);
        c.init();
        assertEquals(1, ok.inits);
    }
}
