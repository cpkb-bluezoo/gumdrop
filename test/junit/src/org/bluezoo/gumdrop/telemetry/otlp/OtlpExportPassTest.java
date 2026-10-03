/*
 * OtlpExportPassTest.java
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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryTestData;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Steps the export loops of {@link OtlpExporter} and
 * {@link OtlpGrpcExporter} by hand ({@code pass}/{@code finalFlush}) against
 * in-memory endpoint stand-ins: batching, flushing, disconnected endpoints,
 * failed channels, queue overflow, metrics collection and the final flush.
 * No runtime is booted and no thread is started, so every outcome is
 * determined by the order of the calls below.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpExportPassTest {

    /** Request stub that completes the response handler when the body ends. */
    private static final class StubRequest implements HttpRequest {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final OtlpResponseHandler handler;
        boolean ended;

        StubRequest(OtlpResponseHandler handler) {
            this.handler = handler;
        }

        @Override
        public void header(String name, String value) {
        }

        @Override
        public void longHeader(String name, long value) {
        }

        @Override
        public void dateHeader(String name, Instant value) {
        }

        @Override
        public void contentType(ContentType contentType) {
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
        }

        @Override
        public void endHeaders() {
        }

        @Override
        public void priority(int weight) {
        }

        @Override
        public void dependency(HttpRequest parent) {
        }

        @Override
        public void exclusive(boolean exclusive) {
        }

        @Override
        public int bodyContent(ByteBuffer data) {
            int n = data.remaining();
            while (data.hasRemaining()) {
                body.write(data.get());
            }
            return n;
        }

        @Override
        public void endMessage() {
            ended = true;
            handler.endMessage();
        }

        @Override
        public void cancel() {
        }
    }

    /** Channel whose writes always fail, to drive the serialisation error arm. */
    private static final class FailingChannel extends HttpRequestChannel {
        FailingChannel() {
            super(null);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            throw new IOException("write refused");
        }

        @Override
        public void close() {
        }
    }

    private static final int MODE_OK = 0;
    private static final int MODE_NO_CHANNEL = 1;
    private static final int MODE_FAILING_CHANNEL = 2;

    private static final class StubEndpoint extends OtlpEndpoint {
        boolean connected;
        int mode = MODE_OK;
        boolean closed;
        int opened;
        Runnable onConnectionCheck;
        final List<StubRequest> requests = new ArrayList<StubRequest>();

        StubEndpoint(String name, boolean connected) {
            super(null, name, "stub", 1, "/p", false, null);
            this.connected = connected;
        }

        @Override
        boolean isConnected() {
            Runnable hook = onConnectionCheck;
            if (hook != null) {
                onConnectionCheck = null;
                hook.run();
            }
            return connected;
        }

        @Override
        boolean connectAndWait(long timeoutMs) {
            return connected;
        }

        @Override
        HttpRequestChannel openStream(OtlpResponseHandler handler) {
            opened++;
            if (mode == MODE_NO_CHANNEL) {
                handler.failed(new IOException("no connection"));
                return null;
            }
            if (mode == MODE_FAILING_CHANNEL) {
                return new FailingChannel();
            }
            StubRequest request = new StubRequest(handler);
            requests.add(request);
            return new HttpRequestChannel(request);
        }

        @Override
        void close() {
            closed = true;
        }
    }

    private static final class StubGrpcEndpoint extends OtlpGrpcEndpoint {
        boolean connected;
        boolean closed;
        boolean failSends;
        Runnable onConnectionCheck;
        final List<byte[]> payloads = new ArrayList<byte[]>();

        StubGrpcEndpoint(String name, boolean connected) {
            super(null, name, "stub", 1, "/p", false, null);
            this.connected = connected;
        }

        @Override
        boolean isConnected() {
            Runnable hook = onConnectionCheck;
            if (hook != null) {
                onConnectionCheck = null;
                hook.run();
            }
            return connected;
        }

        @Override
        boolean connectAndWait(long timeoutMs) {
            return connected;
        }

        @Override
        void send(ByteBuffer data, OtlpGrpcResponseHandler handler) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            payloads.add(copy);
            if (failSends) {
                handler.failed(new IOException("no connection"));
            } else {
                handler.endMessage();
            }
        }

        @Override
        void close() {
            closed = true;
        }
    }

    private static final Map<String, StubEndpoint> HTTP_STUBS = new HashMap<String, StubEndpoint>();
    private static final Map<String, StubGrpcEndpoint> GRPC_STUBS = new HashMap<String, StubGrpcEndpoint>();

    private static final class TestExporter extends OtlpExporter {
        TestExporter(TelemetryConfig config) {
            super(config, false);
        }

        @Override
        OtlpEndpoint createEndpoint(Gumdrop runtime, String endpointName, String url,
                                    String defaultPath, Map<String, String> headers) {
            return HTTP_STUBS.get(endpointName);
        }
    }

    private static final class TestGrpcExporter extends OtlpGrpcExporter {
        TestGrpcExporter(TelemetryConfig config) {
            super(config, false);
        }

        @Override
        OtlpGrpcEndpoint createEndpoint(Gumdrop runtime, String endpointName, String url,
                                        String grpcPath, Map<String, String> headers) {
            return GRPC_STUBS.get(endpointName);
        }
    }

    private static final class Capture extends Handler {
        final List<String> messages = new ArrayList<String>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getLevel() + ":" + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private Logger exporterLogger;
    private Logger grpcLogger;
    private Level exporterLevel;
    private Level grpcLevel;
    private final Capture capture = new Capture();

    @Before
    public void setUp() {
        HTTP_STUBS.clear();
        GRPC_STUBS.clear();
        exporterLogger = Logger.getLogger(OtlpExporter.class.getName());
        grpcLogger = Logger.getLogger(OtlpGrpcExporter.class.getName());
        exporterLevel = exporterLogger.getLevel();
        grpcLevel = grpcLogger.getLevel();
        exporterLogger.setLevel(Level.FINE);
        grpcLogger.setLevel(Level.FINE);
        capture.setLevel(Level.ALL);
        exporterLogger.addHandler(capture);
        grpcLogger.addHandler(capture);
    }

    @After
    public void tearDown() {
        exporterLogger.removeHandler(capture);
        grpcLogger.removeHandler(capture);
        exporterLogger.setLevel(exporterLevel);
        grpcLogger.setLevel(grpcLevel);
        HTTP_STUBS.clear();
        GRPC_STUBS.clear();
    }

    private static TelemetryConfig config() {
        TelemetryConfig config = new TelemetryConfig();
        config.setServiceName("svc");
        config.setServiceInstanceId("inst");
        config.setDeploymentEnvironment("test");
        config.setTimeoutMs(2000);
        config.setFlushIntervalMs(3600000L);
        config.setBatchSize(1000);
        config.setMaxQueueSize(10);
        config.setMetricsEnabled(false);
        return config;
    }

    private boolean logged(String fragment) {
        for (String m : capture.messages) {
            if (m.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void step(OtlpExporter exporter) throws InterruptedException {
        exporter.exportThreadForTesting().pass(false);
    }

    private static void step(OtlpGrpcExporter exporter) throws InterruptedException {
        exporter.exportThreadForTesting().pass(false);
    }

    // ---- HTTP exporter ----

    @Test
    public void httpFlushDeliversTracesLogsAndMetrics() throws Exception {
        StubEndpoint traces = new StubEndpoint("traces", true);
        StubEndpoint logs = new StubEndpoint("logs", true);
        StubEndpoint metrics = new StubEndpoint("metrics", true);
        HTTP_STUBS.put("traces", traces);
        HTTP_STUBS.put("logs", logs);
        HTTP_STUBS.put("metrics", metrics);
        TestExporter exporter = new TestExporter(config());
        assertTrue(exporter.waitForConnections(5L));
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.richTrace());
        for (org.bluezoo.gumdrop.telemetry.LogRecord r : TelemetryTestData.logRecords()) {
            exporter.export(r);
        }
        exporter.export(TelemetryTestData.metrics());
        exporter.flush();
        step(exporter);
        assertEquals(2, traces.requests.size());
        assertTrue(traces.requests.get(0).body.size() > 0);
        assertTrue(logs.requests.get(0).ended);
        assertTrue(metrics.requests.get(0).body.size() > 0);
        exporter.shutdown();
        assertTrue(traces.closed);
        assertTrue(logs.closed);
        assertTrue(metrics.closed);
    }

    @Test
    public void httpBatchSizeTriggersFlushWithoutExplicitRequest() throws Exception {
        StubEndpoint traces = new StubEndpoint("traces", true);
        HTTP_STUBS.put("traces", traces);
        TelemetryConfig config = config();
        config.setBatchSize(1);
        TestExporter exporter = new TestExporter(config);
        exporter.export(TelemetryTestData.richTrace());
        step(exporter);
        assertEquals(1, traces.requests.size());
        exporter.shutdown();
    }

    @Test
    public void httpWithoutFlushConditionNothingIsExported() throws Exception {
        StubEndpoint traces = new StubEndpoint("traces", true);
        HTTP_STUBS.put("traces", traces);
        TestExporter exporter = new TestExporter(config());
        exporter.export(TelemetryTestData.richTrace());
        step(exporter);
        assertEquals(0, traces.requests.size());
        exporter.shutdown();
    }

    @Test
    public void httpDisconnectedEndpointKeepsBatchUntilConnected() throws Exception {
        StubEndpoint traces = new StubEndpoint("traces", false);
        StubEndpoint logs = new StubEndpoint("logs", false);
        HTTP_STUBS.put("traces", traces);
        HTTP_STUBS.put("logs", logs);
        TestExporter exporter = new TestExporter(config());
        assertFalse(exporter.waitForConnections(5L));
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.logRecords().get(0));
        exporter.flush();
        step(exporter);
        assertEquals(0, traces.requests.size());
        traces.connected = true;
        logs.connected = true;
        exporter.flush();
        step(exporter);
        assertEquals(1, traces.requests.size());
        assertEquals(1, logs.requests.size());
        exporter.shutdown();
    }

    @Test
    public void httpNoChannelAndFailingChannelAreContained() throws Exception {
        StubEndpoint traces = new StubEndpoint("traces", true);
        StubEndpoint logs = new StubEndpoint("logs", true);
        StubEndpoint metrics = new StubEndpoint("metrics", true);
        traces.mode = MODE_NO_CHANNEL;
        logs.mode = MODE_FAILING_CHANNEL;
        metrics.mode = MODE_FAILING_CHANNEL;
        HTTP_STUBS.put("traces", traces);
        HTTP_STUBS.put("logs", logs);
        HTTP_STUBS.put("metrics", metrics);
        TestExporter exporter = new TestExporter(config());
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.logRecords().get(0));
        exporter.export(TelemetryTestData.metrics());
        exporter.flush();
        step(exporter);
        assertEquals(1, traces.opened);
        assertEquals(1, logs.opened);
        assertEquals(1, metrics.opened);
        assertTrue(logged("WARNING"));
        exporter.shutdown();
    }

    @Test
    public void httpFinalFlushExportsEvenWhenNotConnected() throws Exception {
        StubEndpoint traces = new StubEndpoint("traces", false);
        StubEndpoint logs = new StubEndpoint("logs", false);
        StubEndpoint metrics = new StubEndpoint("metrics", false);
        HTTP_STUBS.put("traces", traces);
        HTTP_STUBS.put("logs", logs);
        HTTP_STUBS.put("metrics", metrics);
        TestExporter exporter = new TestExporter(config());
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.logRecords().get(0));
        exporter.export(TelemetryTestData.metrics());
        exporter.shutdown();
        exporter.exportThreadForTesting().finalFlush();
        assertEquals(1, traces.requests.size());
        assertEquals(1, logs.requests.size());
        assertEquals(1, metrics.requests.size());
    }

    @Test
    public void httpFullQueuesDropAndRejectAfterShutdown() throws Exception {
        StubEndpoint logs = new StubEndpoint("logs", true);
        StubEndpoint metrics = new StubEndpoint("metrics", true);
        HTTP_STUBS.put("logs", logs);
        HTTP_STUBS.put("metrics", metrics);
        TelemetryConfig config = config();
        config.setMaxQueueSize(1);
        TestExporter exporter = new TestExporter(config);
        org.bluezoo.gumdrop.telemetry.LogRecord rec = TelemetryTestData.logRecords().get(0);
        exporter.export(rec);
        exporter.export(rec);
        exporter.export(TelemetryTestData.metrics());
        exporter.export(TelemetryTestData.metrics());
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.richTrace());
        assertTrue(logged("FINE"));
        exporter.shutdown();
        exporter.export(rec);
        exporter.export(TelemetryTestData.metrics());
        exporter.export(TelemetryTestData.richTrace());
        exporter.exportThreadForTesting().finalFlush();
        assertEquals("only the one queued log record, none after shutdown", 1, logs.requests.size());
        assertEquals(1, metrics.requests.size());
    }

    @Test
    public void httpMetricsCollectionExportsMeters() throws Exception {
        StubEndpoint metrics = new StubEndpoint("metrics", true);
        HTTP_STUBS.put("metrics", metrics);
        TelemetryConfig config = config();
        config.setMetricsEnabled(true);
        config.setMetricsIntervalMs(0L);
        TestExporter exporter = new TestExporter(config);
        step(exporter);
        assertEquals("no meters yet", 0, metrics.requests.size());
        Meter meter = config.getMeter("scope");
        LongCounter counter = meter.counterBuilder("c").build();
        counter.add(1L);
        step(exporter);
        assertEquals(1, metrics.requests.size());
        assertTrue(metrics.requests.get(0).body.size() > 0);
        exporter.shutdown();
        exporter.exportThreadForTesting().finalFlush();
    }

    @Test
    public void httpFlushRequestedDuringAFlushPassIsNotLost() throws Exception {
        StubEndpoint traces = new StubEndpoint("traces", true);
        HTTP_STUBS.put("traces", traces);
        final TestExporter exporter = new TestExporter(config());
        traces.onConnectionCheck = new Runnable() {
            @Override
            public void run() {
                exporter.flush();
            }
        };
        exporter.export(TelemetryTestData.richTrace());
        exporter.flush();
        step(exporter);
        assertEquals(1, traces.requests.size());
        exporter.export(TelemetryTestData.richTrace());
        step(exporter);
        assertEquals("the request made mid-pass is still pending", 2, traces.requests.size());
        exporter.shutdown();
    }

    // ---- gRPC exporter ----

    @Test
    public void grpcFlushDeliversTracesLogsAndMetrics() throws Exception {
        StubGrpcEndpoint traces = new StubGrpcEndpoint("traces", true);
        StubGrpcEndpoint logs = new StubGrpcEndpoint("logs", true);
        StubGrpcEndpoint metrics = new StubGrpcEndpoint("metrics", true);
        GRPC_STUBS.put("traces", traces);
        GRPC_STUBS.put("logs", logs);
        GRPC_STUBS.put("metrics", metrics);
        TestGrpcExporter exporter = new TestGrpcExporter(config());
        assertTrue(exporter.waitForConnections(5L));
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.richTrace());
        for (org.bluezoo.gumdrop.telemetry.LogRecord r : TelemetryTestData.logRecords()) {
            exporter.export(r);
        }
        exporter.export(TelemetryTestData.metrics());
        exporter.flush();
        step(exporter);
        assertEquals(2, traces.payloads.size());
        assertTrue(traces.payloads.get(0).length > 0);
        assertEquals(1, logs.payloads.size());
        assertEquals(1, metrics.payloads.size());
        exporter.shutdown();
        assertTrue(traces.closed);
        assertTrue(logs.closed);
        assertTrue(metrics.closed);
    }

    @Test
    public void grpcBatchSizeDisconnectedAndFailedSends() throws Exception {
        StubGrpcEndpoint traces = new StubGrpcEndpoint("traces", false);
        StubGrpcEndpoint logs = new StubGrpcEndpoint("logs", true);
        logs.failSends = true;
        GRPC_STUBS.put("traces", traces);
        GRPC_STUBS.put("logs", logs);
        TelemetryConfig config = config();
        config.setBatchSize(1);
        TestGrpcExporter exporter = new TestGrpcExporter(config);
        assertFalse(exporter.waitForConnections(5L));
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.logRecords().get(0));
        step(exporter);
        assertEquals(1, logs.payloads.size());
        assertEquals(0, traces.payloads.size());
        traces.connected = true;
        exporter.flush();
        step(exporter);
        assertEquals(1, traces.payloads.size());
        exporter.shutdown();
    }

    @Test
    public void grpcFinalFlushAndQueueOverflow() throws Exception {
        StubGrpcEndpoint logs = new StubGrpcEndpoint("logs", false);
        StubGrpcEndpoint metrics = new StubGrpcEndpoint("metrics", false);
        GRPC_STUBS.put("logs", logs);
        GRPC_STUBS.put("metrics", metrics);
        TelemetryConfig config = config();
        config.setMaxQueueSize(1);
        TestGrpcExporter exporter = new TestGrpcExporter(config);
        org.bluezoo.gumdrop.telemetry.LogRecord rec = TelemetryTestData.logRecords().get(0);
        exporter.export(rec);
        exporter.export(rec);
        exporter.export(TelemetryTestData.metrics());
        exporter.export(TelemetryTestData.metrics());
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.richTrace());
        assertTrue(logged("FINE"));
        exporter.shutdown();
        exporter.export(rec);
        exporter.export(TelemetryTestData.metrics());
        exporter.export(TelemetryTestData.richTrace());
        exporter.exportThreadForTesting().finalFlush();
        assertEquals("only the one queued log record, none after shutdown", 1, logs.payloads.size());
        assertEquals(1, metrics.payloads.size());
    }

    @Test
    public void grpcMetricsCollectionExportsMeters() throws Exception {
        StubGrpcEndpoint metrics = new StubGrpcEndpoint("metrics", true);
        GRPC_STUBS.put("metrics", metrics);
        TelemetryConfig config = config();
        config.setMetricsEnabled(true);
        config.setMetricsIntervalMs(0L);
        TestGrpcExporter exporter = new TestGrpcExporter(config);
        step(exporter);
        assertEquals(0, metrics.payloads.size());
        Meter meter = config.getMeter("scope");
        LongCounter counter = meter.counterBuilder("c").build();
        counter.add(1L);
        step(exporter);
        assertEquals(1, metrics.payloads.size());
        exporter.shutdown();
        exporter.exportThreadForTesting().finalFlush();
    }

    @Test
    public void grpcFlushRequestedDuringAFlushPassIsNotLost() throws Exception {
        StubGrpcEndpoint traces = new StubGrpcEndpoint("traces", true);
        GRPC_STUBS.put("traces", traces);
        final TestGrpcExporter exporter = new TestGrpcExporter(config());
        traces.onConnectionCheck = new Runnable() {
            @Override
            public void run() {
                exporter.flush();
            }
        };
        exporter.export(TelemetryTestData.richTrace());
        exporter.flush();
        step(exporter);
        assertEquals(1, traces.payloads.size());
        exporter.export(TelemetryTestData.richTrace());
        step(exporter);
        assertEquals("the request made mid-pass is still pending", 2, traces.payloads.size());
        exporter.shutdown();
    }

    @Test
    public void httpWithoutAnyEndpointDiscardsQuietly() throws Exception {
        TestExporter exporter = new TestExporter(config());
        assertTrue(exporter.waitForConnections(5L));
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.logRecords().get(0));
        exporter.export(TelemetryTestData.metrics());
        exporter.flush();
        step(exporter);
        exporter.shutdown();
        exporter.exportThreadForTesting().finalFlush();
        exporter.onExportComplete(new OtlpResponseHandler("traces", exporter));
    }

    @Test
    public void grpcWithoutAnyEndpointDiscardsQuietly() throws Exception {
        TestGrpcExporter exporter = new TestGrpcExporter(config());
        assertTrue(exporter.waitForConnections(5L));
        exporter.export(TelemetryTestData.richTrace());
        exporter.export(TelemetryTestData.logRecords().get(0));
        exporter.export(TelemetryTestData.metrics());
        exporter.flush();
        step(exporter);
        exporter.shutdown();
        exporter.exportThreadForTesting().finalFlush();
        exporter.onExportComplete(new OtlpGrpcResponseHandler("traces", exporter));
    }
}
