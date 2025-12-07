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

import org.bluezoo.gumdrop.ClientConnectionPool;
import org.bluezoo.gumdrop.ClientConnectionPool.PoolEntry;
import org.bluezoo.gumdrop.ClientConnectionPool.PoolTarget;
import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientConnection;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPClientStream;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.protobuf.LogSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.MetricSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.TraceSerializer;
import org.bluezoo.gumdrop.telemetry.protobuf.WriteResult;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exports telemetry data to an OpenTelemetry Collector via OTLP/HTTP.
 *
 * <p>This exporter uses Gumdrop's native HTTP client with connection pooling
 * for efficient, non-blocking delivery of telemetry data. Connections are
 * pooled and reused to minimize overhead and maintain SelectorLoop affinity.
 *
 * <p>Data is batched before sending to reduce network overhead. Batches
 * are flushed either when full or when the flush interval expires.
 *
 * <p><strong>Connection Pooling:</strong> The exporter maintains idle
 * connections to the OTLP endpoints, keyed by SelectorLoop for proper
 * thread affinity. Connections are reused across multiple exports,
 * significantly reducing latency and resource usage.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OTLPExporter implements TelemetryExporter {

    private static final Logger LOGGER = Logger.getLogger(OTLPExporter.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");

    private static final String CONTENT_TYPE = "application/x-protobuf";
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024; // 1 MB
    private static final int MAX_CHUNK_SIZE = 64 * 1024; // 64 KB chunks for large payloads

    private final TelemetryConfig config;
    private final TraceSerializer traceSerializer;
    private final LogSerializer logSerializer;
    private final MetricSerializer metricSerializer;

    private final BlockingQueue<Trace> traceQueue;
    private final BlockingQueue<LogRecord> logQueue;
    private final BlockingQueue<List<MetricData>> metricQueue;

    // Connection pool for HTTP connections
    private final ClientConnectionPool connectionPool;

    // Map from connection to its handler (for reusing pooled connections)
    private final Map<Connection, OTLPClientHandler> connectionHandlers;

    // Parsed endpoint information
    private final InetAddress tracesHost;
    private final int tracesPort;
    private final String tracesPath;
    private final boolean tracesSecure;
    private final PoolTarget tracesTarget;

    private final InetAddress logsHost;
    private final int logsPort;
    private final String logsPath;
    private final boolean logsSecure;
    private final PoolTarget logsTarget;

    private final InetAddress metricsHost;
    private final int metricsPort;
    private final String metricsPath;
    private final boolean metricsSecure;
    private final PoolTarget metricsTarget;

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

        this.traceQueue = new ArrayBlockingQueue<Trace>(config.getMaxQueueSize());
        this.logQueue = new ArrayBlockingQueue<LogRecord>(config.getMaxQueueSize());
        this.metricQueue = new ArrayBlockingQueue<List<MetricData>>(config.getMaxQueueSize());

        // Create connection pool with appropriate settings
        this.connectionPool = new ClientConnectionPool();
        connectionPool.setMaxConnectionsPerTarget(4); // Multiple connections for parallelism
        connectionPool.setIdleTimeoutMs(60000); // 60 second idle timeout

        // Map to track handlers for pooled connections
        this.connectionHandlers = new HashMap<Connection, OTLPClientHandler>();

        // Parse traces endpoint
        InetAddress tracesHostParsed = null;
        int tracesPortParsed = 0;
        String tracesPathParsed = null;
        boolean tracesSecureParsed = false;

        String tracesEndpoint = config.getTracesEndpoint();
        if (tracesEndpoint != null) {
            try {
                URI uri = URI.create(tracesEndpoint);
                tracesHostParsed = InetAddress.getByName(uri.getHost());
                tracesPortParsed = uri.getPort() > 0 ? uri.getPort() : 
                        ("https".equals(uri.getScheme()) ? 443 : 80);
                tracesPathParsed = uri.getPath() != null && !uri.getPath().isEmpty() ? 
                        uri.getPath() : "/v1/traces";
                tracesSecureParsed = "https".equals(uri.getScheme());
            } catch (UnknownHostException e) {
                String msg = MessageFormat.format(L10N.getString("err.cannot_resolve_traces_endpoint"), tracesEndpoint);
                LOGGER.warning(msg);
            }
        }

        this.tracesHost = tracesHostParsed;
        this.tracesPort = tracesPortParsed;
        this.tracesPath = tracesPathParsed;
        this.tracesSecure = tracesSecureParsed;
        this.tracesTarget = tracesHostParsed != null ? 
                new PoolTarget(tracesHostParsed, tracesPortParsed, tracesSecureParsed) : null;

        // Parse logs endpoint
        InetAddress logsHostParsed = null;
        int logsPortParsed = 0;
        String logsPathParsed = null;
        boolean logsSecureParsed = false;

        String logsEndpoint = config.getLogsEndpoint();
        if (logsEndpoint != null) {
            try {
                URI uri = URI.create(logsEndpoint);
                logsHostParsed = InetAddress.getByName(uri.getHost());
                logsPortParsed = uri.getPort() > 0 ? uri.getPort() :
                        ("https".equals(uri.getScheme()) ? 443 : 80);
                logsPathParsed = uri.getPath() != null && !uri.getPath().isEmpty() ? 
                        uri.getPath() : "/v1/logs";
                logsSecureParsed = "https".equals(uri.getScheme());
            } catch (UnknownHostException e) {
                String msg = MessageFormat.format(L10N.getString("err.cannot_resolve_logs_endpoint"), logsEndpoint);
                LOGGER.warning(msg);
            }
        }

        this.logsHost = logsHostParsed;
        this.logsPort = logsPortParsed;
        this.logsPath = logsPathParsed;
        this.logsSecure = logsSecureParsed;
        this.logsTarget = logsHostParsed != null ? 
                new PoolTarget(logsHostParsed, logsPortParsed, logsSecureParsed) : null;

        // Parse metrics endpoint
        InetAddress metricsHostParsed = null;
        int metricsPortParsed = 0;
        String metricsPathParsed = null;
        boolean metricsSecureParsed = false;

        String metricsEndpoint = config.getMetricsEndpoint();
        if (metricsEndpoint != null) {
            try {
                URI uri = URI.create(metricsEndpoint);
                metricsHostParsed = InetAddress.getByName(uri.getHost());
                metricsPortParsed = uri.getPort() > 0 ? uri.getPort() :
                        ("https".equals(uri.getScheme()) ? 443 : 80);
                metricsPathParsed = uri.getPath() != null && !uri.getPath().isEmpty() ? 
                        uri.getPath() : "/v1/metrics";
                metricsSecureParsed = "https".equals(uri.getScheme());
            } catch (UnknownHostException e) {
                String msg = MessageFormat.format(L10N.getString("err.cannot_resolve_metrics_endpoint"), metricsEndpoint);
                LOGGER.warning(msg);
            }
        }

        this.metricsHost = metricsHostParsed;
        this.metricsPort = metricsPortParsed;
        this.metricsPath = metricsPathParsed;
        this.metricsSecure = metricsSecureParsed;
        this.metricsTarget = metricsHostParsed != null ? 
                new PoolTarget(metricsHostParsed, metricsPortParsed, metricsSecureParsed) : null;

        this.exportThread = new ExportThread();
        this.running = true;
        this.exportThread.start();

        // Start metrics collection thread if metrics are enabled
        if (config.isMetricsEnabled() && metricsTarget != null) {
            this.metricsCollectionThread = new MetricsCollectionThread();
            this.metricsCollectionThread.start();
        } else {
            this.metricsCollectionThread = null;
        }
    }

    @Override
    public void export(Trace trace) {
        if (!running || trace == null) {
            return;
        }
        if (!traceQueue.offer(trace)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Trace queue full, dropping trace: " + trace.getTraceIdHex());
            }
        }
    }

    @Override
    public void export(LogRecord record) {
        if (!running || record == null) {
            return;
        }
        if (!logQueue.offer(record)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Log queue full, dropping log record");
            }
        }
    }

    @Override
    public void export(List<MetricData> metrics) {
        if (!running || metrics == null || metrics.isEmpty()) {
            return;
        }
        if (!metricQueue.offer(metrics)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Metric queue full, dropping metrics batch");
            }
        }
    }

    @Override
    public void flush() {
        exportThread.requestFlush();
        // Wait for flush to complete (with timeout)
        long deadline = System.currentTimeMillis() + config.getTimeoutMs();
        while (exportThread.isFlushing() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void shutdown() {
        // First, request a final flush of all pending data
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
        
        // Shutdown the connection pool
        connectionPool.shutdown();
    }

    /**
     * Forces an immediate flush of all pending telemetry data.
     * This method blocks until the flush completes or times out.
     * Used during shutdown to ensure all telemetry is exported.
     */
    public void forceFlush() {
        if (!running) {
            return;
        }

        exportThread.requestFlush();

        // Wait for flush to complete with timeout
        long deadline = System.currentTimeMillis() + config.getTimeoutMs();
        while (exportThread.isFlushing() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Background thread that batches and exports telemetry data.
     */
    private class ExportThread extends Thread {

        private volatile boolean flushRequested;
        private volatile boolean flushing;

        ExportThread() {
            super("OTLPExporter");
            setDaemon(true);
        }

        void requestFlush() {
            flushRequested = true;
            interrupt();
        }

        boolean isFlushing() {
            return flushing;
        }

        @Override
        public void run() {
            List<Trace> traceBatch = new ArrayList<Trace>();
            List<LogRecord> logBatch = new ArrayList<LogRecord>();
            List<List<MetricData>> metricBatches = new ArrayList<List<MetricData>>();
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
                    drainTraces(traceBatch);
                    drainLogs(logBatch);
                    drainMetrics(metricBatches);

                    // Check if we should flush
                    now = System.currentTimeMillis();
                    boolean shouldFlush = flushRequested ||
                            traceBatch.size() >= config.getBatchSize() ||
                            logBatch.size() >= config.getBatchSize() ||
                            !metricBatches.isEmpty() ||
                            (now - lastFlush) >= config.getFlushIntervalMs();

                    if (shouldFlush && (!traceBatch.isEmpty() || !logBatch.isEmpty() || !metricBatches.isEmpty())) {
                        flushing = true;
                        try {
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
                        } finally {
                            flushing = false;
                            flushRequested = false;
                        }
                        lastFlush = System.currentTimeMillis();
                    }

                } catch (InterruptedException e) {
                    // Continue to check for shutdown or flush
                }
            }

            // Final flush on shutdown
            drainTraces(traceBatch);
            drainLogs(logBatch);
            drainMetrics(metricBatches);
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

        private void drainTraces(List<Trace> batch) {
            Trace trace;
            while ((trace = traceQueue.poll()) != null) {
                batch.add(trace);
            }
        }

        private void drainLogs(List<LogRecord> batch) {
            LogRecord record;
            while ((record = logQueue.poll()) != null) {
                batch.add(record);
            }
        }

        private void drainMetrics(List<List<MetricData>> batches) {
            List<MetricData> metrics;
            while ((metrics = metricQueue.poll()) != null) {
                batches.add(metrics);
            }
        }

        private void exportTraces(List<Trace> traces) {
            if (tracesHost == null || tracesTarget == null) {
                return;
            }

            ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);

            for (Trace trace : traces) {
                buffer.clear();
                WriteResult result = traceSerializer.serialize(trace, buffer);
                if (result == WriteResult.OVERFLOW) {
                    String msg = MessageFormat.format(L10N.getString("err.trace_too_large"), trace.getTraceIdHex());
                    LOGGER.warning(msg);
                    continue;
                }

                buffer.flip();
                sendRequest(tracesHost, tracesPort, tracesPath, tracesSecure, tracesTarget, buffer);
            }
        }

        private void exportLogs(List<LogRecord> records) {
            if (logsHost == null || logsTarget == null) {
                return;
            }

            ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            buffer.clear();

            WriteResult result = logSerializer.serialize(records, buffer);
            if (result == WriteResult.OVERFLOW) {
                LOGGER.warning(L10N.getString("err.logs_too_large"));
                return;
            }

            buffer.flip();
            sendRequest(logsHost, logsPort, logsPath, logsSecure, logsTarget, buffer);
        }

        private void exportMetrics(List<List<MetricData>> batches) {
            if (metricsHost == null || metricsTarget == null) {
                return;
            }

            ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);

            for (List<MetricData> metrics : batches) {
                buffer.clear();

                // Use "gumdrop" as the default meter name for externally collected metrics
                WriteResult result = metricSerializer.serialize(metrics, "gumdrop", "0.4", buffer);
                if (result == WriteResult.OVERFLOW) {
                    LOGGER.warning(L10N.getString("err.metrics_too_large"));
                    continue;
                }

                buffer.flip();
                sendRequest(metricsHost, metricsPort, metricsPath, metricsSecure, metricsTarget, buffer);
            }
        }

        private void sendRequest(InetAddress host, int port, String path, boolean secure,
                                 PoolTarget target, ByteBuffer data) {
            // Build request headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", CONTENT_TYPE);
            headers.put("Content-Length", String.valueOf(data.remaining()));
            
            String hostHeader = host.getHostAddress();
            if ((secure && port != 443) || (!secure && port != 80)) {
                hostHeader += ":" + port;
            }
            headers.put("Host", hostHeader);

            // Add custom headers from config
            Map<String, String> customHeaders = config.getParsedHeaders();
            headers.putAll(customHeaders);

            HTTPRequest request = new HTTPRequest("POST", path, headers);

            // Create a copy of the data for the async handler
            byte[] bodyData = new byte[data.remaining()];
            data.get(bodyData);
            ByteBuffer body = ByteBuffer.wrap(bodyData);

            // Try to acquire a pooled connection
            PoolEntry poolEntry = connectionPool.tryAcquire(target);
            
            if (poolEntry != null) {
                // Reuse pooled connection
                Connection conn = poolEntry.getConnection();
                if (conn instanceof HTTPClientConnection) {
                    HTTPClientConnection httpConn = (HTTPClientConnection) conn;
                    if (httpConn.isOpen() && httpConn.isProtocolNegotiated()) {
                        // Look up the handler for this connection
                        OTLPClientHandler handler;
                        synchronized (connectionHandlers) {
                            handler = connectionHandlers.get(conn);
                        }
                        
                        if (handler != null) {
                            LOGGER.fine("Reusing pooled OTLP connection to " + target);
                            
                            // Send request on this connection's SelectorLoop
                            SelectorLoop loop = conn.getSelectorLoop();
                            if (loop != null) {
                                loop.invokeLater(new SendRequestTask(httpConn, poolEntry, handler, request, body));
                                return;
                            }
                        }
                    }
                }
                // Connection is not usable or handler not found, remove it
                synchronized (connectionHandlers) {
                    connectionHandlers.remove(poolEntry.getConnection());
                }
                connectionPool.remove(poolEntry);
            }

            // Create new connection
            try {
                HTTPClient client = new HTTPClient(host, port, secure);
                client.setVersion(HTTPVersion.HTTP_1_1); // OTLP typically uses HTTP/1.1

                OTLPClientHandler handler = new OTLPClientHandler(target, request, body);
                client.connect(handler);

            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "OTLP export failed to connect", e);
                }
            }
        }
    }

    /**
     * Task to send a request on a pooled connection.
     * This queues the request with the connection's handler and triggers stream creation.
     */
    private class SendRequestTask implements Runnable {
        private final HTTPClientConnection connection;
        private final PoolEntry poolEntry;
        private final OTLPClientHandler handler;
        private final HTTPRequest request;
        private final ByteBuffer body;

        SendRequestTask(HTTPClientConnection connection, PoolEntry poolEntry, 
                       OTLPClientHandler handler, HTTPRequest request, ByteBuffer body) {
            this.connection = connection;
            this.poolEntry = poolEntry;
            this.handler = handler;
            this.request = request;
            this.body = body;
        }

        @Override
        public void run() {
            try {
                // Queue the request with the handler
                handler.queueRequest(request, body);
                
                // Create a new stream - this triggers onStreamCreated which will
                // pull the request from the queue and send it
                connection.createStream();
                
            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "OTLP export failed to send request on pooled connection", e);
                }
                connectionPool.remove(poolEntry);
            }
        }
    }

    /**
     * A pending request waiting to be sent.
     */
    private static class PendingRequest {
        final HTTPRequest request;
        final ByteBuffer body;

        PendingRequest(HTTPRequest request, ByteBuffer body) {
            this.request = request;
            this.body = body;
        }
    }

    /**
     * HTTP client handler for OTLP export requests.
     * Handles the async request/response lifecycle and pool integration.
     * Supports connection reuse by maintaining a queue of pending requests.
     */
    private class OTLPClientHandler implements HTTPClientHandler {

        private final PoolTarget target;
        private final Deque<PendingRequest> pendingRequests;
        private HTTPClientConnection connection;
        private PoolEntry poolEntry;

        OTLPClientHandler(PoolTarget target, HTTPRequest initialRequest, ByteBuffer initialBody) {
            this.target = target;
            this.pendingRequests = new ConcurrentLinkedDeque<PendingRequest>();
            // Queue the initial request
            pendingRequests.add(new PendingRequest(initialRequest, initialBody));
        }

        /**
         * Queues a request to be sent on this connection.
         * Call createStream() after this to trigger the request to be sent.
         */
        void queueRequest(HTTPRequest request, ByteBuffer body) {
            pendingRequests.add(new PendingRequest(request, body));
        }

        @Override
        public void onConnected() {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("OTLP exporter connected to " + target);
            }
        }

        @Override
        public void onTLSStarted() {
            // TLS handshake completed - nothing specific needed for OTLP
        }

        @Override
        public void onProtocolNegotiated(HTTPVersion protocol, HTTPClientConnection conn) {
            this.connection = conn;
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("OTLP protocol negotiated: " + protocol);
            }

            // Register with connection pool
            poolEntry = connectionPool.register(target, conn);
            
            // Associate this handler with the connection for future reuse
            synchronized (connectionHandlers) {
                connectionHandlers.put(conn, this);
            }

            // Create a stream for the first queued request
            try {
                conn.createStream();
            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "OTLP export failed to create stream", e);
                }
                removeFromPool();
            }
        }
        
        /**
         * Removes this connection from the pool and handler map.
         */
        private void removeFromPool() {
            if (poolEntry != null) {
                synchronized (connectionHandlers) {
                    connectionHandlers.remove(connection);
                }
                connectionPool.remove(poolEntry);
                poolEntry = null;
            }
        }

        @Override
        public void onStreamCreated(HTTPClientStream stream) {
            // Pull the next request from the queue
            PendingRequest pending = pendingRequests.poll();
            if (pending == null) {
                // No pending requests - this shouldn't happen but handle gracefully
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Stream created but no pending request");
                }
                stream.cancel(0);
                return;
            }

            try {
                // Send the request headers
                stream.sendRequest(pending.request);

                // Send the body in chunks if large, otherwise all at once
                if (pending.body.remaining() > MAX_CHUNK_SIZE) {
                    sendChunkedBody(stream, pending.body);
                } else {
                    stream.sendData(pending.body, true);
                }

            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "OTLP export failed to send request", e);
                }
                stream.cancel(0);
                removeFromPool();
            }
        }

        private void sendChunkedBody(HTTPClientStream stream, ByteBuffer data) throws IOException {
            while (data.hasRemaining()) {
                int chunkSize = Math.min(data.remaining(), MAX_CHUNK_SIZE);
                ByteBuffer chunk = data.slice();
                chunk.limit(chunkSize);
                data.position(data.position() + chunkSize);

                boolean lastChunk = !data.hasRemaining();
                stream.sendData(chunk, lastChunk);
            }
        }

        @Override
        public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
            int statusCode = response.getStatusCode();
            if (statusCode < 200 || statusCode >= 300) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    String msg = MessageFormat.format(L10N.getString("err.export_failed"), statusCode);
                    LOGGER.warning(msg);
                }
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("OTLP export successful: HTTP " + statusCode);
                }
            }
        }

        @Override
        public void onStreamData(HTTPClientStream stream, ByteBuffer data, boolean endStream) {
            // We don't need response body for OTLP - discard it
        }

        @Override
        public void onStreamComplete(HTTPClientStream stream) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("OTLP export stream complete");
            }
            // Release connection back to pool for reuse
            if (poolEntry != null) {
                connectionPool.release(poolEntry);
            }
        }

        @Override
        public void onStreamError(HTTPClientStream stream, Exception error) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "OTLP export stream error", error);
            }
            // Remove from pool on error
            removeFromPool();
        }

        @Override
        public void onServerSettings(Map<Integer, Long> settings) {
            // Not needed for HTTP/1.1
        }

        @Override
        public void onGoAway(int lastStreamId, int errorCode, String debugData) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("OTLP server sent GOAWAY: " + errorCode);
            }
            // Remove from pool
            removeFromPool();
        }

        @Override
        public boolean onPushPromise(HTTPClientStream promisedStream, HTTPRequest promisedRequest) {
            // Reject server push - we don't need it
            return false;
        }

        @Override
        public void onDisconnected() {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("OTLP exporter disconnected from " + target);
            }
            // Remove from pool
            removeFromPool();
        }

        @Override
        public void onError(Exception error) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "OTLP export connection error", error);
            }
            // Remove from pool
            removeFromPool();
        }
    }

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

                    // Collect metrics from all meters
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
