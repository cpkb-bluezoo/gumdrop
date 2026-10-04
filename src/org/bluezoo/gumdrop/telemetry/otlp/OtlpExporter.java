/*
 * OtlpExporter.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
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
public class OtlpExporter implements TelemetryExporter {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N",
                org.bluezoo.gumdrop.telemetry.Trace.class.getModule());
    private static final Logger logger = Logger.getLogger(OtlpExporter.class.getName());

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
    private final OtlpEndpoint tracesEndpoint;
    private final OtlpEndpoint logsEndpoint;
    private final OtlpEndpoint metricsEndpoint;

    // Active exports for flush synchronization
    private final Set<OtlpResponseHandler> pendingExports;
    private final Object exportLock = new Object();

    // Background thread
    private final ExportThread exportThread;

    /** The export thread, for tests that step it by hand. */
    ExportThread exportThreadForTesting() {
        return exportThread;
    }

    private volatile boolean running;

    // Standalone runtime for this exporter's outbound HTTP client
    // connections -- deliberately independent of any application Gumdrop
    // runtime, since export is a best-effort background concern with its
    // own lifecycle (started here, stopped in shutdown()), not tied to
    // the application's own listeners/servers.
    private final Gumdrop gumdrop;

    /**
     * Creates an OTLP exporter with the given configuration.
     *
     * @param config the telemetry configuration
     */
    public OtlpExporter(TelemetryConfig config) {
        this(config, true);
    }

    /**
     * Package-private constructor; with {@code active} false no runtime is
     * booted and no export thread is started, so tests can exercise the
     * bookkeeping (response handlers, pending exports) without threads.
     */
    OtlpExporter(TelemetryConfig config, boolean active) {
        this.config = config;
        this.gumdrop = active ? Gumdrop.boot(GumdropConfig.create().workerThreads(1)) : null;

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
        this.tracesEndpoint = createEndpoint(gumdrop, "traces", config.getTracesEndpoint(), "/v1/traces", headers);
        this.logsEndpoint = createEndpoint(gumdrop, "logs", config.getLogsEndpoint(), "/v1/logs", headers);
        this.metricsEndpoint = createEndpoint(gumdrop, "metrics", config.getMetricsEndpoint(), "/v1/metrics", headers);

        // Track pending exports
        this.pendingExports = ConcurrentHashMap.newKeySet();

        // Start export thread
        this.running = true;
        this.exportThread = new ExportThread();
        if (active) {
            this.exportThread.start();
        }

        String endpoints = (tracesEndpoint != null ? ", traces: " + tracesEndpoint : "") +
                (logsEndpoint != null ? ", logs: " + logsEndpoint : "") +
                (metricsEndpoint != null ? ", metrics: " + metricsEndpoint : "");
        logger.info(MessageFormat.format(L10N.getString("info.exporter_started"), endpoints));
    }

    /**
     * Creates one outbound endpoint. Package-private so that tests can
     * substitute an in-memory endpoint; called from the constructor.
     */
    OtlpEndpoint createEndpoint(Gumdrop runtime, String endpointName, String url,
                                String defaultPath, Map<String, String> headers) {
        return OtlpEndpoint.create(runtime, endpointName, url, defaultPath, headers, config);
    }

    @Override
    public void export(Trace trace) {
        if (!running || trace == null) {
            return;
        }
        if (traceQueue.offer(trace)) {
            exportThread.wake();
        } else {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(MessageFormat.format(L10N.getString("fine.trace_queue_full_dropping"), trace.getTraceIdHex()));
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
                logger.fine(L10N.getString("fine.log_queue_full_dropping"));
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
                logger.fine(L10N.getString("fine.metric_queue_full_dropping"));
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
        exportThread.wake();

        try {
            exportThread.join(config.getTimeoutMs());
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

        if (gumdrop != null) {
            gumdrop.shutdown();
            try {
                gumdrop.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info(L10N.getString("info.exporter_shutdown"));
    }

    /**
     * Forces an immediate flush of all pending telemetry data.
     * This method blocks until the flush completes or times out.
     */
    @Override
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
    void onExportComplete(OtlpResponseHandler handler) {
        removePendingExport(handler);
    }

    private void removePendingExport(OtlpResponseHandler handler) {
        synchronized (exportLock) {
            pendingExports.remove(handler);
            if (pendingExports.isEmpty()) {
                exportLock.notifyAll();
            }
        }
    }

    /**
     * Waits for all pending exports to complete.
     */
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

    // ─────────────────────────────────────────────────────────────────────────
    // Export Thread
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Background thread that batches and exports telemetry data.
     * Also handles periodic metrics collection.
     */
    final class ExportThread extends Thread {

        private volatile boolean flushRequested;

        ExportThread() {
            super("OtlpExporter");
            setDaemon(true);
        }

        // The thread waits on this for a trace to arrive or for a flush
        // or shutdown to be requested. It is not woken by interrupting
        // it: an interrupt that arrives while it is exporting would abort
        // whatever wait the export is in, and lose the export.
        private final Object wakeLock = new Object();

        void requestFlush() {
            synchronized (wakeLock) {
                flushRequested = true;
                wakeLock.notifyAll();
            }
        }

        /** Wakes the thread: a trace has been queued, or it is to stop. */
        void wake() {
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        }

        /**
         * Waits until a trace is queued, a flush or shutdown is requested,
         * or the time is up.
         */
        private void awaitWork(long waitTime) throws InterruptedException {
            synchronized (wakeLock) {
                if (running && !flushRequested && traceQueue.isEmpty()) {
                    wakeLock.wait(waitTime);
                }
            }
        }

        private final List<Trace> traceBatch = new ArrayList<>();
        private final List<LogRecord> logBatch = new ArrayList<>();
        private final List<List<MetricData>> metricBatches = new ArrayList<>();
        private long lastFlush = System.currentTimeMillis();
        private long lastMetricsCollection = lastFlush;

        @Override
        public void run() {
            while (running || !traceQueue.isEmpty() || !logQueue.isEmpty() || !metricQueue.isEmpty()) {
                try {
                    pass(true);
                } catch (InterruptedException e) {
                    // Continue to check for shutdown or flush
                }
            }
            finalFlush();
        }

        /**
         * One iteration of the export loop. Package-private so tests can
         * step the loop by hand; {@code mayBlock} false skips the wait for
         * the next trace.
         */
        void pass(boolean mayBlock) throws InterruptedException {
            long now = System.currentTimeMillis();
            long flushWait = config.getFlushIntervalMs() - (now - lastFlush);
            long metricsWait = config.isMetricsEnabled()
                    ? config.getMetricsIntervalMs() - (now - lastMetricsCollection)
                    : flushWait;
            long waitTime = Math.min(flushWait, metricsWait);

            if (mayBlock && waitTime > 0) {
                awaitWork(waitTime);
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

            // Consume the request before the pass starts: a request
            // made while this pass runs must survive it (clearing
            // the flag at the end of the pass lost such requests).
            boolean requested = flushRequested;
            if (requested) {
                flushRequested = false;
            }
            boolean shouldFlush = requested ||
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
                lastFlush = System.currentTimeMillis();
            }
        }

        /** The flush performed once the loop has ended. */
        void finalFlush() {
            // Final flush including metrics
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
                OtlpResponseHandler handler = new OtlpResponseHandler("traces", OtlpExporter.this);
                pendingExports.add(handler);

                HttpRequestChannel channel = tracesEndpoint.openStream(handler);
                if (channel == null) {
                    continue;
                }

                try {
                    traceSerializer.serialize(trace, channel);
                    channel.close();
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

            OtlpResponseHandler handler = new OtlpResponseHandler("logs", OtlpExporter.this);
            pendingExports.add(handler);

            HttpRequestChannel channel = logsEndpoint.openStream(handler);
            if (channel == null) {
                return;
            }

            try {
                logSerializer.serialize(records, channel);
                channel.close();
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
                OtlpResponseHandler handler = new OtlpResponseHandler("metrics", OtlpExporter.this);
                pendingExports.add(handler);

                HttpRequestChannel channel = metricsEndpoint.openStream(handler);
                if (channel == null) {
                    continue;
                }

                try {
                    metricSerializer.serialize(metrics, "gumdrop", Gumdrop.VERSION, channel);
                    channel.close();
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.serialize_metrics_failed"), e.getMessage()));
                    removePendingExport(handler);
                }
            }
        }
    }
}

