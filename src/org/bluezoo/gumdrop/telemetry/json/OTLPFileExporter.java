/*
 * OTLPFileExporter.java
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

package org.bluezoo.gumdrop.telemetry.json;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.util.BufferingByteChannel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exports telemetry data to OTLP JSON Lines files or stdout.
 *
 * <p>This exporter implements the
 * <a href="https://opentelemetry.io/docs/specs/otel/protocol/file-exporter/">
 * OpenTelemetry Protocol File Exporter</a> specification. Each line written
 * is a complete OTLP JSON object ({@code ExportTraceServiceRequest},
 * {@code ExportLogsServiceRequest}, or {@code ExportMetricsServiceRequest})
 * followed by a newline character.
 *
 * <p>When configured with file paths, separate {@code .jsonl} files are
 * maintained for each signal type (traces, logs, metrics) as required by the
 * specification. When writing to stdout (the default), all signals share the
 * same output stream.
 *
 * <p>Like the OTLP/HTTP exporter, data is queued and flushed by a background
 * thread to avoid blocking the caller.
 *
 * <h3>Configuration</h3>
 * <pre>
 * &lt;component id="telemetry" class="org.bluezoo.gumdrop.telemetry.TelemetryConfig"&gt;
 *     &lt;property name="service-name"&gt;my-service&lt;/property&gt;
 *     &lt;property name="exporter-type"&gt;file&lt;/property&gt;
 *     &lt;property name="file-traces-path"&gt;/var/log/otel/traces.jsonl&lt;/property&gt;
 *     &lt;property name="file-logs-path"&gt;/var/log/otel/logs.jsonl&lt;/property&gt;
 *     &lt;property name="file-metrics-path"&gt;/var/log/otel/metrics.jsonl&lt;/property&gt;
 *     &lt;property name="file-buffer-size"&gt;8192&lt;/property&gt;
 * &lt;/component&gt;
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OTLPFileExporter implements TelemetryExporter {

    private static final Logger logger = Logger.getLogger(OTLPFileExporter.class.getName());

    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

    private final TelemetryConfig config;
    private final TraceJsonSerializer traceSerializer;
    private final LogJsonSerializer logSerializer;
    private final MetricJsonSerializer metricSerializer;

    private final BufferingByteChannel tracesChannel;
    private final BufferingByteChannel logsChannel;
    private final BufferingByteChannel metricsChannel;

    private final BlockingQueue<Trace> traceQueue;
    private final BlockingQueue<LogRecord> logQueue;
    private final BlockingQueue<List<MetricData>> metricQueue;

    private final ExportThread exportThread;
    private volatile boolean running;

    /**
     * Creates a file exporter that writes all signals to stdout.
     *
     * @param config the telemetry configuration
     */
    public OTLPFileExporter(TelemetryConfig config) {
        this(config, null, null, null);
    }

    /**
     * Creates a file exporter that writes to the specified file paths.
     * Any null path causes that signal to be written to stdout.
     *
     * @param config the telemetry configuration
     * @param tracesPath path for traces JSONL file, or null for stdout
     * @param logsPath path for logs JSONL file, or null for stdout
     * @param metricsPath path for metrics JSONL file, or null for stdout
     */
    public OTLPFileExporter(TelemetryConfig config,
                            Path tracesPath, Path logsPath, Path metricsPath) {
        this.config = config;

        Map<String, String> resourceAttrs = config.getResourceAttributes();
        if (config.getServiceInstanceId() != null) {
            resourceAttrs.put("service.instance.id", config.getServiceInstanceId());
        }
        if (config.getDeploymentEnvironment() != null) {
            resourceAttrs.put("deployment.environment", config.getDeploymentEnvironment());
        }

        this.traceSerializer = new TraceJsonSerializer(
                config.getServiceName(),
                config.getServiceVersion(),
                config.getServiceNamespace(),
                resourceAttrs);

        this.logSerializer = new LogJsonSerializer(
                config.getServiceName(),
                config.getServiceVersion(),
                config.getServiceNamespace(),
                resourceAttrs);

        this.metricSerializer = new MetricJsonSerializer(
                config.getServiceName(),
                config.getServiceVersion(),
                config.getServiceNamespace(),
                resourceAttrs);

        int bufferSize = config.getFileBufferSize();
        this.tracesChannel = new BufferingByteChannel(openChannel(tracesPath), bufferSize);
        this.logsChannel = new BufferingByteChannel(openChannel(logsPath), bufferSize);
        this.metricsChannel = new BufferingByteChannel(openChannel(metricsPath), bufferSize);

        this.traceQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());
        this.logQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());
        this.metricQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());

        this.running = true;
        this.exportThread = new ExportThread();
        this.exportThread.start();

        logger.info("OTLP file exporter started");
    }

    private static WritableByteChannel openChannel(Path path) {
        if (path == null) {
            return Channels.newChannel(System.out);
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OutputStream out = Files.newOutputStream(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            return Channels.newChannel(out);
        } catch (IOException e) {
            logger.warning("Failed to open file " + path + ", falling back to stdout: " + e.getMessage());
            return Channels.newChannel(System.out);
        }
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
    }

    @Override
    public void shutdown() {
        running = false;
        exportThread.requestFlush();
        exportThread.interrupt();

        try {
            exportThread.join(config.getTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        closeChannel(tracesChannel);
        closeChannel(logsChannel);
        closeChannel(metricsChannel);

        logger.info("OTLP file exporter shut down");
    }

    private static void closeChannel(WritableByteChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // Ignore close errors
        }
    }

    private static void writeNewline(WritableByteChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(NEWLINE);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export Thread
    // ─────────────────────────────────────────────────────────────────────────

    private class ExportThread extends Thread {

        private volatile boolean flushRequested;

        ExportThread() {
            super("OTLPFileExporter");
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
                        if (!traceBatch.isEmpty()) {
                            exportTraces(traceBatch);
                            traceBatch.clear();
                        }
                        if (!logBatch.isEmpty()) {
                            exportLogs(logBatch);
                            logBatch.clear();
                        }
                        if (!metricBatches.isEmpty()) {
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
            synchronized (tracesChannel) {
                for (Trace trace : traces) {
                    try {
                        traceSerializer.serialize(trace, tracesChannel);
                        writeNewline(tracesChannel);
                    } catch (IOException e) {
                        logger.warning("Failed to write trace " + trace.getTraceIdHex() + ": " + e.getMessage());
                    }
                }
                try {
                    tracesChannel.flush();
                } catch (IOException e) {
                    logger.warning("Failed to flush traces: " + e.getMessage());
                }
            }
        }

        private void exportLogs(List<LogRecord> records) {
            synchronized (logsChannel) {
                try {
                    logSerializer.serialize(records, logsChannel);
                    writeNewline(logsChannel);
                } catch (IOException e) {
                    logger.warning("Failed to write logs: " + e.getMessage());
                }
                try {
                    logsChannel.flush();
                } catch (IOException e) {
                    logger.warning("Failed to flush logs: " + e.getMessage());
                }
            }
        }

        private void exportMetrics(List<List<MetricData>> batches) {
            synchronized (metricsChannel) {
                for (List<MetricData> metrics : batches) {
                    try {
                        metricSerializer.serialize(metrics, "gumdrop", Gumdrop.VERSION, metricsChannel);
                        writeNewline(metricsChannel);
                    } catch (IOException e) {
                        logger.warning("Failed to write metrics: " + e.getMessage());
                    }
                }
                try {
                    metricsChannel.flush();
                } catch (IOException e) {
                    logger.warning("Failed to flush metrics: " + e.getMessage());
                }
            }
        }
    }

}
