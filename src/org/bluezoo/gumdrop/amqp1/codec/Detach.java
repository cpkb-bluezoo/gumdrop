/*
 * Detach.java
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

/**
 * The {@code detach} performative (core specification 2.7.7): detaches a
 * link endpoint from a session, optionally closing (destroying) the link.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Detach extends Performative {

    private final long handle;
    private final boolean closed;
    private final Amqp1Error error;

    /**
     * @param handle the sender's handle for the link
     * @param closed {@code true} to close the link, {@code false} to leave it resumable
     * @param error why, or {@code null}
     */
    public Detach(long handle, boolean closed, Amqp1Error error) {
        this.handle = handle;
        this.closed = closed;
        this.error = error;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_DETACH;
    }

    public long getHandle() {
        return handle;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The error that caused the detach, or {@code null}. */
    public Amqp1Error getError() {
        return error;
    }

    @Override
    public void write(Amqp1Encoder out) {
        Amqp1ListBuilder list = new Amqp1ListBuilder()
                .addUint(Long.valueOf(handle))
                .addBoolean(closed ? Boolean.TRUE : null);
        if (error == null) {
            list.addNull();
        } else {
            Amqp1Encoder e = new Amqp1Encoder();
            error.write(e);
            list.addEncoded(e);
        }
        list.writeTo(out, DESCRIPTOR_DETACH);
    }

    static Detach fromFields(List<Object> f) throws Amqp1ProtocolException {
        long handle = Fields.required(Fields.unsigned(f, 0, "handle"), "detach", "handle")
                .longValue();
        Boolean closed = Fields.bool(f, 1, "closed");
        return new Detach(handle, closed != null && closed.booleanValue(),
                Amqp1Error.fromDescribed(Fields.get(f, 2)));
    }
}
