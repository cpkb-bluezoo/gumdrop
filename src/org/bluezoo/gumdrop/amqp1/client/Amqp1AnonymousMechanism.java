/*
 * Amqp1AnonymousMechanism.java
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

package org.bluezoo.gumdrop.amqp1.client;

import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.auth.SaslClientMechanism;

/**
 * Client-side SASL {@code ANONYMOUS} mechanism (RFC 4505). The optional
 * trace information is sent as the initial response.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4505">RFC 4505</a>
 */
final class Amqp1AnonymousMechanism implements SaslClientMechanism {

    private final String trace;
    private boolean complete;

    Amqp1AnonymousMechanism(String trace) {
        this.trace = trace;
    }

    @Override
    public String getMechanismName() {
        return "ANONYMOUS";
    }

    @Override
    public boolean hasInitialResponse() {
        return trace != null && !trace.isEmpty();
    }

    @Override
    public byte[] evaluateChallenge(byte[] challenge) {
        complete = true;
        return trace == null ? new byte[0] : trace.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isComplete() {
        return complete;
    }
}
