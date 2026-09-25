/*
 * SaslMechanisms.java
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
 * The SASL {@code sasl-mechanisms} frame body (SASL profile 3.2.1): sent
 * by the server after the SASL protocol header to advertise the
 * mechanisms it supports, most-preferred first.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SaslMechanisms extends Performative {

    private final List<String> mechanisms;

    public SaslMechanisms(List<String> mechanisms) {
        if (mechanisms == null || mechanisms.isEmpty()) {
            throw new IllegalArgumentException("at least one mechanism is required");
        }
        this.mechanisms = mechanisms;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_SASL_MECHANISMS;
    }

    /** The advertised mechanism names, for example {@code PLAIN}. */
    public List<String> getMechanisms() {
        return mechanisms;
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addSymbols(mechanisms)
                .writeTo(out, DESCRIPTOR_SASL_MECHANISMS);
    }

    static SaslMechanisms fromFields(List<Object> f) throws Amqp1ProtocolException {
        List<String> m = Fields.symbols(f, 0, "sasl-server-mechanisms");
        if (m.isEmpty()) {
            throw new Amqp1ProtocolException(
                    "sasl-mechanisms is missing mandatory field 'sasl-server-mechanisms'");
        }
        return new SaslMechanisms(m);
    }
}
