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
 * OpenTelemetry Metrics implementation for Gumdrop.
 *
 * <p>This package provides a lightweight implementation of OpenTelemetry Metrics,
 * enabling collection and export of metric data from Gumdrop servers and applications.
 *
 * <h2>Instrument Types</h2>
 *
 * <p><b>Synchronous Instruments</b> - recorded at measurement time:
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.metrics.LongCounter} - monotonically increasing counter
 *   <li>{@link org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter} - bidirectional counter
 *   <li>{@link org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram} - distribution of values
 * </ul>
 *
 * <p><b>Asynchronous Instruments</b> - callback-based, invoked at collection time:
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.metrics.ObservableGauge} - point-in-time value
 *   <li>{@link org.bluezoo.gumdrop.telemetry.metrics.ObservableCounter} - async monotonic counter
 *   <li>{@link org.bluezoo.gumdrop.telemetry.metrics.ObservableUpDownCounter} - async bidirectional counter
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * // Get a meter from TelemetryConfig
 * Meter meter = telemetryConfig.getMeter("org.bluezoo.gumdrop.http");
 *
 * // Create a counter
 * LongCounter requestCounter = meter.counterBuilder("http.server.requests")
 *     .setDescription("Total HTTP requests received")
 *     .setUnit("requests")
 *     .build();
 *
 * // Create a histogram for latency
 * DoubleHistogram latencyHistogram = meter.histogramBuilder("http.server.duration")
 *     .setDescription("HTTP request latency")
 *     .setUnit("ms")
 *     .setExplicitBuckets(5, 10, 25, 50, 100, 250, 500, 1000)
 *     .build();
 *
 * // Create an observable gauge
 * meter.gaugeBuilder("http.server.active_connections")
 *     .setDescription("Currently active connections")
 *     .buildWithCallback(new ObservableCallback() {
 *         public void observe(ObservableMeasurement measurement) {
 *             measurement.record(server.getActiveEndpointCount());
 *         }
 *     });
 *
 * // Record measurements in request handling
 * requestCounter.add(1, Attributes.of("http.method", "GET", "http.status_code", 200));
 * latencyHistogram.record(45.2, Attributes.of("http.method", "GET"));
 * </pre>
 *
 * <h2>Aggregation Temporality</h2>
 *
 * <p>Metrics can be exported with different temporalities:
 * <ul>
 *   <li><b>DELTA</b> - Values represent change since last export. Preferred for push-based
 *       systems and stateless collectors.
 *   <li><b>CUMULATIVE</b> - Values represent total since process start. Preferred for
 *       Prometheus-style scrapers and pull-based systems.
 * </ul>
 *
 * <p>Configure the temporality in the TelemetryConfig:
 * <pre>
 * &lt;property name="metrics-temporality-name"&gt;delta&lt;/property&gt;
 * </pre>
 *
 * <h2>Export</h2>
 *
 * <p>Metrics are automatically collected and exported via the OTLP exporter at the
 * configured interval (default: 60 seconds). The export endpoint can be configured:
 * <pre>
 * &lt;property name="metrics-endpoint"&gt;http://localhost:4318/v1/metrics&lt;/property&gt;
 * &lt;property name="metrics-interval-ms"&gt;30000&lt;/property&gt;
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.TelemetryConfig
 * @see org.bluezoo.gumdrop.telemetry.OTLPExporter
 */
package org.bluezoo.gumdrop.telemetry.metrics;

