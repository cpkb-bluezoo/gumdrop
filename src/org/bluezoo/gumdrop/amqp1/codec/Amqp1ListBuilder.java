/*
 * Amqp1ListBuilder.java
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

import java.util.List;
import java.util.Map;

/**
 * Builds the field list of a described composite type such as a
 * performative.
 *
 * <p>Composite types are encoded as a {@code list} of positional fields.
 * Each {@code add} method appends the next field; passing {@code null}
 * (or calling {@link #addNull()}) leaves that field at its default. On
 * {@link #writeTo}, trailing null fields are dropped, as the core
 * specification permits, so the list carries only as many fields as
 * are actually set.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Amqp1ListBuilder {

    private final Amqp1Encoder body = new Amqp1Encoder();
    private int count;
    private int significantCount;
    private int significantSize;

    private void added(boolean isNull) {
        count++;
        if (!isNull) {
            significantCount = count;
            significantSize = body.size();
        }
    }

    public Amqp1ListBuilder addNull() {
        body.writeNull();
        added(true);
        return this;
    }

    public Amqp1ListBuilder addBoolean(Boolean v) {
        if (v == null) {
            return addNull();
        }
        body.writeBoolean(v.booleanValue());
        added(false);
        return this;
    }

    public Amqp1ListBuilder addUbyte(Integer v) {
        if (v == null) {
            return addNull();
        }
        body.writeUbyte(v.intValue());
        added(false);
        return this;
    }

    public Amqp1ListBuilder addUshort(Integer v) {
        if (v == null) {
            return addNull();
        }
        body.writeUshort(v.intValue());
        added(false);
        return this;
    }

    public Amqp1ListBuilder addUint(Long v) {
        if (v == null) {
            return addNull();
        }
        body.writeUint(v.longValue());
        added(false);
        return this;
    }

    public Amqp1ListBuilder addUlong(Long v) {
        if (v == null) {
            return addNull();
        }
        body.writeUlong(v.longValue());
        added(false);
        return this;
    }

    public Amqp1ListBuilder addString(String v) {
        if (v == null) {
            return addNull();
        }
        body.writeString(v);
        added(false);
        return this;
    }

    public Amqp1ListBuilder addSymbol(String v) {
        if (v == null) {
            return addNull();
        }
        body.writeSymbol(v);
        added(false);
        return this;
    }

    /** Adds a multiple-valued symbol field as an array; null or empty leaves it unset. */
    public Amqp1ListBuilder addSymbols(List<String> v) {
        if (v == null || v.isEmpty()) {
            return addNull();
        }
        body.writeSymbolArray(v);
        added(false);
        return this;
    }

    public Amqp1ListBuilder addBinary(byte[] v) {
        if (v == null) {
            return addNull();
        }
        body.writeBinary(v);
        added(false);
        return this;
    }

    /** Adds a map field; null or empty leaves it unset. */
    public Amqp1ListBuilder addMap(Map<?, ?> v) {
        if (v == null || v.isEmpty()) {
            return addNull();
        }
        body.writeMap(v);
        added(false);
        return this;
    }

    /** Adds an arbitrary value encoded with {@link Amqp1Encoder#writeObject}. */
    public Amqp1ListBuilder addObject(Object v) {
        if (v == null) {
            return addNull();
        }
        body.writeObject(v);
        added(false);
        return this;
    }

    /** Adds an already-encoded value (for example a nested composite). */
    Amqp1ListBuilder addEncoded(Amqp1Encoder encoded) {
        byte[] b = encoded.toByteArray();
        body.writeRaw(b, 0, b.length);
        added(false);
        return this;
    }

    /** Adds a timestamp field (milliseconds since the epoch), or null if {@code v} is null. */
    public Amqp1ListBuilder addTimestamp(Long v) {
        if (v == null) {
            return addNull();
        }
        body.writeTimestamp(v.longValue());
        added(false);
        return this;
    }

    /**
     * Adds a message-id or correlation-id: a {@link Long} is written as a
     * {@code ulong}, a {@link java.util.UUID} as a {@code uuid}, a
     * {@code byte[]} as {@code binary} and a {@link String} as {@code string}.
     *
     * @throws IllegalArgumentException for any other type
     */
    public Amqp1ListBuilder addMessageId(Object v) {
        if (v == null) {
            return addNull();
        }
        if (v instanceof Long) {
            body.writeUlong(((Long) v).longValue());
        } else if (v instanceof java.util.UUID || v instanceof byte[] || v instanceof String) {
            body.writeObject(v);
        } else {
            throw new IllegalArgumentException("message-id must be a Long, UUID, byte[] or String");
        }
        added(false);
        return this;
    }

    /** Adds a delivery state, or a null field if {@code state} is null. */
    Amqp1ListBuilder addObjectEncoded(DeliveryState state) {
        if (state == null) {
            return addNull();
        }
        Amqp1Encoder e = new Amqp1Encoder();
        state.write(e);
        return addEncoded(e);
    }

    /**
     * Writes the described list: descriptor, then the list of the fields
     * added so far, minus any trailing nulls.
     */
    public void writeTo(Amqp1Encoder out, long descriptor) {
        out.writeDescriptor(descriptor);
        byte[] fields = body.toByteArray();
        out.writeCompound(Amqp1Types.LIST0, Amqp1Types.LIST8, Amqp1Types.LIST32,
                fields, significantSize, significantCount);
    }
}
