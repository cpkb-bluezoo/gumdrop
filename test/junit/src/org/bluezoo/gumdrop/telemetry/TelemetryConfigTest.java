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

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for the pure configuration, trace and meter factory behaviour
 * of {@link TelemetryConfig}. Lifecycle methods that register JVM shutdown
 * hooks or JMX beans ({@code init}, {@code setExporter}) are not exercised.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryConfigTest {

    private static final String TRACEPARENT =
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    public void defaults() {
        TelemetryConfig c = new TelemetryConfig();
        assertTrue(c.isTracesEnabled());
        assertTrue(c.isLogsEnabled());
        assertTrue(c.isMetricsEnabled());
        assertEquals("gumdrop", c.getServiceName());
        assertEquals(TelemetryConfig.ExporterType.OTLP, c.getExporterType());
        assertEquals(TelemetryConfig.Protocol.HTTP_PROTOBUF, c.getProtocol());
        assertEquals(AggregationTemporality.CUMULATIVE, c.getMetricsTemporality());
        assertEquals(60000L, c.getMetricsIntervalMs());
        assertEquals(10000, c.getTimeoutMs());
        assertEquals(KeystoreFormat.PKCS12, c.getTruststoreFormat());
        assertEquals(8192, c.getFileBufferSize());
        assertEquals(512, c.getBatchSize());
        assertEquals(5000L, c.getFlushIntervalMs());
        assertEquals(2048, c.getMaxQueueSize());
        assertFalse(c.isIncludeExceptionDetails());
        assertTrue(c.isJmxBridgeEnabled());
        assertFalse(c.isExportConfigured());
        assertFalse(c.isShuttingDown());
        assertNull(c.getExporter());
        assertNull(c.getEndpoint());
    }

    @Test
    public void simpleAccessorsRoundTrip() {
        TelemetryConfig c = new TelemetryConfig();
        c.setTracesEnabled(false);
        c.setLogsEnabled(false);
        c.setMetricsEnabled(false);
        c.setServiceName("svc");
        c.setServiceVersion("1.2");
        c.setServiceNamespace("ns");
        c.setServiceInstanceId("i-1");
        c.setDeploymentEnvironment("prod");
        c.setExporterType(TelemetryConfig.ExporterType.FILE);
        c.setProtocol(TelemetryConfig.Protocol.GRPC);
        c.setTimeoutMs(5);
        c.setTruststorePass("secret");
        c.setTruststoreFormat(KeystoreFormat.JKS);
        c.setMetricsIntervalMs(1000L);
        c.setBatchSize(10);
        c.setFlushIntervalMs(20L);
        c.setMaxQueueSize(30);
        c.setJmxBridgeEnabled(false);
        c.setIncludeExceptionDetails(true);

        assertFalse(c.isTracesEnabled());
        assertFalse(c.isLogsEnabled());
        assertFalse(c.isMetricsEnabled());
        assertEquals("svc", c.getServiceName());
        assertEquals("1.2", c.getServiceVersion());
        assertEquals("ns", c.getServiceNamespace());
        assertEquals("i-1", c.getServiceInstanceId());
        assertEquals("prod", c.getDeploymentEnvironment());
        assertEquals(TelemetryConfig.ExporterType.FILE, c.getExporterType());
        assertEquals(TelemetryConfig.Protocol.GRPC, c.getProtocol());
        assertEquals(5, c.getTimeoutMs());
        assertEquals("secret", c.getTruststorePass());
        assertEquals(KeystoreFormat.JKS, c.getTruststoreFormat());
        assertEquals(1000L, c.getMetricsIntervalMs());
        assertEquals(10, c.getBatchSize());
        assertEquals(20L, c.getFlushIntervalMs());
        assertEquals(30, c.getMaxQueueSize());
        assertFalse(c.isJmxBridgeEnabled());
        assertTrue(c.isIncludeExceptionDetails());
        assertTrue(c.isExportConfigured());
    }

    @Test
    public void resourceAttributes() {
        TelemetryConfig c = new TelemetryConfig();
        c.addResourceAttribute("k", "v");
        assertEquals("v", c.getResourceAttributes().get("k"));
    }

    @Test
    public void endpointsDeriveFromBaseUnlessOverridden() {
        TelemetryConfig c = new TelemetryConfig();
        assertNull(c.getTracesEndpoint());
        assertNull(c.getLogsEndpoint());
        assertNull(c.getMetricsEndpoint());
        c.setEndpoint("http://collector:4318");
        assertTrue(c.isExportConfigured());
        assertEquals("http://collector:4318/v1/traces", c.getTracesEndpoint());
        assertEquals("http://collector:4318/v1/logs", c.getLogsEndpoint());
        assertEquals("http://collector:4318/v1/metrics", c.getMetricsEndpoint());
        c.setTracesEndpoint("http://t");
        c.setLogsEndpoint("http://l");
        c.setMetricsEndpoint("http://m");
        assertEquals("http://t", c.getTracesEndpoint());
        assertEquals("http://l", c.getLogsEndpoint());
        assertEquals("http://m", c.getMetricsEndpoint());
    }

    @Test
    public void anySpecificEndpointCountsAsExportConfigured() {
        TelemetryConfig c = new TelemetryConfig();
        c.setMetricsEndpoint("http://m");
        assertTrue(c.isExportConfigured());
    }

    @Test
    public void temporalityIsSetByEnum() {
        TelemetryConfig c = new TelemetryConfig();
        c.setMetricsTemporality(AggregationTemporality.DELTA);
        assertEquals(AggregationTemporality.DELTA, c.getMetricsTemporality());
        c.setMetricsTemporality(AggregationTemporality.CUMULATIVE);
        assertEquals(AggregationTemporality.CUMULATIVE, c.getMetricsTemporality());
    }

    @Test
    public void headersAreParsedAndCacheInvalidated() {
        TelemetryConfig c = new TelemetryConfig();
        assertTrue(c.getParsedHeaders().isEmpty());
        c.setHeaders("a=1, b = two ,bad,=x,c=3=4");
        assertEquals("a=1, b = two ,bad,=x,c=3=4", c.getHeaders());
        Map<String, String> parsed = c.getParsedHeaders();
        assertEquals("1", parsed.get("a"));
        assertEquals("two", parsed.get("b"));
        assertEquals("3=4", parsed.get("c"));
        assertEquals(3, parsed.size());
        assertSame(parsed.get("a"), c.getParsedHeaders().get("a"));
        c.setHeaders("z=9");
        assertEquals(1, c.getParsedHeaders().size());
        assertEquals("9", c.getParsedHeaders().get("z"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void parsedHeadersAreUnmodifiable() {
        TelemetryConfig c = new TelemetryConfig();
        c.setHeaders("a=1");
        c.getParsedHeaders().put("x", "y");
    }

    @Test
    public void pathSettersDoNotTouchDisk() {
        TelemetryConfig c = new TelemetryConfig();
        c.setTruststoreFile(Path.of("/nonexistent/ts.p12"));
        c.setFileTracesPath(Path.of("/nonexistent/traces"));
        c.setFileLogsPath(Path.of("/nonexistent/logs"));
        c.setFileMetricsPath(Path.of("/nonexistent/metrics"));
        assertEquals(Path.of("/nonexistent/ts.p12"), c.getTruststoreFile());
        assertEquals(Path.of("/nonexistent/traces"), c.getFileTracesPath());
        assertEquals(Path.of("/nonexistent/logs"), c.getFileLogsPath());
        assertEquals(Path.of("/nonexistent/metrics"), c.getFileMetricsPath());
        Path p = Path.of("/other");
        c.setTruststoreFile(p);
        c.setFileTracesPath(p);
        c.setFileLogsPath(p);
        c.setFileMetricsPath(p);
        assertSame(p, c.getTruststoreFile());
        assertSame(p, c.getFileTracesPath());
        assertSame(p, c.getFileLogsPath());
        assertSame(p, c.getFileMetricsPath());
    }

    @Test
    public void fileBufferSizeFromString() {
        TelemetryConfig c = new TelemetryConfig();
        c.setFileBufferSize("4096");
        assertEquals(4096, c.getFileBufferSize());
        c.setFileBufferSize(16);
        assertEquals(16, c.getFileBufferSize());
    }

    @Test
    public void createTraceHonoursEnabledFlag() {
        TelemetryConfig c = new TelemetryConfig();
        Trace trace = c.createTrace("root");
        assertNotNull(trace);
        assertNotNull(trace.getRootSpan());
        assertNotNull(c.createTrace("root", SpanKind.CLIENT));
        c.setTracesEnabled(false);
        assertNull(c.createTrace("root"));
        assertNull(c.createTrace("root", SpanKind.CLIENT));
        assertNull(c.createTraceFromTraceparent(TRACEPARENT, "root", SpanKind.SERVER));
    }

    @Test
    public void createTraceContinuesIncomingTraceparent() {
        TelemetryConfig c = new TelemetryConfig();
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
    public void shutdownIsIdempotentWithoutExporter() {
        TelemetryConfig c = new TelemetryConfig();
        c.shutdown();
        assertTrue(c.isShuttingDown());
        c.shutdown();
    }

    @Test
    public void toStringSummarises() {
        TelemetryConfig c = new TelemetryConfig();
        c.setEndpoint("http://e");
        String text = c.toString();
        assertTrue(text.startsWith("TelemetryConfig["));
        assertTrue(text.contains("service=gumdrop"));
        assertTrue(text.contains("endpoint=http://e"));
        assertTrue(text.contains("temporality="));
    }
}
