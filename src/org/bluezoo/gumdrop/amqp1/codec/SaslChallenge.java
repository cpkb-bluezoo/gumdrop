/*
 * SaslChallenge.java
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
 * The SASL {@code sasl-challenge} frame body (SASL profile 3.2.3): sent by the
 * server carrying the server's challenge data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SaslChallenge extends Performative {

    private final byte[] data;

    public SaslChallenge(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("challenge must not be null");
        }
        this.data = data;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_SASL_CHALLENGE;
    }

    /** The opaque mechanism-specific challenge data. */
    public byte[] getData() {
        return data;
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addBinary(data)
                .writeTo(out, DESCRIPTOR_SASL_CHALLENGE);
    }

    static SaslChallenge fromFields(List<Object> f) throws Amqp1ProtocolException {
        return new SaslChallenge(Fields.required(Fields.binary(f, 0, "challenge"),
                "sasl-challenge", "challenge"));
    }
}
