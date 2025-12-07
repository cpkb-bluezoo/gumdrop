/*
 * SpanKind.java
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
 * Indicates the role of a span in a trace.
 * These values correspond to the OpenTelemetry SpanKind enum.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum SpanKind {

    /**
     * Default value. Indicates an internal operation within an application.
     */
    INTERNAL(1),

    /**
     * Indicates that the span covers server-side handling of a request.
     */
    SERVER(2),

    /**
     * Indicates that the span describes a request to some remote service.
     */
    CLIENT(3),

    /**
     * Indicates that the span describes a producer sending a message.
     */
    PRODUCER(4),

    /**
     * Indicates that the span describes a consumer receiving a message.
     */
    CONSUMER(5);

    private final int value;

    SpanKind(int value) {
        this.value = value;
    }

    /**
     * Returns the OTLP numeric value for this span kind.
     */
    public int getValue() {
        return value;
    }

}

