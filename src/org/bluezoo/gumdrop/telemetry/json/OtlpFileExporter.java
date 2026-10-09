/*
 * OtlpFileExporter.java
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
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.BatchingExporter;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.util.BufferingByteChannel;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.MessageFormat;
import java.util.ResourceBundle;

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
 * <p>The file paths are given to the constructor; the buffer size, the
 * batching and metrics settings of {@link BatchingExporter} and the levels
 * are set on the exporter before {@link TelemetryConfig#init()} starts it.
 * <pre>
 * TelemetryConfig telemetry = new TelemetryConfig();
 * telemetry.serviceName("my-service");
 * OtlpFileExporter files = new OtlpFileExporter(
 *         Path.of("/var/log/otel/traces.jsonl"),
 *         Path.of("/var/log/otel/logs.jsonl"),
 *         Path.of("/var/log/otel/metrics.jsonl"));
 * files.fileBufferSize(16384);
 * telemetry.exporter(files);
 * telemetry.init();
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpFileExporter extends BatchingExporter<OtlpFileExporter> {

        private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N",
                org.bluezoo.gumdrop.telemetry.Trace.class.getModule());
private static final Logger logger = Logger.getLogger(OtlpFileExporter.class.getName());

    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

    // How long shutdown waits for the export thread to finish.
    private static final long SHUTDOWN_WAIT_MS = 10000L;

    private final Path tracesPath;
    private final Path logsPath;
    private final Path metricsPath;
    private int fileBufferSize = 8192;

    // Everything below is created by init()
    private TelemetryConfig config;
    private TraceJsonSerializer traceSerializer;
    private LogJsonSerializer logSerializer;
    private MetricJsonSerializer metricSerializer;

    private BufferingByteChannel tracesChannel;
    private BufferingByteChannel logsChannel;
    private BufferingByteChannel metricsChannel;

    private BlockingQueue<Trace> traceQueue;
    private BlockingQueue<LogRecord> logQueue;
    private BlockingQueue<List<MetricData>> metricQueue;

    private volatile ExportThread exportThread;
    private volatile boolean running;

    /**
     * Creates a file exporter that writes all signals to stdout.
     */
    public OtlpFileExporter() {
        this(null, null, null);
    }

    /**
     * Creates a file exporter that writes to the specified file paths.
     * Any null path causes that signal to be written to stdout. The
     * files are opened, creating them and their directories if need be,
     * when the exporter is started.
     *
     * @param tracesPath path for traces JSONL file, or null for stdout
     * @param logsPath path for logs JSONL file, or null for stdout
     * @param metricsPath path for metrics JSONL file, or null for stdout
     */
    public OtlpFileExporter(Path tracesPath, Path logsPath, Path metricsPath) {
        this.tracesPath = tracesPath;
        this.logsPath = logsPath;
        this.metricsPath = metricsPath;
    }

    /**
     * Returns the size of the I/O buffer of each file in bytes.
     *
     * @return the buffer size
     */
    public int getFileBufferSize() {
        return fileBufferSize;
    }

    /**
     * Sets the size of the I/O buffer of each file in bytes. Writes are
     * accumulated in a buffer of this size before being flushed to the
     * underlying file. The default is 8192.
     *
     * @param fileBufferSize the buffer size
     * @return this exporter
     */
    public OtlpFileExporter fileBufferSize(int fileBufferSize) {
        this.fileBufferSize = fileBufferSize;
        return this;
    }

    /**
     * Opens the files and starts the thread that writes them.
     */
    @Override
    public synchronized void init(TelemetryConfig config) {
        if (running) {
            return;
        }
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
                resourceAttrs,
                config.isIncludeExceptionDetails());

        this.metricSerializer = new MetricJsonSerializer(
                config.getServiceName(),
                config.getServiceVersion(),
                config.getServiceNamespace(),
                resourceAttrs);

        int bufferSize = fileBufferSize;
        this.tracesChannel = new BufferingByteChannel(openChannel(tracesPath), bufferSize);
        this.logsChannel = new BufferingByteChannel(openChannel(logsPath), bufferSize);
        this.metricsChannel = new BufferingByteChannel(openChannel(metricsPath), bufferSize);

        this.traceQueue = new ArrayBlockingQueue<>(getMaxQueueSize());
        this.logQueue = new ArrayBlockingQueue<>(getMaxQueueSize());
        this.metricQueue = new ArrayBlockingQueue<>(getMaxQueueSize());

        ExportThread thread = new ExportThread();
        this.exportThread = thread;
        this.running = true;
        thread.start();

        logger.info(L10N.getString("info.file_exporter_started"));
    }

    /**
     * Returns a channel writing to standard output whose close only
     * flushes, so that closing the exporter never closes the
     * process-wide System.out.
     */
    private static WritableByteChannel newStdoutChannel() {
        final PrintStream stdout = System.out;
        // Not Channels.newChannel: that channel is interruptible and
        // closes its stream when the writing thread is interrupted (as the
        // export thread is at shutdown), which would close System.out.
        return new WritableByteChannel() {
            @Override
            public int write(ByteBuffer src) throws IOException {
                int n = src.remaining();
                byte[] bytes = new byte[n];
                src.get(bytes);
                stdout.write(bytes, 0, n);
                return n;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
                stdout.flush();
            }
        };
    }

    private static WritableByteChannel openChannel(Path path) {
        if (path == null) {
            return newStdoutChannel();
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
            logger.warning(MessageFormat.format(L10N.getString("warn.file_open_fallback"), path, e.getMessage()));
            return newStdoutChannel();
        }
    }

    @Override
    public boolean accepts(LogLevel level) {
        return running && takesLevel(level);
    }

    @Override
    public boolean acceptsTraces() {
        return running;
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
        if (!running || record == null || !accepts(record.getLevel())) {
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
        ExportThread thread = exportThread;
        if (thread != null) {
            thread.requestFlush();
        }
    }

    @Override
    public void shutdown() {
        ExportThread thread = exportThread;
        if (thread == null) {
            return;
        }
        running = false;
        thread.requestFlush();

        try {
            thread.join(SHUTDOWN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        closeChannel(tracesChannel);
        closeChannel(logsChannel);
        closeChannel(metricsChannel);

        logger.info(L10N.getString("info.file_exporter_shutdown"));
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

        // The thread waits on this for a trace to arrive or a flush to be
        // requested. It must not be woken by interrupting it: an interrupt
        // that finds it writing closes the file channel it is writing to,
        // and everything written afterwards is lost.
        private final Object wakeLock = new Object();

        ExportThread() {
            super("OtlpFileExporter");
            setDaemon(true);
        }

        void requestFlush() {
            synchronized (wakeLock) {
                flushRequested = true;
                wakeLock.notifyAll();
            }
        }

        /** Wakes the thread because a trace has been queued. */
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
                    long flushWait = getFlushIntervalMs() - (now - lastFlush);
                    long metricsWait = config.isMetricsEnabled()
                            ? getMetricsIntervalMs() - (now - lastMetricsCollection)
                            : flushWait;
                    long waitTime = Math.min(flushWait, metricsWait);

                    if (waitTime > 0) {
                        awaitWork(waitTime);
                    }

                    drainQueue(traceQueue, traceBatch);
                    drainQueue(logQueue, logBatch);
                    drainQueue(metricQueue, metricBatches);

                    now = System.currentTimeMillis();

                    if (config.isMetricsEnabled()
                            && (now - lastMetricsCollection) >= getMetricsIntervalMs()) {
                        collectMetrics();
                        drainQueue(metricQueue, metricBatches);
                        lastMetricsCollection = now;
                    }

                    boolean shouldFlush = flushRequested ||
                            traceBatch.size() >= getBatchSize() ||
                            logBatch.size() >= getBatchSize() ||
                            !metricBatches.isEmpty() ||
                            (now - lastFlush) >= getFlushIntervalMs();

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
            AggregationTemporality temporality = getMetricsTemporality();
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
                        logger.warning(MessageFormat.format(L10N.getString("warn.file_write_trace_failed"), trace.getTraceIdHex(), e.getMessage()));
                    }
                }
                try {
                    tracesChannel.flush();
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.file_flush_traces_failed"), e.getMessage()));
                }
            }
        }

        private void exportLogs(List<LogRecord> records) {
            synchronized (logsChannel) {
                try {
                    logSerializer.serialize(records, logsChannel);
                    writeNewline(logsChannel);
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.file_write_logs_failed"), e.getMessage()));
                }
                try {
                    logsChannel.flush();
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.file_flush_logs_failed"), e.getMessage()));
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
                        logger.warning(MessageFormat.format(L10N.getString("warn.file_write_metrics_failed"), e.getMessage()));
                    }
                }
                try {
                    metricsChannel.flush();
                } catch (IOException e) {
                    logger.warning(MessageFormat.format(L10N.getString("warn.file_flush_metrics_failed"), e.getMessage()));
                }
            }
        }
    }

}
