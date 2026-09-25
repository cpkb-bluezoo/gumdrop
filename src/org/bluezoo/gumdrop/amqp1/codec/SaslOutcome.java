/*
 * SaslOutcome.java
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
 * The SASL {@code sasl-outcome} frame body (SASL profile 3.2.5): sent by
 * the server to conclude the exchange. If the {@linkplain #getCode code}
 * is {@link #OK}, the peers proceed to the AMQP protocol header.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SaslOutcome extends Performative {

    /** Authentication succeeded. */
    public static final int OK = 0;
    /** Authentication failed: incorrect credentials. */
    public static final int AUTH = 1;
    /** Failed due to a system error. */
    public static final int SYS = 2;
    /** Failed due to a permanent system error. */
    public static final int SYS_PERM = 3;
    /** Failed due to a transient system error. */
    public static final int SYS_TEMP = 4;

    private final int code;
    private final byte[] additionalData;

    /**
     * @param code one of {@link #OK}, {@link #AUTH}, {@link #SYS},
     *      {@link #SYS_PERM}, {@link #SYS_TEMP}
     * @param additionalData mechanism-specific data sent with a
     *      successful outcome, or {@code null}
     */
    public SaslOutcome(int code, byte[] additionalData) {
        this.code = code;
        this.additionalData = additionalData;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_SASL_OUTCOME;
    }

    public int getCode() {
        return code;
    }

    public boolean isSuccess() {
        return code == OK;
    }

    /** Additional mechanism data, or {@code null}. */
    public byte[] getAdditionalData() {
        return additionalData;
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addUbyte(Integer.valueOf(code))
                .addBinary(additionalData)
                .writeTo(out, DESCRIPTOR_SASL_OUTCOME);
    }

    static SaslOutcome fromFields(List<Object> f) throws Amqp1ProtocolException {
        Long code = Fields.required(Fields.unsigned(f, 0, "code"), "sasl-outcome", "code");
        return new SaslOutcome(code.intValue(), Fields.binary(f, 1, "additional-data"));
    }
}
