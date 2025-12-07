/*
 * SpanStatus.java
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

package org.bluezoo.gumdrop.telemetry;

/**
 * Represents the status of a span.
 * A span can be unset (default), OK (successful), or ERROR (failed).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpanStatus {

    /**
     * Status code values matching OTLP StatusCode enum.
     */
    public static final int STATUS_CODE_UNSET = 0;
    public static final int STATUS_CODE_OK = 1;
    public static final int STATUS_CODE_ERROR = 2;

    /**
     * Singleton for unset status.
     */
    public static final SpanStatus UNSET = new SpanStatus(STATUS_CODE_UNSET, null);

    /**
     * Singleton for OK status.
     */
    public static final SpanStatus OK = new SpanStatus(STATUS_CODE_OK, null);

    private final int code;
    private final String message;

    private SpanStatus(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * Creates an error status with a description message.
     *
     * @param message the error description
     * @return a new error status
     */
    public static SpanStatus error(String message) {
        return new SpanStatus(STATUS_CODE_ERROR, message);
    }

    /**
     * Returns the status code.
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the status message, if any.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns true if this status indicates an error.
     */
    public boolean isError() {
        return code == STATUS_CODE_ERROR;
    }

    /**
     * Returns true if this status indicates success.
     */
    public boolean isOk() {
        return code == STATUS_CODE_OK;
    }

}

