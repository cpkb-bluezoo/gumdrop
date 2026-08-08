/*
 * AMQPLainClientMechanism.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.auth.SASLClientMechanism;

/**
 * Client-side {@code AMQPLAIN} SASL mechanism (issue #188).
 *
 * <p>{@code AMQPLAIN} is a broker-specific (RabbitMQ) mechanism, not an
 * IANA-registered SASL mechanism, so it is implemented here in the AMQP
 * client package rather than in {@link org.bluezoo.gumdrop.auth.SASLUtils},
 * which is shared across protocols that only ever see standard mechanisms.
 * It still implements the same {@link SASLClientMechanism} contract as
 * every other mechanism this client supports, so the protocol handler
 * drives it identically.
 *
 * <p>The response is a single {@link FieldTable} (no outer length prefix
 * of its own — {@code connection.start-ok} already length-prefixes the
 * whole response) with two {@code longstr} entries, {@code LOGIN} and
 * {@code PASSWORD}, in place of the plain {@code \0user\0pass} encoding
 * that {@code PLAIN} uses.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rabbitmq.com/docs/access-control#mechanisms">RabbitMQ: Authentication Mechanisms</a>
 */
final class AMQPLainClientMechanism implements SASLClientMechanism {

    private final String username;
    private final String password;
    private boolean complete;

    AMQPLainClientMechanism(String username, String password) {
        this.username = username;
        this.password = (password != null) ? password : "";
    }

    @Override
    public String getMechanismName() {
        return "AMQPLAIN";
    }

    @Override
    public boolean hasInitialResponse() {
        return true;
    }

    @Override
    public byte[] evaluateChallenge(byte[] challenge) {
        complete = true;
        FieldTable table = new FieldTable()
                .put("LOGIN", username)
                .put("PASSWORD", password);
        ByteBuffer encoded = table.encode();
        byte[] response = new byte[encoded.remaining()];
        encoded.get(response);
        return response;
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

}
