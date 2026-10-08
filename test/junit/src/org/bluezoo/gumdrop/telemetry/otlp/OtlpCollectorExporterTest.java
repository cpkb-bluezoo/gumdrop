/*
 * OtlpCollectorExporterTest.java
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


package org.bluezoo.gumdrop.telemetry.otlp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.Test;

/**
 * The settings OTLP/HTTP and OTLP/gRPC exporters share, and that neither
 * is started by having them set.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpCollectorExporterTest {

    @Test
    public void defaults() {
        OtlpExporter e = new OtlpExporter();
        assertNull(e.getEndpoint());
        assertNull(e.getTracesEndpoint());
        assertNull(e.getLogsEndpoint());
        assertNull(e.getMetricsEndpoint());
        assertNull(e.getHeaders());
        assertNull(e.getTls());
        assertEquals(10000, e.getTimeoutMs());
        assertTrue(e.parsedHeaders().isEmpty());
    }

    @Test
    public void endpointsDeriveFromBaseUnlessOverridden() {
        OtlpGrpcExporter e = new OtlpGrpcExporter();
        e.setEndpoint("http://collector:4318");
        assertEquals("http://collector:4318/v1/traces", e.getTracesEndpoint());
        assertEquals("http://collector:4318/v1/logs", e.getLogsEndpoint());
        assertEquals("http://collector:4318/v1/metrics", e.getMetricsEndpoint());
        e.setTracesEndpoint("http://t");
        e.setLogsEndpoint("http://l");
        e.setMetricsEndpoint("http://m");
        assertEquals("http://t", e.getTracesEndpoint());
        assertEquals("http://l", e.getLogsEndpoint());
        assertEquals("http://m", e.getMetricsEndpoint());
    }

    @Test
    public void headersAreParsed() {
        OtlpExporter e = new OtlpExporter();
        e.setHeaders("a=1, b = two ,bad,=x,c=3=4");
        assertEquals("a=1, b = two ,bad,=x,c=3=4", e.getHeaders());
        Map<String, String> parsed = e.parsedHeaders();
        assertEquals("1", parsed.get("a"));
        assertEquals("two", parsed.get("b"));
        assertEquals("3=4", parsed.get("c"));
        assertEquals(3, parsed.size());
        e.setHeaders("z=9");
        assertEquals(1, e.parsedHeaders().size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void parsedHeadersAreUnmodifiable() {
        OtlpExporter e = new OtlpExporter();
        e.setHeaders("a=1");
        e.parsedHeaders().put("x", "y");
    }

    @Test
    public void tlsIsAConfigObject() {
        OtlpExporter e = new OtlpExporter();
        TlsConfig tls = new TlsConfig();
        e.setTls(tls);
        assertSame(tls, e.getTls());
        e.setTimeoutMs(5);
        assertEquals(5, e.getTimeoutMs());
    }

    @Test
    public void anExporterWithNothingStartedAcceptsNothing() {
        OtlpExporter http = new OtlpExporter();
        http.setEndpoint("http://collector:4318");
        assertFalse(http.accepts(LogLevel.INFO));
        assertFalse(http.acceptsTraces());
        OtlpGrpcExporter grpc = new OtlpGrpcExporter();
        grpc.setEndpoint("http://collector:4317");
        assertFalse(grpc.accepts(LogLevel.INFO));
        assertFalse(grpc.acceptsTraces());
    }

    @Test
    public void shuttingDownBeforeStartIsHarmless() {
        new OtlpExporter().shutdown();
        new OtlpGrpcExporter().shutdown();
        new OtlpExporter().flush();
    }
}
