/*
 * package-info.java
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

/**
 * OTLP JSON serialization and file export for OpenTelemetry data.
 *
 * <p>This package implements the
 * <a href="https://opentelemetry.io/docs/specs/otel/protocol/file-exporter/">
 * OpenTelemetry Protocol File Exporter</a> specification. It provides JSON
 * serializers for traces, logs, and metrics using the OTLP JSON Protobuf
 * encoding, and a file exporter that writes OTLP JSON Lines ({@code .jsonl})
 * output to files or stdout.
 *
 * <p>The serializers use the
 * <a href="https://github.com/cpkb-bluezoo/jsonparser">jsonparser</a>
 * library's streaming {@code JSONWriter} for efficient, buffered output.
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.json.OTLPFileExporter} -
 *       File/stdout exporter implementing {@code TelemetryExporter}</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.json.TraceJsonSerializer} -
 *       Serializes traces to OTLP JSON</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.json.LogJsonSerializer} -
 *       Serializes logs to OTLP JSON</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.json.MetricJsonSerializer} -
 *       Serializes metrics to OTLP JSON</li>
 * </ul>
 *
 * @see org.bluezoo.gumdrop.telemetry.TelemetryExporter
 * @see org.bluezoo.gumdrop.telemetry.TelemetryConfig
 */
package org.bluezoo.gumdrop.telemetry.json;
