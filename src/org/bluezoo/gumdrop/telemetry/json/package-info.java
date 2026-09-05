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
 * OTLP JSON Lines ({@code .jsonl}) export to a file or stdout,
 * implementing the OpenTelemetry Protocol
 * <a href="https://opentelemetry.io/docs/specs/otel/protocol/file-exporter/">File Exporter</a>
 * specification.
 *
 * <p>{@link org.bluezoo.gumdrop.telemetry.json.OTLPFileExporter}
 * implements {@link org.bluezoo.gumdrop.telemetry.TelemetryExporter};
 * {@link org.bluezoo.gumdrop.telemetry.json.TraceJsonSerializer}, {@link
 * org.bluezoo.gumdrop.telemetry.json.LogJsonSerializer}, and {@link
 * org.bluezoo.gumdrop.telemetry.json.MetricJsonSerializer} render
 * traces, logs, and metrics respectively into OTLP's JSON-Protobuf
 * encoding, streamed via the
 * <a href="https://github.com/cpkb-bluezoo/jsonparser">jsonparser</a>
 * library's {@code JSONWriter}.
 *
 * @see org.bluezoo.gumdrop.telemetry.TelemetryExporter
 * @see org.bluezoo.gumdrop.telemetry.TelemetryConfig
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.telemetry.json;
