/*
 * HttpAccessRecord.java
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

/**
 * One completed HTTP request for access logging.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HttpAccessRecord {

    private final long timeEpochMillis;
    private final String clientHost;
    private final String protocolUser;
    private final String applicationUser;
    private final String method;
    private final String requestTarget;
    private final String protocolVersion;
    private final int statusCode;
    private final long responseBytes;

    public HttpAccessRecord(long timeEpochMillis, String clientHost,
            String protocolUser, String applicationUser,
            String method, String requestTarget, String protocolVersion,
            int statusCode, long responseBytes) {
        this.timeEpochMillis = timeEpochMillis;
        this.clientHost = clientHost;
        this.protocolUser = protocolUser;
        this.applicationUser = applicationUser;
        this.method = method;
        this.requestTarget = requestTarget;
        this.protocolVersion = protocolVersion;
        this.statusCode = statusCode;
        this.responseBytes = responseBytes;
    }

    public long getTimeEpochMillis() {
        return timeEpochMillis;
    }

    public String getClientHost() {
        return clientHost;
    }

    public String getProtocolUser() {
        return protocolUser;
    }

    public String getApplicationUser() {
        return applicationUser;
    }

    public String getMethod() {
        return method;
    }

    public String getRequestTarget() {
        return requestTarget;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public long getResponseBytes() {
        return responseBytes;
    }
}
