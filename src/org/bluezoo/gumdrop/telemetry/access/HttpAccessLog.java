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

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.Principal;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records completed HTTP requests to the configured access log file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HttpAccessLog {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    private static final Logger LOGGER = Logger.getLogger(HttpAccessLog.class.getName());

    private HttpAccessLog() {
    }

    public static void record(TelemetryConfig config, long timeEpochMillis,
            SocketAddress remoteAddress, String method, String requestTarget,
            String protocolVersion, Principal protocolPrincipal,
            Principal applicationPrincipal, int statusCode, long responseBytes) {
        if (config == null) {
            return;
        }
        HttpAccessLogWriter writer = config.getAccessLogWriter();
        if (writer == null) {
            return;
        }
        String clientHost = clientHost(remoteAddress);
        String protocolUser = nameOf(protocolPrincipal);
        String applicationUser = nameOf(applicationPrincipal);
        HttpAccessRecord record = new HttpAccessRecord(
                timeEpochMillis,
                clientHost,
                protocolUser,
                applicationUser,
                method,
                requestTarget,
                protocolVersion,
                statusCode,
                responseBytes);
        try {
            writer.write(record);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    MessageFormat.format(L10N.getString("err.access_log_write_failed"),
                            e.getMessage()), e);
        }
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
        return principal != null ? principal.getName() : null;
    }
}
