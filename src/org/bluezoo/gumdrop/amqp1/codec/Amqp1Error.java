/*
 * Amqp1Error.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AMQP 1.0 {@code error} composite (core specification 2.8.16),
 * carried by {@code end}, {@code close} and {@code detach} to report why
 * a session, connection or link is being terminated.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1Error {

    /** Well-known error conditions (core specification 2.8.15 and 2.8.18-2.8.20). */
    public static final String INTERNAL_ERROR = "amqp:internal-error";
    public static final String NOT_FOUND = "amqp:not-found";
    public static final String UNAUTHORIZED_ACCESS = "amqp:unauthorized-access";
    public static final String DECODE_ERROR = "amqp:decode-error";
    public static final String RESOURCE_LIMIT_EXCEEDED = "amqp:resource-limit-exceeded";
    public static final String NOT_ALLOWED = "amqp:not-allowed";
    public static final String INVALID_FIELD = "amqp:invalid-field";
    public static final String NOT_IMPLEMENTED = "amqp:not-implemented";
    public static final String RESOURCE_LOCKED = "amqp:resource-locked";
    public static final String PRECONDITION_FAILED = "amqp:precondition-failed";
    public static final String RESOURCE_DELETED = "amqp:resource-deleted";
    public static final String ILLEGAL_STATE = "amqp:illegal-state";
    public static final String FRAME_SIZE_TOO_SMALL = "amqp:frame-size-too-small";
    public static final String CONNECTION_FORCED = "amqp:connection:forced";
    public static final String FRAMING_ERROR = "amqp:connection:framing-error";
    public static final String CONNECTION_REDIRECT = "amqp:connection:redirect";
    public static final String WINDOW_VIOLATION = "amqp:session:window-violation";
    public static final String UNATTACHED_HANDLE = "amqp:session:unattached-handle";
    public static final String TRANSFER_LIMIT_EXCEEDED = "amqp:link:transfer-limit-exceeded";
    public static final String MESSAGE_SIZE_EXCEEDED = "amqp:link:message-size-exceeded";
    public static final String DETACH_FORCED = "amqp:link:detach-forced";

    private String condition;
    private String description;
    private Map<Object, Object> info = new LinkedHashMap<Object, Object>();

    public Amqp1Error(String condition) {
        this(condition, null);
    }

    public Amqp1Error(String condition, String description) {
        if (condition == null) {
            throw new IllegalArgumentException("condition must not be null");
        }
        this.condition = condition;
        this.description = description;
    }

    /** The symbolic error condition, for example {@link #NOT_FOUND}. */
    public String getCondition() {
        return condition;
    }

    /** A human-readable description, or {@code null}. */
    public String getDescription() {
        return description;
    }

    /** Condition-specific supplementary information (symbol keys); never null. */
    public Map<Object, Object> getInfo() {
        return info;
    }

    public void setInfo(Map<Object, Object> info) {
        this.info = info == null ? new LinkedHashMap<Object, Object>() : info;
    }

    void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addSymbol(condition)
                .addString(description)
                .addMap(info)
                .writeTo(out, Performative.DESCRIPTOR_ERROR);
    }

    static Amqp1Error fromDescribed(Object value) throws Amqp1ProtocolException {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Amqp1Described)) {
            throw new Amqp1ProtocolException("error field is not a described type");
        }
        Amqp1Described d = (Amqp1Described) value;
        if (!(d.getDescriptor() instanceof Long)
                || ((Long) d.getDescriptor()).longValue() != Performative.DESCRIPTOR_ERROR
                || !(d.getValue() instanceof List)) {
            throw new Amqp1ProtocolException("error field has the wrong descriptor: "
                    + d.getDescriptor());
        }
        @SuppressWarnings("unchecked")
        List<Object> fields = (List<Object>) d.getValue();
        String condition = Fields.required(Fields.symbol(fields, 0, "condition"),
                "error", "condition");
        Amqp1Error e = new Amqp1Error(condition, Fields.string(fields, 1, "description"));
        e.info = Fields.map(fields, 2, "info");
        return e;
    }

    @Override
    public String toString() {
        return description == null ? condition : condition + ": " + description;
    }
}
