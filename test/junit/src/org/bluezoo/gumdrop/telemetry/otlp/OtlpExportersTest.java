/*
 * OtlpExportersTest.java
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

package org.bluezoo.gumdrop.telemetry.otlp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryTestData;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.junit.Test;

/**
 * Tests for the OTLP HTTP and gRPC exporters (without any collector
 * endpoint configured) and their response handlers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpExportersTest {

    private static TelemetryConfig config() {
        TelemetryConfig config = new TelemetryConfig();
        config.setServiceName("svc");
        config.setServiceVersion("1");
        config.setServiceNamespace("ns");
        config.setServiceInstanceId("inst");
        config.setDeploymentEnvironment("test");
        config.setMetricsEnabled(true);
        Meter meter = config.getMeter("scope");
        LongCounter counter = meter.counterBuilder("c").build();
        counter.add(1L);
        return config;
    }

    private static OtlpExporter http() {
        OtlpExporter e = new OtlpExporter();
        e.setTimeoutMs(500);
        e.setMetricsIntervalMs(50L);
        e.setMaxQueueSize(2);
        e.start(config(), false);
        return e;
    }

    private static OtlpGrpcExporter grpc() {
        OtlpGrpcExporter e = new OtlpGrpcExporter();
        e.setTimeoutMs(500);
        e.setMetricsIntervalMs(50L);
        e.setMaxQueueSize(2);
        e.start(config(), false);
        return e;
    }

    /** An exporter whose export thread waits a long time. */
    private static OtlpExporter idleHttp() {
        OtlpExporter e = new OtlpExporter();
        e.setTimeoutMs(2000);
        e.setFlushIntervalMs(600000L);
        e.start(idleConfig(), false);
        return e;
    }

    private static OtlpGrpcExporter idleGrpc() {
        OtlpGrpcExporter e = new OtlpGrpcExporter();
        e.setTimeoutMs(2000);
        e.setFlushIntervalMs(600000L);
        e.start(idleConfig(), false);
        return e;
    }

    /** A configuration under which the export thread waits a long time. */
    private static TelemetryConfig idleConfig() {
        TelemetryConfig config = new TelemetryConfig();
        config.setServiceName("svc");
        return config;
    }

    /**
     * Shutdown has to reach an export thread that is waiting for work. It
     * is woken through its monitor, not interrupted: an interrupt arriving
     * while it is exporting would abort whatever wait the export is in.
     */
    @Test
    public void testHttpExportThreadIsWokenToShutDown() throws Exception {
        OtlpExporter e = idleHttp();
        Thread thread = e.exportThreadForTesting();
        thread.start();
        e.flush();
        e.shutdown();
        thread.join(2000L);
        assertFalse(thread.isAlive());
    }

    @Test
    public void testGrpcExportThreadIsWokenToShutDown() throws Exception {
        OtlpGrpcExporter e = idleGrpc();
        Thread thread = e.exportThreadForTesting();
        thread.start();
        e.flush();
        e.shutdown();
        thread.join(2000L);
        assertFalse(thread.isAlive());
    }

    @Test
    public void testHttpResponseHandler() {
        OtlpExporter e = http();
        try {
            OtlpResponseHandler h = new OtlpResponseHandler("traces", e);
            assertFalse(h.isComplete());
            assertFalse(h.isSuccess());
            assertNull(h.getStatus());
            h.status(HttpStatus.OK.code);
            assertTrue(h.isSuccess());
            assertEquals(HttpStatus.OK, h.getStatus());
            HttpStatus[] errs = new HttpStatus[] {
                HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.GATEWAY_TIMEOUT, HttpStatus.BAD_REQUEST};
            for (int i = 0; i < errs.length; i++) {
                h.status(errs[i].code);
                assertFalse(h.isSuccess());
                assertEquals(errs[i], h.getStatus());
            }
            h.endMessage();
            assertTrue(h.isComplete());
            OtlpResponseHandler h2 = new OtlpResponseHandler("logs", e);
            h2.failed(new RuntimeException("x"));
            assertTrue(h2.isComplete());
            assertTrue(h2.toString().contains("logs"));
        } finally {
            e.shutdown();
        }
    }

    @Test
    public void testGrpcResponseHandler() {
        OtlpGrpcExporter e = grpc();
        try {
            OtlpGrpcResponseHandler h = new OtlpGrpcResponseHandler("traces", e);
            assertFalse(h.isComplete());
            assertNull(h.getStatus());
            h.status(HttpStatus.OK.code);
            assertTrue(h.isSuccess());
            h.status(HttpStatus.SERVICE_UNAVAILABLE.code);
            assertFalse(h.isSuccess());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, h.getStatus());
            h.endMessage();
            assertTrue(h.isComplete());
            OtlpGrpcResponseHandler h2 = new OtlpGrpcResponseHandler("logs", e);
            h2.failed(new RuntimeException("x"));
            assertTrue(h2.isComplete());
            assertTrue(h2.toString().contains("logs"));
        } finally {
            e.shutdown();
        }
    }
}
