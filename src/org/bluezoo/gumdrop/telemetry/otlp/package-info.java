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
 * OTLP export of traces, metrics, and logs to an OpenTelemetry Collector,
 * over HTTP or gRPC.
 *
 * <p>{@link org.bluezoo.gumdrop.telemetry.otlp.OTLPExporter} sends
 * OTLP/HTTP requests (protobuf-encoded, via {@link
 * org.bluezoo.gumdrop.telemetry.protobuf}) through {@link
 * org.bluezoo.gumdrop.telemetry.otlp.OTLPEndpoint}, HTTP/2 or HTTP/1.1
 * with ALPN negotiation and connection pooling keyed to a {@code
 * SelectorLoop}. {@link org.bluezoo.gumdrop.telemetry.otlp.OTLPGrpcExporter}
 * and {@link org.bluezoo.gumdrop.telemetry.otlp.OTLPGrpcEndpoint} are the
 * OTLP/gRPC equivalents, framing the same protobuf payloads per the gRPC
 * wire format instead. Both implement {@link
 * org.bluezoo.gumdrop.telemetry.TelemetryExporter}, loaded via the
 * {@link org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory} SPI
 * from the optional {@code gumdrop-telemetry.jar}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.TelemetryConfig
 * @see org.bluezoo.gumdrop.telemetry.protobuf
 */
package org.bluezoo.gumdrop.telemetry.otlp;
