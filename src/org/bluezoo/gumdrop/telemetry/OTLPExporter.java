/*
 * OTLPExporter.java
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

import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.protobuf.LogSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.MetricSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.TraceSerializer;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exports telemetry data to an OpenTelemetry Collector via OTLP/HTTP.
 *
 * <p>This exporter uses Gumdrop's native HTTP client for efficient,
 * non-blocking delivery of telemetry data. Data is batched before sending
 * to reduce network overhead. Batches are flushed either when full or when
 * the flush interval expires.
 *
 * <p>The exporter maintains separate endpoints for traces, logs, and metrics,
 * each with its own HTTP connection that is reused across exports.
 *
 * <h3>Configuration</h3>
 * <p>The exporter is configured via {@link TelemetryConfig}:
 * <ul>
 * <li>{@code tracesEndpoint} - URL for trace export (e.g., http://localhost:4318/v1/traces)</li>
 * <li>{@code logsEndpoint} - URL for log export</li>
 * <li>{@code metricsEndpoint} - URL for metrics export</li>
 * <li>{@code batchSize} - Maximum items per batch</li>
 * <li>{@code flushIntervalMs} - Maximum time between flushes</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OTLPExporter implements TelemetryExporter {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    private static final Logger logger = Logger.getLogger(OTLPExporter.class.getName());

    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024; // 1 MB

    private final TelemetryConfig config;
    private final TraceSerializer traceSerializer;
    private final LogSerializer logSerializer;
    private final MetricSerializer metricSerializer;

    // Queues for incoming telemetry data
    private final BlockingQueue<Trace> traceQueue;
    private final BlockingQueue<LogRecord> logQueue;
    private final BlockingQueue<List<MetricData>> metricQueue;

    // Endpoints
    private final OTLPEndpoint tracesEndpoint;
    private final OTLPEndpoint logsEndpoint;
    private final OTLPEndpoint metricsEndpoint;

    // Active exports for flush synchronization
    private final Set<OTLPResponseHandler> pendingExports;

    // Background threads
    private final ExportThread exportThread;
    private final MetricsCollectionThread metricsCollectionThread;
    private volatile boolean running;

    /**
     * Creates an OTLP exporter with the given configuration.
     *
     * @param config the telemetry configuration
     */
    public OTLPExporter(TelemetryConfig config) {
        this.config = config;

        // Build resource attributes
        Map<String, String> resourceAttrs = config.getResourceAttributes();
        if (config.getServiceInstanceId() != null) {
            resourceAttrs.put("service.instance.id", config.getServiceInstanceId());
        }
        if (config.getDeploymentEnvironment() != null) {
            resourceAttrs.put("deployment.environment", config.getDeploymentEnvironment());
        }

        // Create serializers
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

        // Create queues
        this.traceQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());
        this.logQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());
        this.metricQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());

        // Parse and create endpoints
        Map<String, String> headers = config.getParsedHeaders();
        this.tracesEndpoint = OTLPEndpoint.create("traces", config.getTracesEndpoint(), "/v1/traces", headers, config);
        this.logsEndpoint = OTLPEndpoint.create("logs", config.getLogsEndpoint(), "/v1/logs", headers, config);
        this.metricsEndpoint = OTLPEndpoint.create("metrics", config.getMetricsEndpoint(), "/v1/metrics", headers, config);

        // Track pending exports
        this.pendingExports = ConcurrentHashMap.newKeySet();

        // Start export thread
        this.running = true;
        this.exportThread = new ExportThread();
        this.exportThread.start();

        // Start metrics collection thread if metrics are enabled
        if (config.isMetricsEnabled() && metricsEndpoint != null) {
            this.metricsCollectionThread = new MetricsCollectionThread();
            this.metricsCollectionThread.start();
        } else {
            this.metricsCollectionThread = null;
        }

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
        // Final flush
        forceFlush();

        running = false;
        exportThread.interrupt();
        if (metricsCollectionThread != null) {
            metricsCollectionThread.interrupt();
        }

        try {
            exportThread.join(config.getTimeoutMs());
            if (metricsCollectionThread != null) {
                metricsCollectionThread.join(config.getTimeoutMs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close endpoints
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
     * This method blocks until the flush completes or times out.
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
     * <p>This method blocks until all endpoints are connected or the timeout
     * expires. Use this after starting the exporter to ensure connections
     * are ready before sending telemetry data.
     *
     * @param timeoutMs the maximum time to wait in milliseconds
     * @return true if all endpoints are connected, false if any timed out
     */
    public boolean waitForConnections(long timeoutMs) {
        boolean allConnected = true;
        if (tracesEndpoint != null) {
            if (!tracesEndpoint.connectAndWait(timeoutMs)) {
                allConnected = false;
            }
        }
        if (logsEndpoint != null) {
            if (!logsEndpoint.connectAndWait(timeoutMs)) {
                allConnected = false;
            }
        }
        if (metricsEndpoint != null) {
            if (!metricsEndpoint.connectAndWait(timeoutMs)) {
                allConnected = false;
            }
        }
        return allConnected;
    }

    /**
     * Called by response handlers when an export completes.
     *
     * @param handler the completed handler
     */
    void onExportComplete(OTLPResponseHandler handler) {
        pendingExports.remove(handler);
    }

    /**
     * Waits for all pending exports to complete.
     */
    private void waitForPendingExports(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!pendingExports.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export Thread
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Background thread that batches and exports telemetry data.
     */
    private class ExportThread extends Thread {

        private volatile boolean flushRequested;

        ExportThread() {
            super("OTLPExporter");
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

            while (running || !traceQueue.isEmpty() || !logQueue.isEmpty() || !metricQueue.isEmpty()) {
                try {
                    // Wait for data or flush interval
                    long now = System.currentTimeMillis();
                    long waitTime = config.getFlushIntervalMs() - (now - lastFlush);
                    if (waitTime > 0 && !flushRequested) {
                        Thread.sleep(Math.min(waitTime, 100));
                    }

                    // Drain queues into batches
                    drainQueue(traceQueue, traceBatch);
                    drainQueue(logQueue, logBatch);
                    drainQueue(metricQueue, metricBatches);

                    // Check if we should flush
                    now = System.currentTimeMillis();
                    boolean shouldFlush = flushRequested ||
                            traceBatch.size() >= config.getBatchSize() ||
                            logBatch.size() >= config.getBatchSize() ||
                            !metricBatches.isEmpty() ||
                            (now - lastFlush) >= config.getFlushIntervalMs();

                    if (shouldFlush) {
                        // Only export if the endpoint is connected, otherwise keep data for retry
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
                    // Continue to check for shutdown or flush
                }
            }

            // Final flush on shutdown
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

        private void exportTraces(List<Trace> traces) {
            if (tracesEndpoint == null) {
                return;
            }

            for (Trace trace : traces) {
                OTLPResponseHandler handler = new OTLPResponseHandler("traces", OTLPExporter.this);
                pendingExports.add(handler);

                HTTPRequestChannel channel = tracesEndpoint.openStream(handler);
                if (channel == null) {
                    continue;
                }

                try {
                    traceSerializer.serialize(trace, channel);
                    channel.close();
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.serialize_trace_failed"), 
                        trace.getTraceIdHex(), e.getMessage()));
                    pendingExports.remove(handler);
                }
            }
        }

        private void exportLogs(List<LogRecord> records) {
            if (logsEndpoint == null) {
                return;
            }

            OTLPResponseHandler handler = new OTLPResponseHandler("logs", OTLPExporter.this);
            pendingExports.add(handler);

            HTTPRequestChannel channel = logsEndpoint.openStream(handler);
            if (channel == null) {
                return;
            }

            try {
                logSerializer.serialize(records, channel);
                channel.close();
            } catch (IOException e) {
                logger.warning(MessageFormat.format(L10N.getString("warn.serialize_logs_failed"), e.getMessage()));
                pendingExports.remove(handler);
            }
        }

        private void exportMetrics(List<List<MetricData>> batches) {
            if (metricsEndpoint == null) {
                return;
            }

            for (List<MetricData> metrics : batches) {
                OTLPResponseHandler handler = new OTLPResponseHandler("metrics", OTLPExporter.this);
                pendingExports.add(handler);

                HTTPRequestChannel channel = metricsEndpoint.openStream(handler);
                if (channel == null) {
                    continue;
                }

                try {
                    metricSerializer.serialize(metrics, "gumdrop", "0.4", channel);
                    channel.close();
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.serialize_metrics_failed"), e.getMessage()));
                    pendingExports.remove(handler);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metrics Collection Thread
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Background thread that periodically collects metrics from registered meters.
     */
    private class MetricsCollectionThread extends Thread {

        MetricsCollectionThread() {
            super("OTLPExporter-Metrics");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(config.getMetricsIntervalMs());

                    if (!running) {
                        break;
                    }

                    collectAndExportMetrics();

                } catch (InterruptedException e) {
                    // Check for shutdown
                }
            }

            // Final collection on shutdown
            collectAndExportMetrics();
        }

        private void collectAndExportMetrics() {
            Map<String, Meter> meters = config.getMeters();
            if (meters.isEmpty()) {
                return;
            }

            AggregationTemporality temporality = config.getMetricsTemporality();
            List<MetricData> allMetrics = new ArrayList<>();

            for (Meter meter : meters.values()) {
                List<MetricData> meterMetrics = meter.collect(temporality);
                allMetrics.addAll(meterMetrics);
            }

            if (!allMetrics.isEmpty()) {
                export(allMetrics);
            }
        }
    }
}

