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
 * OTEL SPI implementation.
 *
 * <p>{@link org.bluezoo.gumdrop.telemetry.export.DefaultTelemetryExporterFactory}
 * picks OTLP/HTTP, OTLP/gRPC ({@link org.bluezoo.gumdrop.telemetry.otlp}),
 * or JSONL file export ({@link org.bluezoo.gumdrop.telemetry.json})
 * according to {@link org.bluezoo.gumdrop.telemetry.TelemetryConfig}'s
 * configured exporter type, or returns {@code null} when no export is
 * configured.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.TelemetryExporterFactory
 */
package org.bluezoo.gumdrop.telemetry.export;
