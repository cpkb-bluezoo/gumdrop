/*
 * HttpAccessLogFormatter.java
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats {@link HttpAccessRecord} as CLF or ELFF lines.
 */
public final class HttpAccessLogFormatter {

    private static final DateTimeFormatter CLF_DATE =
            DateTimeFormatter.ofPattern("'['dd/MMM/yyyy:HH:mm:ss Z']'")
                    .withZone(ZoneId.systemDefault());

    private final AccessLogFormat format;
    private final AccessLogUserSelection userSelection;

    public HttpAccessLogFormatter(AccessLogFormat format,
            AccessLogUserSelection userSelection) {
        this.format = format;
        this.userSelection = userSelection;
    }

    public String[] preambleLines() {
        if (format != AccessLogFormat.ELFF) {
            return new String[0];
        }
        if (userSelection == AccessLogUserSelection.BOTH) {
            return new String[] {
                "#Version: 1.0",
                "#Fields: date time c-ip cs-method cs-uri-stem sc-status sc-bytes "
                        + "cs-username x-gumdrop-app-user cs-version"
            };
        }
        return new String[] {
            "#Version: 1.0",
            "#Fields: date time c-ip cs-method cs-uri-stem sc-status sc-bytes "
                    + "cs-username cs-version"
        };
    }

    public String formatLine(HttpAccessRecord record) {
        if (format == AccessLogFormat.ELFF) {
            return formatElff(record);
        }
        return formatClf(record);
    }

    private String formatClf(HttpAccessRecord record) {
        String user = selectClfUser(record);
        String date = CLF_DATE.format(Instant.ofEpochMilli(record.getTimeEpochMillis()));
        String requestLine = formatRequestLine(record);
        StringBuilder buf = new StringBuilder();
        buf.append(safeToken(record.getClientHost()));
        buf.append(' ');
        buf.append('-'); // rfc931 ident
        buf.append(' ');
        buf.append(user == null ? "-" : user);
        buf.append(' ');
        buf.append(date);
        buf.append(' ');
        buf.append('"');
        buf.append(requestLine);
        buf.append('"');
        buf.append(' ');
        buf.append(String.format("%03d", record.getStatusCode()));
        buf.append(' ');
        buf.append(Long.toString(record.getResponseBytes()));
        return buf.toString();
    }

    private String formatElff(HttpAccessRecord record) {
        Instant instant = Instant.ofEpochMilli(record.getTimeEpochMillis());
        String date = DateTimeFormatter.ISO_LOCAL_DATE.format(instant.atZone(ZoneId.systemDefault()));
        String time = DateTimeFormatter.ofPattern("HH:mm:ss").format(instant.atZone(ZoneId.systemDefault()));
        String clfUser = selectClfUser(record);
        StringBuilder buf = new StringBuilder();
        buf.append(date);
        buf.append('\t');
        buf.append(time);
        buf.append('\t');
        buf.append(safeToken(record.getClientHost()));
        buf.append('\t');
        buf.append(safeToken(record.getMethod()));
        buf.append('\t');
        buf.append(safeToken(record.getRequestTarget()));
        buf.append('\t');
        buf.append(record.getStatusCode());
        buf.append('\t');
        buf.append(record.getResponseBytes());
        buf.append('\t');
        buf.append(clfUser == null ? "-" : clfUser);
        if (userSelection == AccessLogUserSelection.BOTH) {
            buf.append('\t');
            String app = record.getApplicationUser();
            buf.append(app == null || app.isEmpty() ? "-" : app);
        }
        buf.append('\t');
        buf.append(safeToken(record.getProtocolVersion()));
        return buf.toString();
    }

    private String formatRequestLine(HttpAccessRecord record) {
        String method = record.getMethod() != null ? record.getMethod() : "-";
        String target = record.getRequestTarget() != null ? record.getRequestTarget() : "-";
        String version = record.getProtocolVersion() != null ? record.getProtocolVersion() : "HTTP/1.1";
        return method + ' ' + target + ' ' + version;
    }

    String selectClfUser(HttpAccessRecord record) {
        String protocol = emptyToNull(record.getProtocolUser());
        String application = emptyToNull(record.getApplicationUser());
        switch (userSelection) {
            case PROTOCOL:
                return protocol;
            case APPLICATION:
                return application;
            case APPLICATION_FIRST:
                return application != null ? application : protocol;
            case BOTH:
                return protocol != null ? protocol : application;
            case PROTOCOL_FIRST:
            default:
                return protocol != null ? protocol : application;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String safeToken(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value;
    }
}
