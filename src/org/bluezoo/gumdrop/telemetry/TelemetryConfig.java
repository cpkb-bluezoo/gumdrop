/*
 * TelemetryConfig.java
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

import java.nio.file.Path;
import java.util.Collections;
import java.util.ResourceBundle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level telemetry configuration for Gumdrop: where traces, log
 * events and metrics go. Composed in Java and set on the runtime with
 * {@link org.bluezoo.gumdrop.Gumdrop#setTelemetryConfig}, one per runtime:
 * listeners and endpoints reach it through the runtime, and the runtime
 * flushes and shuts it down when it shuts down.
 *
 * <p>The destinations are a tree of {@link TelemetryExporter}s, set
 * with {@link #setExporter}: one exporter, or several joined by
 * {@link TeeExporter}. A fresh configuration has the {@link
 * DefaultExporter}, which prints log events through
 * {@code java.util.logging} and takes nothing else. Each exporter
 * takes the log levels it is configured for, so one tree can send
 * operational events to the console and a collector, access events to
 * a file, and qlog events to a qlog directory.
 *
 * <pre>
 * TelemetryConfig telemetry = new TelemetryConfig();
 * telemetry.setServiceName("my-service");
 * telemetry.setEndpoint("https://collector:4318");
 * telemetry.setExporter(new TeeExporter(new OtlpExporter(telemetry), new DefaultExporter()));
 * telemetry.init();
 * </pre>
 *
 * <p>Classes emit operational events through the {@link EventLogger}
 * that {@link #getLogger} returns for them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryConfig {

    // Feature flags (traces/logs/metrics can be individually disabled)
    private boolean metricsEnabled = true;

    // Resource attributes
    private String serviceName = "gumdrop";
    private String serviceVersion;
    private String serviceNamespace;
    private String serviceInstanceId;
    private String deploymentEnvironment;


    // OTLP exporter settings
    private String endpoint;
    private String tracesEndpoint;
    private String logsEndpoint;
    private String metricsEndpoint;
    private Protocol protocol = Protocol.HTTP_PROTOBUF;
    private String headers;
    private volatile Map<String, String> parsedHeadersCache;
    private int timeoutMs = 10000;

    // TLS configuration for HTTPS endpoints
    private Path truststoreFile;
    private String truststorePass;
    private KeystoreFormat truststoreFormat = KeystoreFormat.PKCS12;

    // File exporter settings
    private int fileBufferSize = 8192; // 8KB default

    // Metrics configuration
    private AggregationTemporality metricsTemporality = AggregationTemporality.CUMULATIVE;
    private long metricsIntervalMs = 60000; // 60 seconds default

    // Batching configuration
    private int batchSize = 512;
    private long flushIntervalMs = 5000;
    private int maxQueueSize = 2048;

    // Exception detail export (default off for security)
    private boolean includeExceptionDetails = false;

    // Additional resource attributes
    private Map<String, String> resourceAttributes;

    // The exporter tree: where everything goes
    private TelemetryExporter exporter = new DefaultExporter();

    // Event loggers, by instrumentation scope
    private final Map<String, EventLogger> loggers = new ConcurrentHashMap<String, EventLogger>();

    // Meter registry - maps scope name to meter instance
    private final Map<String, Meter> meters = new ConcurrentHashMap<>();

    // JMX bridge - exposes metrics via MBeans when enabled
    private boolean jmxBridgeEnabled = true;
    private TelemetryJMXBridge jmxBridge;

    /**
     * Creates a new telemetry configuration with default values.
     */
    public TelemetryConfig() {
        this.resourceAttributes = new HashMap<String, String>();
    }

    // -- Feature flags --

    /**
     * Returns true if metrics collection is enabled.
     * Metrics are enabled by default when TelemetryConfig is present.
     */
    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    /**
     * Enables or disables metrics collection.
     *
     * @param metricsEnabled true to enable metrics
     */
    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    // -- Resource attributes --

    /**
     * Returns the service name.
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Sets the service name.
     *
     * @param serviceName the service name
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Returns the service version.
     */
    public String getServiceVersion() {
        return serviceVersion;
    }

    /**
     * Sets the service version.
     *
     * @param serviceVersion the service version
     */
    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }

    /**
     * Returns the service namespace.
     */
    public String getServiceNamespace() {
        return serviceNamespace;
    }

    /**
     * Sets the service namespace.
     *
     * @param serviceNamespace the service namespace
     */
    public void setServiceNamespace(String serviceNamespace) {
        this.serviceNamespace = serviceNamespace;
    }

    /**
     * Returns the service instance ID.
     */
    public String getServiceInstanceId() {
        return serviceInstanceId;
    }

    /**
     * Sets the service instance ID.
     *
     * @param serviceInstanceId the service instance ID
     */
    public void setServiceInstanceId(String serviceInstanceId) {
        this.serviceInstanceId = serviceInstanceId;
    }

    /**
     * Returns the deployment environment.
     */
    public String getDeploymentEnvironment() {
        return deploymentEnvironment;
    }

    /**
     * Sets the deployment environment.
     *
     * @param deploymentEnvironment the deployment environment (e.g., "production")
     */
    public void setDeploymentEnvironment(String deploymentEnvironment) {
        this.deploymentEnvironment = deploymentEnvironment;
    }

    /**
     * Returns additional resource attributes.
     */
    public Map<String, String> getResourceAttributes() {
        return resourceAttributes;
    }

    /**
     * Adds a resource attribute.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    public void addResourceAttribute(String key, String value) {
        resourceAttributes.put(key, value);
    }

    /** The OTLP transport used when exporting to a collector. */
    public enum Protocol {
        /** OTLP over HTTP with protobuf bodies. */
        HTTP_PROTOBUF,
        /** OTLP over gRPC. */
        GRPC
    }

    // -- Exporter settings --

    /**
     * Returns the OTLP endpoint URL.
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the OTLP endpoint URL.
     * This is the base URL; /v1/traces and /v1/logs will be appended.
     *
     * @param endpoint the endpoint URL (e.g., "http://localhost:4318")
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Returns the traces-specific endpoint, or the base endpoint with /v1/traces.
     */
    public String getTracesEndpoint() {
        if (tracesEndpoint != null) {
            return tracesEndpoint;
        }
        if (endpoint != null) {
            return endpoint + "/v1/traces";
        }
        return null;
    }

    /**
     * Sets a traces-specific endpoint.
     *
     * @param tracesEndpoint the traces endpoint URL
     */
    public void setTracesEndpoint(String tracesEndpoint) {
        this.tracesEndpoint = tracesEndpoint;
    }

    /**
     * Returns the logs-specific endpoint, or the base endpoint with /v1/logs.
     */
    public String getLogsEndpoint() {
        if (logsEndpoint != null) {
            return logsEndpoint;
        }
        if (endpoint != null) {
            return endpoint + "/v1/logs";
        }
        return null;
    }

    /**
     * Sets a logs-specific endpoint.
     *
     * @param logsEndpoint the logs endpoint URL
     */
    public void setLogsEndpoint(String logsEndpoint) {
        this.logsEndpoint = logsEndpoint;
    }

    /**
     * Returns the metrics-specific endpoint, or the base endpoint with /v1/metrics.
     */
    public String getMetricsEndpoint() {
        if (metricsEndpoint != null) {
            return metricsEndpoint;
        }
        if (endpoint != null) {
            return endpoint + "/v1/metrics";
        }
        return null;
    }

    /**
     * Sets a metrics-specific endpoint.
     *
     * @param metricsEndpoint the metrics endpoint URL
     */
    public void setMetricsEndpoint(String metricsEndpoint) {
        this.metricsEndpoint = metricsEndpoint;
    }

    /**
     * Returns the aggregation temporality for metrics.
     */
    public AggregationTemporality getMetricsTemporality() {
        return metricsTemporality;
    }

    /**
     * Sets the aggregation temporality for metrics.
     *
     * @param temporality DELTA or CUMULATIVE
     */
    public void setMetricsTemporality(AggregationTemporality temporality) {
        this.metricsTemporality = temporality;
    }

    /**
     * Returns the metrics collection interval in milliseconds.
     */
    public long getMetricsIntervalMs() {
        return metricsIntervalMs;
    }

    /**
     * Sets the metrics collection interval in milliseconds.
     *
     * @param metricsIntervalMs the interval
     */
    public void setMetricsIntervalMs(long metricsIntervalMs) {
        this.metricsIntervalMs = metricsIntervalMs;
    }

    /**
     * Returns the export protocol.
     */
    public Protocol getProtocol() {
        return protocol;
    }

    /**
     * Sets the export protocol.
     *
     * @param protocol the OTLP transport
     */
    public void setProtocol(Protocol protocol) {
        if (protocol == null) {
            throw new NullPointerException("protocol");
        }
        this.protocol = protocol;
    }

    /**
     * Returns extra headers to send with export requests.
     */
    public String getHeaders() {
        return headers;
    }

    /**
     * Sets extra headers to send with export requests.
     * Format: "key1=value1,key2=value2"
     *
     * @param headers the headers string
     */
    public void setHeaders(String headers) {
        this.headers = headers;
        this.parsedHeadersCache = null;
    }

    /**
     * Parses the headers string into a map.
     * The result is cached and invalidated when headers are set.
     *
     * @return an unmodifiable map of header names to values
     */
    public Map<String, String> getParsedHeaders() {
        Map<String, String> cache = parsedHeadersCache;
        if (cache != null) {
            return Collections.unmodifiableMap(cache);
        }
        Map<String, String> result = new HashMap<String, String>();
        if (headers != null && headers.length() > 0) {
            int start = 0;
            int length = headers.length();
            while (start <= length) {
                int end = headers.indexOf(',', start);
                if (end < 0) {
                    end = length;
                }
                String pair = headers.substring(start, end);
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = pair.substring(0, idx).trim();
                    String value = pair.substring(idx + 1).trim();
                    result.put(key, value);
                }
                start = end + 1;
            }
        }
        parsedHeadersCache = result;
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the export timeout in milliseconds.
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Sets the export timeout in milliseconds.
     *
     * @param timeoutMs the timeout
     */
    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    // -- TLS settings --

    /**
     * Returns the truststore file path for HTTPS endpoints.
     *
     * <p>When connecting to HTTPS OTLP endpoints, this truststore is used
     * to verify the server's certificate. If not set, the JVM's default
     * truststore is used.
     */
    public Path getTruststoreFile() {
        return truststoreFile;
    }

    /**
     * Sets the truststore file path for HTTPS endpoints.
     *
     * @param truststoreFile the path to the truststore file
     */
    public void setTruststoreFile(Path truststoreFile) {
        this.truststoreFile = truststoreFile;
    }

    /**
     * Returns the truststore password.
     */
    public String getTruststorePass() {
        return truststorePass;
    }

    /**
     * Sets the truststore password.
     *
     * @param truststorePass the truststore password
     */
    public void setTruststorePass(String truststorePass) {
        this.truststorePass = truststorePass;
    }

    /**
     * Returns the truststore format.
     */
    public KeystoreFormat getTruststoreFormat() {
        return truststoreFormat;
    }

    /**
     * Sets the truststore format.
     *
     * @param truststoreFormat the format (default: PKCS12)
     */
    public void setTruststoreFormat(KeystoreFormat truststoreFormat) {
        this.truststoreFormat = truststoreFormat;
    }

    // -- File exporter settings --

    /**
     * Returns the buffer size for file exporter I/O in bytes.
     */
    public int getFileBufferSize() {
        return fileBufferSize;
    }

    /**
     * Sets the buffer size for file exporter I/O in bytes.
     * Writes are accumulated in a buffer of this size before being
     * flushed to the underlying file channel.
     *
     * @param fileBufferSize buffer size in bytes (default 8192)
     */
    public void setFileBufferSize(int fileBufferSize) {
        this.fileBufferSize = fileBufferSize;
    }

    public void setFileBufferSize(String fileBufferSize) {
        this.fileBufferSize = Integer.parseInt(fileBufferSize);
    }

    // -- Batching settings --

    /**
     * Returns the batch size for exports.
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the batch size for exports.
     *
     * @param batchSize the batch size
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Returns the flush interval in milliseconds.
     */
    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    /**
     * Sets the flush interval in milliseconds.
     *
     * @param flushIntervalMs the flush interval
     */
    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    /**
     * Returns the maximum queue size.
     */
    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    /**
     * Sets the maximum queue size.
     *
     * @param maxQueueSize the max queue size
     */
    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    // -- Lifecycle methods --

    /**
     * Returns true if the JMX bridge is enabled.
     * When enabled, OpenTelemetry metrics are exposed via MBeans under
     * {@code org.bluezoo.gumdrop:type=Telemetry}.
     */
    public boolean isJmxBridgeEnabled() {
        return jmxBridgeEnabled;
    }

    /**
     * Enables or disables the JMX bridge.
     *
     * @param jmxBridgeEnabled true to expose metrics via JMX (default: true)
     */
    public void setJmxBridgeEnabled(boolean jmxBridgeEnabled) {
        this.jmxBridgeEnabled = jmxBridgeEnabled;
    }

    /**
     * Returns true if span exception records include full message and stack trace.
     * Default: false (only exception class name is exported).
     */
    public boolean isIncludeExceptionDetails() {
        return includeExceptionDetails;
    }

    /**
     * Sets whether span exception records include full message and stack trace.
     * XML property: {@code include-exception-details}
     *
     * @param includeExceptionDetails true for full details (use only when
     *        telemetry export is trusted, e.g. internal collector)
     */
    public void setIncludeExceptionDetails(boolean includeExceptionDetails) {
        this.includeExceptionDetails = includeExceptionDetails;
    }

    /**
     * Returns the JMX bridge instance, or null if not registered.
     */
    public TelemetryJMXBridge getJmxBridge() {
        return jmxBridge;
    }

    /**
     * Initializes the telemetry configuration, after configuration
     * properties have been set and the exporter tree is composed.
     *
     * <p>When the {@code QLOGDIR} environment variable names a directory
     * and no exporter in the tree takes qlog events, a {@link QlogExporter}
     * writing there is added to the tree: this is how the QUIC interop
     * runner asks for qlog output.
     *
     * <p>When metrics and the JMX bridge are enabled, metrics are exposed
     * via MBeans for JMX-based monitoring tools.
     */
    public void init() {
        if (!exporter.accepts(LogLevel.QLOG)) {
            String environment = System.getenv("QLOGDIR");
            if (environment != null && !environment.isEmpty()) {
                exporter = new TeeExporter(exporter,
                        new QlogExporter(Path.of(environment), QlogExporter.DEFAULT_QUEUE_SIZE));
            }
        }
        if (jmxBridge == null && metricsEnabled && jmxBridgeEnabled) {
            jmxBridge = new TelemetryJMXBridge(this);
            jmxBridge.register();
        }
    }

    // -- Exporters and loggers --

    /**
     * Returns the exporter tree. A fresh configuration has a
     * {@link DefaultExporter}, so this is never null.
     *
     * @return the exporter
     */
    public TelemetryExporter getExporter() {
        return exporter;
    }

    /**
     * Sets the exporter tree: one exporter, or several joined by
     * {@link TeeExporter}. The runtime flushes and shuts the tree down
     * when it shuts down.
     *
     * @param exporter the exporter
     */
    public void setExporter(TelemetryExporter exporter) {
        if (exporter == null) {
            throw new IllegalArgumentException("exporter");
        }
        this.exporter = exporter;
    }

    /**
     * Returns whether any exporter in the tree takes log records of a
     * level. Code that would build an expensive record asks this first.
     *
     * @param level the level
     * @return true if an exporter accepts it
     */
    public boolean accepts(LogLevel level) {
        return exporter.accepts(level);
    }

    /**
     * Returns the event logger for a class, creating it on first use.
     * The class is the instrumentation scope of the events it emits,
     * and the bundle is where their keys are looked up: the one the
     * class already holds for its messages.
     *
     * @param scope the emitting class
     * @param bundle the class's resource bundle
     * @return the logger
     */
    public EventLogger getLogger(Class<?> scope, ResourceBundle bundle) {
        String name = scope.getName();
        EventLogger events = loggers.get(name);
        if (events == null) {
            events = new EventLogger(this, name, bundle);
            EventLogger existing = loggers.putIfAbsent(name, events);
            if (existing != null) {
                events = existing;
            }
        }
        return events;
    }

    /**
     * Creates a new trace with this configuration.
     *
     * @param rootSpanName the name for the root span
     * @return a new trace, or null if no exporter takes traces
     */
    public Trace createTrace(String rootSpanName) {
        return createTrace(rootSpanName, SpanKind.SERVER);
    }

    /**
     * Creates a new trace with this configuration.
     *
     * @param rootSpanName the name for the root span
     * @param kind the kind for the root span
     * @return a new trace, or null if no exporter takes traces
     */
    public Trace createTrace(String rootSpanName, SpanKind kind) {
        if (!exporter.acceptsTraces()) {
            return null;
        }
        Trace trace = new Trace(rootSpanName, kind);
        trace.setIncludeExceptionDetails(includeExceptionDetails);
        trace.setExporter(exporter);
        return trace;
    }

    /**
     * Creates a trace continuing from a remote context.
     *
     * @param traceparent the W3C traceparent header value
     * @param rootSpanName the name for the local root span
     * @param kind the kind for the root span
     * @return a new trace, or null if no exporter takes traces
     */
    public Trace createTraceFromTraceparent(String traceparent, String rootSpanName, SpanKind kind) {
        if (!exporter.acceptsTraces()) {
            return null;
        }
        Trace trace = Trace.fromTraceparent(traceparent, rootSpanName, kind);
        trace.setIncludeExceptionDetails(includeExceptionDetails);
        trace.setExporter(exporter);
        return trace;
    }

    // -- Meter factory --

    /**
     * Returns a Meter for the given instrumentation scope.
     * If a Meter for this scope already exists, it is returned.
     *
     * @param name the instrumentation scope name (e.g., "org.bluezoo.gumdrop.http")
     * @return the Meter, or a no-op meter if metrics are disabled
     */
    public Meter getMeter(String name) {
        return getMeter(name, null, null);
    }

    /**
     * Returns a Meter for the given instrumentation scope.
     * If a Meter for this scope already exists, it is returned.
     *
     * @param name the instrumentation scope name
     * @param version the instrumentation scope version
     * @return the Meter, or a no-op meter if metrics are disabled
     */
    public Meter getMeter(String name, String version) {
        return getMeter(name, version, null);
    }

    /**
     * Returns a Meter for the given instrumentation scope.
     * If a Meter for this scope already exists, it is returned.
     *
     * @param name the instrumentation scope name
     * @param version the instrumentation scope version
     * @param schemaUrl the schema URL
     * @return the Meter
     */
    public Meter getMeter(String name, String version, String schemaUrl) {
        String key = name + (version != null ? ":" + version : "");
        Meter meter = meters.get(key);
        if (meter == null) {
            meter = new Meter(name, version, schemaUrl);
            Meter existing = meters.putIfAbsent(key, meter);
            if (existing != null) {
                meter = existing;
            }
        }
        return meter;
    }

    /**
     * Returns all registered meters.
     */
    public Map<String, Meter> getMeters() {
        return Collections.unmodifiableMap(meters);
    }

    // -- Shutdown handling --

    private volatile boolean shuttingDown = false;

    /**
     * Shuts down telemetry, flushing all pending data.
     * This method blocks until the flush completes or times out.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;

        if (jmxBridge != null) {
            jmxBridge.unregister();
            jmxBridge = null;
        }
        exporter.forceFlush();
        exporter.shutdown();
    }

    /**
     * Returns true if telemetry is currently shutting down.
     *
     * @return true if shutdown has been initiated
     */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TelemetryConfig[");
        sb.append("service=").append(serviceName);
        if (endpoint != null) {
            sb.append(", endpoint=").append(endpoint);
        }
        sb.append(", exporter=").append(exporter.getClass().getSimpleName());
        sb.append(", metrics=").append(metricsEnabled);
        if (metricsEnabled) {
            sb.append(", temporality=").append(metricsTemporality);
        }
        sb.append("]");
        return sb.toString();
    }

}

