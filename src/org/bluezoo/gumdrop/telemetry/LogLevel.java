/*
 * LogLevel.java
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

package org.bluezoo.gumdrop.telemetry;

/**
 * The kind of a {@link LogRecord}: the one axis an exporter matches
 * records on. The operational levels carry the OpenTelemetry severity of
 * the same name. {@link #ACCESS} and {@link #QLOG} are streams of their
 * own, so that an exporter takes a stream only when it is configured to:
 * the default exporter prints operational events and nothing else, a
 * qlog exporter takes qlog events and nothing else.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum LogLevel {

    /** An operational event of note. */
    INFO(9, "INFO"),

    /** An operational condition that may need attention. */
    WARN(13, "WARN"),

    /** An operational failure. */
    ERROR(17, "ERROR"),

    /** One completed HTTP request. */
    ACCESS(9, "INFO"),

    /** One QUIC or HTTP/3 qlog event. */
    QLOG(5, "DEBUG");

    private final int severityNumber;
    private final String severityText;

    LogLevel(int severityNumber, String severityText) {
        this.severityNumber = severityNumber;
        this.severityText = severityText;
    }

    /**
     * Returns the OTLP severity number records of this level are exported with.
     *
     * @return the severity number
     */
    public int getSeverityNumber() {
        return severityNumber;
    }

    /**
     * Returns the OTLP severity text records of this level are exported with.
     *
     * @return the severity text
     */
    public String getSeverityText() {
        return severityText;
    }

}
