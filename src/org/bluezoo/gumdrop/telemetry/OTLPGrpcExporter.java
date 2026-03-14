/*
 * OTLPGrpcExporter.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.protobuf.LogSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.MetricSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.TraceSerializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exports telemetry data to an OpenTelemetry Collector via OTLP/gRPC.
 *
 * <p>Uses gRPC over HTTP/2 with the same protobuf encoding as OTLP/HTTP.
 * The payload (TracesData, LogsData, MetricsData) is identical; only the
 * transport differs (gRPC framing, Content-Type: application/grpc, service paths).
 *
 * <p>Default OTLP gRPC port is 4317. Configure with protocol="grpc" in TelemetryConfig.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OTLPGrpcExporter implements TelemetryExporter {

    private static final String TRACE_SERVICE_PATH =
            "/opentelemetry.proto.collector.trace.v1.TraceService/Export";
    private static final String LOGS_SERVICE_PATH =
            "/opentelemetry.proto.collector.logs.v1.LogsService/Export";
    private static final String METRICS_SERVICE_PATH =
            "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export";

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    private static final Logger logger = Logger.getLogger(OTLPGrpcExporter.class.getName());

    private final TelemetryConfig config;
    private final TraceSerializer traceSerializer;
    private final LogSerializer logSerializer;
    private final MetricSerializer metricSerializer;

    private final BlockingQueue<Trace> traceQueue;
    private final BlockingQueue<LogRecord> logQueue;
    private final BlockingQueue<List<MetricData>> metricQueue;

    private final OTLPGrpcEndpoint tracesEndpoint;
    private final OTLPGrpcEndpoint logsEndpoint;
    private final OTLPGrpcEndpoint metricsEndpoint;

    private final Set<OTLPGrpcResponseHandler> pendingExports;
    private final Object exportLock = new Object();

    private final ExportThread exportThread;
    private volatile boolean running;

    /**
     * Creates an OTLP gRPC exporter with the given configuration.
     *
     * @param config the telemetry configuration
     */
    public OTLPGrpcExporter(TelemetryConfig config) {
        this.config = config;

        Map<String, String> resourceAttrs = config.getResourceAttributes();
        if (config.getServiceInstanceId() != null) {
            resourceAttrs.put("service.instance.id", config.getServiceInstanceId());
        }
        if (config.getDeploymentEnvironment() != null) {
            resourceAttrs.put("deployment.environment", config.getDeploymentEnvironment());
        }

        this.traceSerializer = new TraceSerializer(
                config.getServiceName(),
                config.getServiceVersion(),
                config.getServiceNamespace(),
                resourceAttrs);

        this.logSerializer = new LogSerializer(
                config.getServiceName(),
                config.getServiceVersion(),
                config.getServiceNamespace(),
                resourceAttrs);

        this.metricSerializer = new MetricSerializer(
                config.getServiceName(),
                config.getServiceVersion(),
                config.getServiceNamespace(),
                resourceAttrs);

        this.traceQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());
        this.logQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());
        this.metricQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());

        Map<String, String> headers = config.getParsedHeaders();
        this.tracesEndpoint = OTLPGrpcEndpoint.create("traces", config.getTracesEndpoint(),
                TRACE_SERVICE_PATH, headers, config);
        this.logsEndpoint = OTLPGrpcEndpoint.create("logs", config.getLogsEndpoint(),
                LOGS_SERVICE_PATH, headers, config);
        this.metricsEndpoint = OTLPGrpcEndpoint.create("metrics", config.getMetricsEndpoint(),
                METRICS_SERVICE_PATH, headers, config);

        this.pendingExports = ConcurrentHashMap.newKeySet();

        this.running = true;
        this.exportThread = new ExportThread();
        this.exportThread.start();

        String endpoints = (tracesEndpoint != null ? ", traces: " + tracesEndpoint : "") +
                (logsEndpoint != null ? ", logs: " + logsEndpoint : "") +
                (metricsEndpoint != null ? ", metrics: " + metricsEndpoint : "");
        logger.info(MessageFormat.format(L10N.getString("info.exporter_started"), endpoints));
    }

    @Override
    public void export(Trace trace) {
        if (!running || trace == null) {
            return;
        }
        if (!traceQueue.offer(trace)) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Trace queue full, dropping trace: " + trace.getTraceIdHex());
            }
        }
    }

    @Override
    public void export(LogRecord record) {
        if (!running || record == null) {
            return;
        }
        if (!logQueue.offer(record)) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Log queue full, dropping log record");
            }
        }
    }

    @Override
    public void export(List<MetricData> metrics) {
        if (!running || metrics == null || metrics.isEmpty()) {
            return;
        }
        if (!metricQueue.offer(metrics)) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Metric queue full, dropping metrics batch");
            }
        }
    }

    @Override
    public void flush() {
        exportThread.requestFlush();
        waitForPendingExports(config.getTimeoutMs());
    }

    @Override
    public void shutdown() {
        forceFlush();

        running = false;
        exportThread.interrupt();

        try {
            exportThread.join(config.getTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (tracesEndpoint != null) {
            tracesEndpoint.close();
        }
        if (logsEndpoint != null) {
            logsEndpoint.close();
        }
        if (metricsEndpoint != null) {
            metricsEndpoint.close();
        }

        logger.info(L10N.getString("info.exporter_shutdown"));
    }

    /**
     * Forces an immediate flush of all pending telemetry data.
     */
    public void forceFlush() {
        if (!running) {
            return;
        }
        exportThread.requestFlush();
        waitForPendingExports(config.getTimeoutMs());
    }

    /**
     * Waits for all configured endpoints to establish connections.
     *
     * @param timeoutMs the maximum time to wait in milliseconds
     * @return true if all endpoints are connected
     */
    public boolean waitForConnections(long timeoutMs) {
        boolean allConnected = true;
        if (tracesEndpoint != null && !tracesEndpoint.connectAndWait(timeoutMs)) {
            allConnected = false;
        }
        if (logsEndpoint != null && !logsEndpoint.connectAndWait(timeoutMs)) {
            allConnected = false;
        }
        if (metricsEndpoint != null && !metricsEndpoint.connectAndWait(timeoutMs)) {
            allConnected = false;
        }
        return allConnected;
    }

    void onExportComplete(OTLPGrpcResponseHandler handler) {
        removePendingExport(handler);
    }

    private void removePendingExport(OTLPGrpcResponseHandler handler) {
        synchronized (exportLock) {
            pendingExports.remove(handler);
            if (pendingExports.isEmpty()) {
                exportLock.notifyAll();
            }
        }
    }

    private void waitForPendingExports(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (exportLock) {
            while (!pendingExports.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    exportLock.wait(Math.min(remaining, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private class ExportThread extends Thread {

        private volatile boolean flushRequested;

        ExportThread() {
            super("OTLPGrpcExporter");
            setDaemon(true);
        }

        void requestFlush() {
            flushRequested = true;
            interrupt();
        }

        @Override
        public void run() {
            List<Trace> traceBatch = new ArrayList<>();
            List<LogRecord> logBatch = new ArrayList<>();
            List<List<MetricData>> metricBatches = new ArrayList<>();
            long lastFlush = System.currentTimeMillis();
            long lastMetricsCollection = lastFlush;

            while (running || !traceQueue.isEmpty() || !logQueue.isEmpty() || !metricQueue.isEmpty()) {
                try {
                    long now = System.currentTimeMillis();
                    long flushWait = config.getFlushIntervalMs() - (now - lastFlush);
                    long metricsWait = config.isMetricsEnabled()
                            ? config.getMetricsIntervalMs() - (now - lastMetricsCollection)
                            : flushWait;
                    long waitTime = Math.min(flushWait, metricsWait);

                    if (waitTime > 0 && !flushRequested) {
                        Trace polled = traceQueue.poll(waitTime, TimeUnit.MILLISECONDS);
                        if (polled != null) {
                            traceBatch.add(polled);
                        }
                    }

                    drainQueue(traceQueue, traceBatch);
                    drainQueue(logQueue, logBatch);
                    drainQueue(metricQueue, metricBatches);

                    now = System.currentTimeMillis();

                    if (config.isMetricsEnabled()
                            && (now - lastMetricsCollection) >= config.getMetricsIntervalMs()) {
                        collectMetrics();
                        drainQueue(metricQueue, metricBatches);
                        lastMetricsCollection = now;
                    }

                    boolean shouldFlush = flushRequested ||
                            traceBatch.size() >= config.getBatchSize() ||
                            logBatch.size() >= config.getBatchSize() ||
                            !metricBatches.isEmpty() ||
                            (now - lastFlush) >= config.getFlushIntervalMs();

                    if (shouldFlush) {
                        if (!traceBatch.isEmpty() && tracesEndpoint != null && tracesEndpoint.isConnected()) {
                            exportTraces(traceBatch);
                            traceBatch.clear();
                        }
                        if (!logBatch.isEmpty() && logsEndpoint != null && logsEndpoint.isConnected()) {
                            exportLogs(logBatch);
                            logBatch.clear();
                        }
                        if (!metricBatches.isEmpty() && metricsEndpoint != null && metricsEndpoint.isConnected()) {
                            exportMetrics(metricBatches);
                            metricBatches.clear();
                        }
                        flushRequested = false;
                        lastFlush = System.currentTimeMillis();
                    }

                } catch (InterruptedException e) {
                    // Continue
                }
            }

            if (config.isMetricsEnabled()) {
                collectMetrics();
            }
            drainQueue(traceQueue, traceBatch);
            drainQueue(logQueue, logBatch);
            drainQueue(metricQueue, metricBatches);

            if (!traceBatch.isEmpty()) {
                exportTraces(traceBatch);
            }
            if (!logBatch.isEmpty()) {
                exportLogs(logBatch);
            }
            if (!metricBatches.isEmpty()) {
                exportMetrics(metricBatches);
            }
        }

        private <T> void drainQueue(BlockingQueue<T> queue, List<T> batch) {
            T item;
            while ((item = queue.poll()) != null) {
                batch.add(item);
            }
        }

        private void collectMetrics() {
            Map<String, Meter> meters = config.getMeters();
            if (meters.isEmpty()) {
                return;
            }
            AggregationTemporality temporality = config.getMetricsTemporality();
            List<MetricData> allMetrics = new ArrayList<>();
            for (Meter meter : meters.values()) {
                allMetrics.addAll(meter.collect(temporality));
            }
            if (!allMetrics.isEmpty()) {
                export(allMetrics);
            }
        }

        private void exportTraces(List<Trace> traces) {
            if (tracesEndpoint == null) {
                return;
            }

            for (Trace trace : traces) {
                OTLPGrpcResponseHandler handler = new OTLPGrpcResponseHandler("traces", OTLPGrpcExporter.this);
                pendingExports.add(handler);

                try {
                    ByteBuffer data = traceSerializer.serialize(trace);
                    tracesEndpoint.send(data, handler);
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.serialize_trace_failed"),
                            trace.getTraceIdHex(), e.getMessage()));
                    removePendingExport(handler);
                }
            }
        }

        private void exportLogs(List<LogRecord> records) {
            if (logsEndpoint == null) {
                return;
            }

            OTLPGrpcResponseHandler handler = new OTLPGrpcResponseHandler("logs", OTLPGrpcExporter.this);
            pendingExports.add(handler);

            try {
                ByteBuffer data = logSerializer.serialize(records);
                logsEndpoint.send(data, handler);
            } catch (IOException e) {
                logger.warning(MessageFormat.format(L10N.getString("warn.serialize_logs_failed"), e.getMessage()));
                removePendingExport(handler);
            }
        }

        private void exportMetrics(List<List<MetricData>> batches) {
            if (metricsEndpoint == null) {
                return;
            }

            for (List<MetricData> metrics : batches) {
                OTLPGrpcResponseHandler handler = new OTLPGrpcResponseHandler("metrics", OTLPGrpcExporter.this);
                pendingExports.add(handler);

                try {
                    ByteBuffer data = metricSerializer.serialize(metrics, "gumdrop", Gumdrop.VERSION);
                    metricsEndpoint.send(data, handler);
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.serialize_metrics_failed"), e.getMessage()));
                    removePendingExport(handler);
                }
            }
        }
    }
}
