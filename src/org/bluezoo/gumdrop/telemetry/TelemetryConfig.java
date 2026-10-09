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
 * with {@link #exporter}: one exporter, or several joined by
 * {@link TeeExporter}. A fresh configuration has the {@link
 * DefaultExporter}, which prints log events through
 * {@code java.util.logging} and takes nothing else. Each exporter
 * takes the log levels it is configured for, so one tree can send
 * operational events to the console and a collector, access events to
 * a file, and qlog events to a qlog directory.
 *
 * <p>This class holds what is true of the service and of the runtime:
 * its identity, whether metrics are collected, and the exception detail
 * policy. What is particular to a destination, such as an endpoint, a
 * batch size or a TLS configuration, is a setting of the exporter that
 * sends there.
 *
 * <pre>
 * TelemetryConfig telemetry = new TelemetryConfig();
 * telemetry.serviceName("my-service");
 * OtlpExporter otlp = new OtlpExporter();
 * otlp.endpoint("https://collector:4318");
 * telemetry.exporter(new TeeExporter(otlp, new DefaultExporter()));
 * telemetry.init();
 * </pre>
 *
 * <p>Classes emit operational events through the {@link EventLogger}
 * that {@link #getLogger} returns for them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryConfig {

    // Metrics can be disabled
    private boolean metricsEnabled = true;

    // Resource attributes
    private String serviceName = "gumdrop";
    private String serviceVersion;
    private String serviceNamespace;
    private String serviceInstanceId;
    private String deploymentEnvironment;


    // Exception detail export (default off for security)
    private boolean includeExceptionDetails = false;

    // Additional resource attributes
    private Map<String, String> resourceAttributes;

    // The exporter tree: where everything goes
    private TelemetryExporter exporter = new DefaultExporter();
    private boolean initialised;

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
     * @return this configuration
     */
    public TelemetryConfig metricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig serviceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig serviceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig serviceNamespace(String serviceNamespace) {
        this.serviceNamespace = serviceNamespace;
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig serviceInstanceId(String serviceInstanceId) {
        this.serviceInstanceId = serviceInstanceId;
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig deploymentEnvironment(String deploymentEnvironment) {
        this.deploymentEnvironment = deploymentEnvironment;
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig addResourceAttribute(String key, String value) {
        resourceAttributes.put(key, value);
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig jmxBridgeEnabled(boolean jmxBridgeEnabled) {
        this.jmxBridgeEnabled = jmxBridgeEnabled;
        return this;
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
     * @return this configuration
     */
    public TelemetryConfig includeExceptionDetails(boolean includeExceptionDetails) {
        this.includeExceptionDetails = includeExceptionDetails;
        return this;
    }

    /**
     * Returns the JMX bridge instance, or null if not registered.
     */
    public TelemetryJMXBridge getJmxBridge() {
        return jmxBridge;
    }

    /**
     * Initializes the telemetry configuration, after its own settings
     * and those of every exporter have been made and the exporter tree is
     * composed. Each exporter in the tree is started: it reads its
     * settings and the identity of the service, and opens whatever
     * threads, files and connections it needs.
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
        initialised = true;
        exporter.init(this);
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
     * when it shuts down. Set the settings of each exporter before
     * {@link #init()}, which starts them; an exporter set after it has
     * run is started at once.
     *
     * @param exporter the exporter
     * @return this configuration
     */
    public TelemetryConfig exporter(TelemetryExporter exporter) {
        if (exporter == null) {
            throw new IllegalArgumentException("exporter");
        }
        this.exporter = exporter;
        if (initialised) {
            exporter.init(this);
        }
        return this;
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
     * <p>A class that takes its messages from more than one bundle has a
     * logger for each, since a key means nothing without its bundle.
     *
     * @param scope the emitting class
     * @param bundle the class's resource bundle
     * @return the logger
     */
    public EventLogger getLogger(Class<?> scope, ResourceBundle bundle) {
        String name = scope.getName();
        String key = name + '|' + bundle.getBaseBundleName();
        EventLogger events = loggers.get(key);
        if (events == null) {
            events = new EventLogger(this, name, bundle);
            EventLogger existing = loggers.putIfAbsent(key, events);
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
        sb.append(", exporter=").append(exporter.getClass().getSimpleName());
        sb.append(", metrics=").append(metricsEnabled);
        sb.append("]");
        return sb.toString();
    }

}

