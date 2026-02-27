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

import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Top-level telemetry configuration for Gumdrop.
 * Configured via dependency injection in the gumdroprc file.
 * 
 * <p>The presence of a TelemetryConfig (non-null) on a connector indicates
 * that telemetry is enabled. There is no separate "enabled" flag.
 *
 * <p>Example configuration:
 * <pre>
 * &lt;component id="telemetry" class="org.bluezoo.gumdrop.telemetry.TelemetryConfig"&gt;
 *     &lt;property name="service-name"&gt;my-service&lt;/property&gt;
 *     &lt;property name="service-version"&gt;1.0.0&lt;/property&gt;
 *     &lt;property name="endpoint"&gt;http://localhost:4318&lt;/property&gt;
 * &lt;/component&gt;
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryConfig {

    private static final Logger logger = Logger.getLogger(TelemetryConfig.class.getName());

    // Feature flags (traces/logs/metrics can be individually disabled)
    private boolean tracesEnabled = true;
    private boolean logsEnabled = true;
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
    private String protocol = "http/protobuf";
    private String headers;
    private int timeoutMs = 10000;

    // TLS configuration for HTTPS endpoints
    private Path truststoreFile;
    private String truststorePass;
    private String truststoreFormat = "PKCS12";

    // Metrics configuration
    private AggregationTemporality metricsTemporality = AggregationTemporality.CUMULATIVE;
    private long metricsIntervalMs = 60000; // 60 seconds default

    // Batching configuration
    private int batchSize = 512;
    private long flushIntervalMs = 5000;
    private int maxQueueSize = 2048;

    // Additional resource attributes
    private Map<String, String> resourceAttributes;

    // The exporter instance (created when start() is called)
    private TelemetryExporter exporter;

    // Meter registry - maps scope name to meter instance
    private final Map<String, Meter> meters = new ConcurrentHashMap<>();

    /**
     * Creates a new telemetry configuration with default values.
     */
    public TelemetryConfig() {
        this.resourceAttributes = new HashMap<String, String>();
    }

    // -- Feature flags --

    /**
     * Returns true if trace collection is enabled.
     * Traces are enabled by default when TelemetryConfig is present.
     */
    public boolean isTracesEnabled() {
        return tracesEnabled;
    }

    /**
     * Enables or disables trace collection.
     *
     * @param tracesEnabled true to enable traces
     */
    public void setTracesEnabled(boolean tracesEnabled) {
        this.tracesEnabled = tracesEnabled;
    }

    /**
     * Returns true if log collection is enabled.
     * Logs are enabled by default when TelemetryConfig is present.
     */
    public boolean isLogsEnabled() {
        return logsEnabled;
    }

    /**
     * Enables or disables log collection.
     *
     * @param logsEnabled true to enable logs
     */
    public void setLogsEnabled(boolean logsEnabled) {
        this.logsEnabled = logsEnabled;
    }

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
     * Sets the aggregation temporality by name.
     *
     * @param temporality "delta" or "cumulative"
     */
    public void setMetricsTemporalityName(String temporality) {
        if ("delta".equalsIgnoreCase(temporality)) {
            this.metricsTemporality = AggregationTemporality.DELTA;
        } else if ("cumulative".equalsIgnoreCase(temporality)) {
            this.metricsTemporality = AggregationTemporality.CUMULATIVE;
        }
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
    public String getProtocol() {
        return protocol;
    }

    /**
     * Sets the export protocol.
     *
     * @param protocol the protocol ("http/protobuf" or "grpc")
     */
    public void setProtocol(String protocol) {
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
    }

    /**
     * Parses the headers string into a map.
     *
     * @return a map of header names to values
     */
    public Map<String, String> getParsedHeaders() {
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
        return result;
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

    public void setTruststoreFile(String truststoreFile) {
        this.truststoreFile = Path.of(truststoreFile);
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
    public String getTruststoreFormat() {
        return truststoreFormat;
    }

    /**
     * Sets the truststore format.
     *
     * @param truststoreFormat the format (default: PKCS12)
     */
    public void setTruststoreFormat(String truststoreFormat) {
        this.truststoreFormat = truststoreFormat;
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
     * Initializes the telemetry configuration.
     * This method is called automatically by the ComponentRegistry after
     * all properties have been set via the gumdroprc configuration file.
     * 
     * <p>If any OTLP endpoints are configured (tracesEndpoint, logsEndpoint,
     * or metricsEndpoint), an OTLPExporter is automatically created and started.
     */
    public void init() {
        // Only create exporter if at least one endpoint is configured
        if (hasAnyEndpoint() && exporter == null) {
            exporter = new OTLPExporter(this);
            registerShutdownHook();
        }
    }

    /**
     * Returns true if any OTLP endpoint is configured.
     */
    private boolean hasAnyEndpoint() {
        return (tracesEndpoint != null && !tracesEndpoint.isEmpty()) ||
               (logsEndpoint != null && !logsEndpoint.isEmpty()) ||
               (metricsEndpoint != null && !metricsEndpoint.isEmpty()) ||
               (endpoint != null && !endpoint.isEmpty());
    }

    // -- Exporter lifecycle --

    /**
     * Returns the exporter instance.
     */
    public TelemetryExporter getExporter() {
        return exporter;
    }

    /**
     * Sets the exporter instance.
     * Automatically registers a JVM shutdown hook to flush telemetry on exit.
     *
     * @param exporter the exporter
     */
    public void setExporter(TelemetryExporter exporter) {
        this.exporter = exporter;
        if (exporter != null) {
            registerShutdownHook();
        }
    }

    /**
     * Creates a new trace with this configuration.
     *
     * @param rootSpanName the name for the root span
     * @return a new trace, or null if telemetry is disabled
     */
    public Trace createTrace(String rootSpanName) {
        return createTrace(rootSpanName, SpanKind.SERVER);
    }

    /**
     * Creates a new trace with this configuration.
     *
     * @param rootSpanName the name for the root span
     * @param kind the kind for the root span
     * @return a new trace, or null if telemetry is disabled
     */
    public Trace createTrace(String rootSpanName, SpanKind kind) {
        if (!isTracesEnabled()) {
            return null;
        }
        Trace trace = new Trace(rootSpanName, kind);
        trace.setExporter(exporter);
        return trace;
    }

    /**
     * Creates a trace continuing from a remote context.
     *
     * @param traceparent the W3C traceparent header value
     * @param rootSpanName the name for the local root span
     * @param kind the kind for the root span
     * @return a new trace, or null if telemetry is disabled
     */
    public Trace createTraceFromTraceparent(String traceparent, String rootSpanName, SpanKind kind) {
        if (!isTracesEnabled()) {
            return null;
        }
        Trace trace = Trace.fromTraceparent(traceparent, rootSpanName, kind);
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
        return new HashMap<>(meters);
    }

    // -- Shutdown handling --

    private volatile boolean shutdownHookRegistered = false;
    private volatile boolean shuttingDown = false;

    /**
     * Registers a JVM shutdown hook to flush telemetry on exit.
     * This ensures all pending telemetry data is exported before the JVM terminates.
     * The hook is registered automatically when an exporter is set.
     */
    public void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;

        final TelemetryConfig config = this;
        Thread shutdownHook = new Thread(new Runnable() {
            @Override
            public void run() {
                config.shutdown();
            }
        }, "TelemetryShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Shuts down telemetry, flushing all pending data.
     * This method blocks until the flush completes or times out.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;

        if (exporter != null) {
            // Force flush ensures all pending data is exported
            if (exporter instanceof OTLPExporter) {
                ((OTLPExporter) exporter).forceFlush();
            }
            exporter.shutdown();
        }
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
        sb.append(", traces=").append(tracesEnabled);
        sb.append(", logs=").append(logsEnabled);
        sb.append(", metrics=").append(metricsEnabled);
        if (metricsEnabled) {
            sb.append(", temporality=").append(metricsTemporality);
        }
        sb.append("]");
        return sb.toString();
    }

}

