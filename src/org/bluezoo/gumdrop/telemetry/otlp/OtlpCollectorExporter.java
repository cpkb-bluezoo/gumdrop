/*
 * OtlpCollectorExporter.java
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


package org.bluezoo.gumdrop.telemetry.otlp;

import org.bluezoo.gumdrop.telemetry.BatchingExporter;
import org.bluezoo.gumdrop.tls.TlsConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The settings of an exporter that sends to an OpenTelemetry Collector:
 * where each signal goes, the headers that go with it, how long to wait,
 * and the TLS configuration for HTTPS endpoints. Shared by {@link
 * OtlpExporter} (OTLP/HTTP) and {@link OtlpGrpcExporter} (OTLP/gRPC).
 *
 * <p>The settings are made on the exporter, before {@link
 * org.bluezoo.gumdrop.telemetry.TelemetryConfig#init()} starts it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class OtlpCollectorExporter extends BatchingExporter {

    private String endpoint;
    private String tracesEndpoint;
    private String logsEndpoint;
    private String metricsEndpoint;
    private String headers;
    private int timeoutMs = 10000;
    private TlsConfig tls;

    /**
     * Returns the base endpoint URL.
     *
     * @return the endpoint, or null if none is set
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the base endpoint URL, from which each signal's endpoint is
     * derived unless it is set itself.
     *
     * @param endpoint the endpoint URL, for example {@code https://collector:4318}
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Returns the traces endpoint: the one set, or the base endpoint with
     * {@code /v1/traces}.
     *
     * @return the endpoint URL, or null if neither is set
     */
    public String getTracesEndpoint() {
        return derive(tracesEndpoint, "/v1/traces");
    }

    /**
     * Sets an endpoint for traces alone.
     *
     * @param tracesEndpoint the endpoint URL
     */
    public void setTracesEndpoint(String tracesEndpoint) {
        this.tracesEndpoint = tracesEndpoint;
    }

    /**
     * Returns the logs endpoint: the one set, or the base endpoint with
     * {@code /v1/logs}.
     *
     * @return the endpoint URL, or null if neither is set
     */
    public String getLogsEndpoint() {
        return derive(logsEndpoint, "/v1/logs");
    }

    /**
     * Sets an endpoint for log records alone.
     *
     * @param logsEndpoint the endpoint URL
     */
    public void setLogsEndpoint(String logsEndpoint) {
        this.logsEndpoint = logsEndpoint;
    }

    /**
     * Returns the metrics endpoint: the one set, or the base endpoint with
     * {@code /v1/metrics}.
     *
     * @return the endpoint URL, or null if neither is set
     */
    public String getMetricsEndpoint() {
        return derive(metricsEndpoint, "/v1/metrics");
    }

    /**
     * Sets an endpoint for metrics alone.
     *
     * @param metricsEndpoint the endpoint URL
     */
    public void setMetricsEndpoint(String metricsEndpoint) {
        this.metricsEndpoint = metricsEndpoint;
    }

    private String derive(String specific, String path) {
        if (specific != null) {
            return specific;
        }
        if (endpoint != null) {
            return endpoint + path;
        }
        return null;
    }

    /**
     * Returns the extra headers sent with export requests.
     *
     * @return the headers as set, or null
     */
    public String getHeaders() {
        return headers;
    }

    /**
     * Sets extra headers to send with export requests, usually for
     * authentication. The format is {@code key1=value1,key2=value2}.
     *
     * @param headers the headers
     */
    public void setHeaders(String headers) {
        this.headers = headers;
    }

    /**
     * Parses the headers into a map.
     *
     * @return an unmodifiable map of header names to values
     */
    Map<String, String> parsedHeaders() {
        Map<String, String> result = new HashMap<String, String>();
        if (headers != null && headers.length() > 0) {
            int start = 0;
            int length = headers.length();
            while (start <= length) {
                int end = headers.indexOf(',', start);
                if (end < 0) {
                    end = length;
                }
                String pair = headers.substring(start, end);
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    result.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                }
                start = end + 1;
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the export timeout in milliseconds.
     *
     * @return the timeout
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Sets how long, in milliseconds, a flush or shutdown waits for
     * exports in flight. The default is 10000.
     *
     * @param timeoutMs the timeout
     */
    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns the TLS configuration used for HTTPS endpoints.
     *
     * @return the configuration, or null for the JVM defaults
     */
    public TlsConfig getTls() {
        return tls;
    }

    /**
     * Sets the TLS configuration used for HTTPS endpoints: the trust
     * manager that verifies the collector's certificate and, for mutual
     * TLS, the identity this exporter presents. With none set, the JVM's
     * default trust store is used.
     *
     * @param tls the configuration
     */
    public void setTls(TlsConfig tls) {
        this.tls = tls;
    }

}
