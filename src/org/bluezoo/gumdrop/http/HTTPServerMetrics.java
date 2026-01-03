/*
 * HTTPServerMetrics.java
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for HTTP servers.
 * 
 * <p>This class provides standardized HTTP server metrics following the
 * OpenTelemetry semantic conventions for HTTP.
 * 
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code http.server.requests} - Total number of HTTP requests received</li>
 *   <li>{@code http.server.active_requests} - Number of requests currently being processed</li>
 *   <li>{@code http.server.request.duration} - Duration of HTTP requests in milliseconds</li>
 *   <li>{@code http.server.request.size} - Size of HTTP request bodies in bytes</li>
 *   <li>{@code http.server.response.size} - Size of HTTP response bodies in bytes</li>
 *   <li>{@code http.server.active_connections} - Number of active connections</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-metrics/">HTTP Metrics Semantic Conventions</a>
 */
public class HTTPServerMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.http";

    // Synchronous counters
    private final LongCounter requestCounter;
    private final LongUpDownCounter activeRequests;
    private final LongUpDownCounter activeConnections;

    // Histograms
    private final DoubleHistogram requestDuration;
    private final DoubleHistogram requestSize;
    private final DoubleHistogram responseSize;

    /**
     * Creates HTTP server metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public HTTPServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);

        // Request counter - total requests
        this.requestCounter = meter.counterBuilder("http.server.requests")
                .setDescription("Total number of HTTP requests received")
                .setUnit("requests")
                .build();

        // Active requests gauge (using up-down counter)
        this.activeRequests = meter.upDownCounterBuilder("http.server.active_requests")
                .setDescription("Number of HTTP requests currently being processed")
                .setUnit("requests")
                .build();

        // Active connections
        this.activeConnections = meter.upDownCounterBuilder("http.server.active_connections")
                .setDescription("Number of active HTTP connections")
                .setUnit("connections")
                .build();

        // Request duration histogram with bucket boundaries for latency
        this.requestDuration = meter.histogramBuilder("http.server.request.duration")
                .setDescription("Duration of HTTP server requests")
                .setUnit("ms")
                .setExplicitBuckets(1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000)
                .build();

        // Request body size histogram
        this.requestSize = meter.histogramBuilder("http.server.request.size")
                .setDescription("Size of HTTP request bodies")
                .setUnit("bytes")
                .setExplicitBuckets(100, 1000, 10000, 100000, 1000000, 10000000)
                .build();

        // Response body size histogram
        this.responseSize = meter.histogramBuilder("http.server.response.size")
                .setDescription("Size of HTTP response bodies")
                .setUnit("bytes")
                .setExplicitBuckets(100, 1000, 10000, 100000, 1000000, 10000000)
                .build();
    }

    /**
     * Records the start of an HTTP request.
     * Call this when a request begins processing.
     *
     * @param method the HTTP method (GET, POST, etc.)
     */
    public void requestStarted(String method) {
        activeRequests.add(1, Attributes.of("http.method", method));
    }

    /**
     * Records the completion of an HTTP request.
     * Call this when a request finishes processing.
     *
     * @param method the HTTP method
     * @param statusCode the HTTP response status code
     * @param durationMs the request duration in milliseconds
     * @param requestBytes the request body size in bytes
     * @param responseBytes the response body size in bytes
     */
    public void requestCompleted(String method, int statusCode, 
                                  double durationMs, long requestBytes, long responseBytes) {
        Attributes attrs = Attributes.of(
                "http.method", method,
                "http.status_code", statusCode
        );

        // Decrement active requests
        activeRequests.add(-1, Attributes.of("http.method", method));

        // Increment total request count
        requestCounter.add(1, attrs);

        // Record duration
        requestDuration.record(durationMs, attrs);

        // Record sizes if non-zero
        if (requestBytes > 0) {
            requestSize.record(requestBytes, Attributes.of("http.method", method));
        }
        if (responseBytes > 0) {
            responseSize.record(responseBytes, attrs);
        }
    }

    /**
     * Records the opening of an HTTP connection.
     */
    public void connectionOpened() {
        activeConnections.add(1);
    }

    /**
     * Records the closing of an HTTP connection.
     */
    public void connectionClosed() {
        activeConnections.add(-1);
    }

    /**
     * Records an HTTP error (request that did not complete normally).
     *
     * @param method the HTTP method
     * @param statusCode the error status code
     */
    public void requestError(String method, int statusCode) {
        Attributes attrs = Attributes.of(
                "http.method", method != null ? method : "UNKNOWN",
                "http.status_code", statusCode,
                "error", true
        );
        requestCounter.add(1, attrs);
        activeRequests.add(-1, Attributes.of("http.method", method != null ? method : "UNKNOWN"));
    }

}

