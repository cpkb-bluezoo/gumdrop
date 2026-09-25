/*
 * SaslInit.java
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
 * The SASL {@code sasl-init} frame body (SASL profile 3.2.2): sent by the
 * client to select a mechanism and supply its initial response.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SaslInit extends Performative {

    private final String mechanism;
    private final byte[] initialResponse;
    private String hostname;

    /**
     * @param mechanism the selected mechanism name
     * @param initialResponse the mechanism's initial response, or {@code null} if none
     */
    public SaslInit(String mechanism, byte[] initialResponse) {
        if (mechanism == null) {
            throw new IllegalArgumentException("mechanism must not be null");
        }
        this.mechanism = mechanism;
        this.initialResponse = initialResponse;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_SASL_INIT;
    }

    public String getMechanism() {
        return mechanism;
    }

    /** The initial response, or {@code null}. */
    public byte[] getInitialResponse() {
        return initialResponse;
    }

    /** The name of the target host, or {@code null}. */
    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addSymbol(mechanism)
                .addBinary(initialResponse)
                .addString(hostname)
                .writeTo(out, DESCRIPTOR_SASL_INIT);
    }

    static SaslInit fromFields(List<Object> f) throws Amqp1ProtocolException {
        SaslInit init = new SaslInit(Fields.required(Fields.symbol(f, 0, "mechanism"),
                "sasl-init", "mechanism"), Fields.binary(f, 1, "initial-response"));
        init.hostname = Fields.string(f, 2, "hostname");
        return init;
    }
}
