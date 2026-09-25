/*
 * Performative.java
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

import java.nio.ByteBuffer;

/**
 * A decoded AMQP 1.0 performative or SASL frame body.
 *
 * <p>Performatives are described composite types: a descriptor
 * identifying the type, then a list of positional fields. Concrete
 * subclasses hold the fields as typed properties and know how to encode
 * themselves; {@link PerformativeCodec#decode(ByteBuffer)} constructs
 * them from the wire.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see PerformativeCodec
 */
public abstract class Performative {

    /** Descriptor codes of the AMQP transport performatives (core specification 2.7). */
    public static final long DESCRIPTOR_OPEN = 0x10;
    public static final long DESCRIPTOR_BEGIN = 0x11;
    public static final long DESCRIPTOR_ATTACH = 0x12;
    public static final long DESCRIPTOR_FLOW = 0x13;
    public static final long DESCRIPTOR_TRANSFER = 0x14;
    public static final long DESCRIPTOR_DISPOSITION = 0x15;
    public static final long DESCRIPTOR_DETACH = 0x16;
    public static final long DESCRIPTOR_END = 0x17;
    public static final long DESCRIPTOR_CLOSE = 0x18;

    /** Descriptor codes of the messaging types (core specification 3.5 and 3.4). */
    public static final long DESCRIPTOR_SOURCE = 0x28;
    public static final long DESCRIPTOR_TARGET = 0x29;
    public static final long DESCRIPTOR_STATE_RECEIVED = 0x23;
    public static final long DESCRIPTOR_STATE_ACCEPTED = 0x24;
    public static final long DESCRIPTOR_STATE_REJECTED = 0x25;
    public static final long DESCRIPTOR_STATE_RELEASED = 0x26;
    public static final long DESCRIPTOR_STATE_MODIFIED = 0x27;

    /** Descriptor code of the {@code error} type (core specification 2.8.16). */
    public static final long DESCRIPTOR_ERROR = 0x1D;

    /** Descriptor codes of the SASL frame bodies (SASL profile 3.2). */
    public static final long DESCRIPTOR_SASL_MECHANISMS = 0x40;
    public static final long DESCRIPTOR_SASL_INIT = 0x41;
    public static final long DESCRIPTOR_SASL_CHALLENGE = 0x42;
    public static final long DESCRIPTOR_SASL_RESPONSE = 0x43;
    public static final long DESCRIPTOR_SASL_OUTCOME = 0x44;

    /** Returns the descriptor code identifying this performative type. */
    public abstract long getDescriptor();

    /** Writes this performative to {@code out}. */
    public abstract void write(Amqp1Encoder out);

    /**
     * Encodes this performative into a new buffer suitable as (the
     * start of) a frame body.
     */
    public ByteBuffer encode() {
        Amqp1Encoder out = new Amqp1Encoder();
        write(out);
        return out.toByteBuffer();
    }
}
