/*
 * HttpAccessLog.java
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

package org.bluezoo.gumdrop.telemetry.access;

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.Principal;

/**
 * Emits one {@link LogLevel#ACCESS} record per completed HTTP request.
 * The record's event name is {@link #EVENT_NAME}, its attributes are
 * the access fields named here, and it carries the request span when
 * there is one. Every exporter that accepts the level receives it: an
 * {@link AccessLogExporter} writes the CLF or ELFF line, OTLP or JSONL
 * exports the record as it is.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HttpAccessLog {

    /** The event name of an access record. */
    public static final String EVENT_NAME = "http.server.request";

    /** The client host, as an address or a name. */
    public static final String CLIENT_ADDRESS = "client.address";

    /** The request method. */
    public static final String METHOD = "http.request.method";

    /** The request target, as it appeared in the request. */
    public static final String TARGET = "http.request.target";

    /** The protocol version, such as {@code HTTP/1.1}. */
    public static final String PROTOCOL_VERSION = "network.protocol.version";

    /** The response status code. */
    public static final String STATUS_CODE = "http.response.status_code";

    /** The number of response body bytes sent. */
    public static final String RESPONSE_BYTES = "http.response.body.size";

    /** The user the protocol authenticated, such as a TLS client certificate's. */
    public static final String PROTOCOL_USER = "enduser.id";

    /** The user the application authenticated. */
    public static final String APPLICATION_USER = "gumdrop.application.user";

    private HttpAccessLog() {
    }

    /**
     * Builds the access record for a completed request and hands it to
     * the exporter, when any exporter accepts access records.
     *
     * @param config the telemetry configuration, or null for none
     * @param span the request span, or null for none
     * @param timeEpochMillis when the request completed
     * @param remoteAddress the client's address
     * @param method the request method
     * @param requestTarget the request target
     * @param protocolVersion the protocol version
     * @param protocolPrincipal the user the protocol authenticated, or null
     * @param applicationPrincipal the user the application authenticated, or null
     * @param statusCode the response status
     * @param responseBytes the response body size
     */
    public static void record(TelemetryConfig config, Span span, long timeEpochMillis,
            SocketAddress remoteAddress, String method, String requestTarget,
            String protocolVersion, Principal protocolPrincipal,
            Principal applicationPrincipal, int statusCode, long responseBytes) {
        if (config == null || !config.accepts(LogLevel.ACCESS)) {
            return;
        }
        config.getExporter().export(toRecord(span, timeEpochMillis, remoteAddress, method,
                requestTarget, protocolVersion, protocolPrincipal, applicationPrincipal,
                statusCode, responseBytes));
    }

    /**
     * Builds the access record for a completed request.
     *
     * @return the record
     * @see #record
     */
    public static LogRecord toRecord(Span span, long timeEpochMillis,
            SocketAddress remoteAddress, String method, String requestTarget,
            String protocolVersion, Principal protocolPrincipal,
            Principal applicationPrincipal, int statusCode, long responseBytes) {
        LogRecord record = new LogRecord(timeEpochMillis * 1_000_000L, LogLevel.ACCESS, EVENT_NAME);
        record.span(span);
        record.attr(CLIENT_ADDRESS, clientHost(remoteAddress));
        if (method != null) {
            record.attr(METHOD, method);
        }
        if (requestTarget != null) {
            record.attr(TARGET, requestTarget);
        }
        if (protocolVersion != null) {
            record.attr(PROTOCOL_VERSION, protocolVersion);
        }
        record.attr(STATUS_CODE, statusCode);
        record.attr(RESPONSE_BYTES, responseBytes);
        String protocolUser = nameOf(protocolPrincipal);
        if (protocolUser != null) {
            record.attr(PROTOCOL_USER, protocolUser);
        }
        String applicationUser = nameOf(applicationPrincipal);
        if (applicationUser != null) {
            record.attr(APPLICATION_USER, applicationUser);
        }
        return record;
    }

    private static String clientHost(SocketAddress remoteAddress) {
        if (remoteAddress instanceof InetSocketAddress) {
            InetSocketAddress inet = (InetSocketAddress) remoteAddress;
            String host = inet.getHostString();
            if (host != null && !host.isEmpty()) {
                return host;
            }
        }
        return remoteAddress != null ? remoteAddress.toString() : "-";
    }

    private static String nameOf(Principal principal) {
        if (principal == null) {
            return null;
        }
        String name = principal.getName();
        return name == null || name.isEmpty() ? null : name;
    }
}
