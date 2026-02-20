/*
 * package-info.java
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

/**
 * OpenTelemetry implementation for Gumdrop.
 *
 * <p>This package provides a lightweight, native implementation of OpenTelemetry
 * for distributed tracing, metrics, and log correlation. It integrates with
 * Gumdrop's event-driven architecture and exports data via OTLP/HTTP to any
 * OpenTelemetry Collector.
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Distributed tracing with W3C Trace Context propagation
 *   <li>Metrics collection (counters, histograms, gauges)
 *   <li>Hierarchical spans with attributes, events, and status
 *   <li>Zero-dependency protobuf serialization
 *   <li>Native HTTP client for non-blocking OTLP export
 *   <li>HTTPS/TLS support with configurable truststore for secure export
 *   <li>HTTP/2 and HTTP/1.1 with ALPN negotiation
 *   <li>Connection pooling with SelectorLoop affinity
 * </ul>
 *
 * <h2>Tracing</h2>
 *
 * <p>The tracing API provides distributed request tracing across services:
 *
 * <pre>
 * // Create a trace
 * TelemetryConfig config = server.getTelemetryConfig();
 * Trace trace = config.createTrace("Process request", SpanKind.SERVER);
 *
 * // Add attributes
 * trace.addAttribute("request.id", requestId);
 *
 * // Create child spans for sub-operations
 * Span childSpan = trace.startSpan("Database query", SpanKind.CLIENT);
 * try {
 *     // ... operation ...
 *     childSpan.setStatusOk();
 * } catch (Exception e) {
 *     childSpan.recordException(e);
 * } finally {
 *     childSpan.end();
 * }
 *
 * trace.end(); // Exports automatically
 * </pre>
 *
 * <h2>Metrics</h2>
 *
 * <p>The metrics API provides collection of numeric measurements:
 *
 * <pre>
 * // Get a meter
 * Meter meter = config.getMeter("org.bluezoo.gumdrop.http");
 *
 * // Create instruments
 * LongCounter requests = meter.counterBuilder("http.requests")
 *     .setDescription("Total HTTP requests")
 *     .build();
 *
 * DoubleHistogram latency = meter.histogramBuilder("http.duration")
 *     .setDescription("Request latency")
 *     .setUnit("ms")
 *     .build();
 *
 * // Record measurements
 * requests.add(1, Attributes.of("method", "GET"));
 * latency.record(45.2, Attributes.of("method", "GET"));
 *
 * // Observable gauges for current state
 * meter.gaugeBuilder("http.connections.active")
 *     .buildWithCallback(new ObservableCallback() {
 *         public void observe(ObservableMeasurement m) {
 *             m.record(server.getActiveEndpointCount());
 *         }
 *     });
 * </pre>
 *
 * <h3>Instrument Types</h3>
 *
 * <ul>
 *   <li><b>LongCounter</b> - Monotonically increasing counter (requests, bytes)
 *   <li><b>LongUpDownCounter</b> - Bidirectional counter (active connections)
 *   <li><b>DoubleHistogram</b> - Distribution of values (latency, size)
 *   <li><b>ObservableGauge</b> - Point-in-time value via callback (memory, CPU)
 *   <li><b>ObservableCounter</b> - Async monotonic counter (system metrics)
 *   <li><b>ObservableUpDownCounter</b> - Async bidirectional counter
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>Configure telemetry via the DI framework in gumdroprc. <b>HTTPS is
 * strongly recommended</b> for OTLP export to protect telemetry data in
 * transit:
 *
 * <pre>
 * &lt;component id="telemetry" class="org.bluezoo.gumdrop.telemetry.TelemetryConfig"&gt;
 *     &lt;property name="service-name"&gt;my-service&lt;/property&gt;
 *     
 *     &lt;!-- OTLP endpoint - use HTTPS in production --&gt;
 *     &lt;property name="endpoint"&gt;https://otel-collector:4318&lt;/property&gt;
 *     
 *     &lt;!-- TLS configuration for HTTPS endpoints --&gt;
 *     &lt;property name="truststore-file"&gt;/etc/gumdrop/otlp-truststore.p12&lt;/property&gt;
 *     &lt;property name="truststore-pass"&gt;changeit&lt;/property&gt;
 *     
 *     &lt;!-- Metrics configuration --&gt;
 *     &lt;property name="metrics-enabled"&gt;true&lt;/property&gt;
 *     &lt;property name="metrics-temporality-name"&gt;cumulative&lt;/property&gt;
 *     &lt;property name="metrics-interval-ms"&gt;60000&lt;/property&gt;
 * &lt;/component&gt;
 * </pre>
 *
 * <p>The truststore should contain the CA certificate(s) that signed the
 * OpenTelemetry Collector's TLS certificate. Create with keytool:
 *
 * <pre>
 * keytool -importcert -alias otel-ca -file ca-cert.pem \
 *     -keystore otlp-truststore.p12 -storetype PKCS12 -storepass changeit
 * </pre>
 *
 * <h2>Built-in Instrumentation</h2>
 *
 * <p>Gumdrop provides automatic instrumentation for:
 * <ul>
 *   <li>HTTP server - request spans and metrics (requests, connections, duration, size)
 *   <li>SMTP server - connection spans and metrics (messages, authentication, sessions)
 *   <li>IMAP server - session spans and metrics (commands, messages, authentication)
 *   <li>POP3 server - session spans and metrics (messages, authentication, bytes)
 *   <li>FTP server - session spans and metrics (transfers, authentication, commands)
 * </ul>
 *
 * <p>Each server type has a dedicated metrics class (e.g., {@code HTTPServerMetrics},
 * {@code SMTPServerMetrics}) that collects OpenTelemetry-compatible metrics automatically
 * when telemetry is enabled.
 *
 * <h2>Core Classes</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.TelemetryConfig} - Configuration and factory
 *   <li>{@link org.bluezoo.gumdrop.telemetry.Trace} - Distributed trace container
 *   <li>{@link org.bluezoo.gumdrop.telemetry.Span} - Unit of work with timing and attributes
 *   <li>{@link org.bluezoo.gumdrop.telemetry.Attribute} - Key-value span metadata
 *   <li>{@link org.bluezoo.gumdrop.telemetry.SpanKind} - Categorises span role
 *   <li>{@link org.bluezoo.gumdrop.telemetry.SpanStatus} - Operation outcome
 *   <li>{@link org.bluezoo.gumdrop.telemetry.OTLPExporter} - OTLP/HTTP exporter
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.metrics
 */
package org.bluezoo.gumdrop.telemetry;

