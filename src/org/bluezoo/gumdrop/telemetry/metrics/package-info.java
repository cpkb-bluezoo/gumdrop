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
 * OpenTelemetry Metrics instrument types.
 *
 * <p>Synchronous instruments, recorded at measurement time: {@link
 * org.bluezoo.gumdrop.telemetry.metrics.LongCounter} (monotonic), {@link
 * org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter}
 * (bidirectional), {@link
 * org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram} (value
 * distributions, with configurable explicit buckets). Asynchronous
 * instruments, invoked via callback at collection time: {@link
 * org.bluezoo.gumdrop.telemetry.metrics.ObservableGauge}, {@link
 * org.bluezoo.gumdrop.telemetry.metrics.ObservableCounter}, {@link
 * org.bluezoo.gumdrop.telemetry.metrics.ObservableUpDownCounter}.
 *
 * <p>Metrics export with either DELTA temporality (value since last
 * export, for push-based/stateless collectors) or CUMULATIVE (value
 * since process start, for Prometheus-style scrapers), configured on
 * {@link org.bluezoo.gumdrop.telemetry.TelemetryConfig}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.TelemetryConfig
 * @see org.bluezoo.gumdrop.telemetry.otlp
 */
package org.bluezoo.gumdrop.telemetry.metrics;
