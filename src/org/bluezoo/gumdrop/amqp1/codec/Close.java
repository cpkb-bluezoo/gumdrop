/*
 * Close.java
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
 * The {@code close} performative (core specification 2.7.10): indicates that the connection is being closed,
 * optionally reporting the error that caused it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Close extends Performative {

    private final Amqp1Error error;

    /** Creates a normal (error-free) close. */
    public Close() {
        this(null);
    }

    /** @param error why it is being closed, or {@code null} for a normal close */
    public Close(Amqp1Error error) {
        this.error = error;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_CLOSE;
    }

    /** The error that caused this close, or {@code null}. */
    public Amqp1Error getError() {
        return error;
    }

    @Override
    public void write(Amqp1Encoder out) {
        Amqp1ListBuilder list = new Amqp1ListBuilder();
        if (error == null) {
            list.addNull();
        } else {
            Amqp1Encoder e = new Amqp1Encoder();
            error.write(e);
            list.addEncoded(e);
        }
        list.writeTo(out, DESCRIPTOR_CLOSE);
    }

    static Close fromFields(List<Object> f) throws Amqp1ProtocolException {
        return new Close(Amqp1Error.fromDescribed(Fields.get(f, 0)));
    }
}
