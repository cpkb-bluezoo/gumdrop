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
 * OpenTelemetry implementation: distributed tracing, metrics, and log
 * correlation, exported via OTLP to any OpenTelemetry Collector, with no
 * dependency on the official OpenTelemetry SDK.
 *
 * <p>{@link org.bluezoo.gumdrop.telemetry.TelemetryConfig} is the entry
 * point, creating {@link org.bluezoo.gumdrop.telemetry.Trace}s (each a
 * tree of {@link org.bluezoo.gumdrop.telemetry.Span}s, with W3C Trace
 * Context propagation) and {@code Meter}s for metric instruments ({@link
 * org.bluezoo.gumdrop.telemetry.metrics}). Every protocol server
 * (HTTP, SMTP, IMAP, POP3, FTP, and more) instruments itself
 * automatically -- each has a dedicated {@code *ServerMetrics} class --
 * once telemetry is configured with an OTLP endpoint; HTTPS is strongly
 * recommended for that endpoint to protect telemetry data in transit.
 * {@link org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory} is the
 * SPI other export formats plug into; OTLP/HTTP, OTLP/gRPC, and JSONL
 * exporters ship in the optional {@code gumdrop-telemetry.jar}, loaded
 * via {@link java.util.ServiceLoader}.
 *
 * <p>When metrics are enabled, {@link
 * org.bluezoo.gumdrop.telemetry.TelemetryJMXBridge} also exposes them
 * under {@code org.bluezoo.gumdrop:type=Telemetry} for JMX-based tools
 * (jconsole, VisualVM, the Prometheus JMX exporter), reading from the
 * same OpenTelemetry state on each attribute access rather than
 * maintaining a separate copy.
 *
 * <h2>Subpackages</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.metrics} - metric instrument types</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.otlp} - OTLP/HTTP and OTLP/gRPC export</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.json} - OTLP JSON Lines file/stdout export</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.protobuf} - the Protocol Buffers codec OTLP export uses</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.metrics
 */
package org.bluezoo.gumdrop.telemetry;
