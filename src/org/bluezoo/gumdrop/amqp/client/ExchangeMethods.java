/*
 * ExchangeMethods.java
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

/**
 * Encode/decode for {@code exchange} class (40) method arguments used by
 * this client. Only {@code declare} is implemented — exchange/queue
 * administration ({@code delete}, etc.) is explicitly out of scope for
 * this client (issue #154): it targets publish/consume workloads, not
 * broker administration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ExchangeMethods {

    private ExchangeMethods() {
    }

    /** {@code exchange.declare} (40,10) — sent by the client. */
    static ByteBuffer encodeDeclare(String exchange, String type, boolean passive,
            boolean durable, boolean autoDelete, boolean internal, boolean noWait,
            FieldTable arguments) {
        FieldTable args = (arguments != null) ? arguments : new FieldTable();
        int size = 4
                + 2 // reserved-1 (ticket)
                + FieldTable.shortStringEncodedSize(exchange)
                + FieldTable.shortStringEncodedSize(type)
                + 1 // bits
                + 4 + args.encodedContentSize();
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_EXCHANGE);
        buf.putShort((short) AMQPMethod.EXCHANGE_DECLARE);
        buf.putShort((short) 0);
        FieldTable.putShortString(buf, exchange);
        FieldTable.putShortString(buf, type);
        buf.put(AMQPBits.pack(passive, durable, autoDelete, internal, noWait));
        ByteBuffer encodedArgs = args.encode();
        buf.putInt(encodedArgs.remaining());
        buf.put(encodedArgs);
        buf.flip();
        return buf;
    }

    /** {@code exchange.declare-ok} (40,11) — sent by the server; no arguments. */
    static void decodeDeclareOk(ByteBuffer payload) {
        // No arguments.
    }

    static ByteBuffer encodeDeclareOk() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short) AMQPMethod.CLASS_EXCHANGE);
        buf.putShort((short) AMQPMethod.EXCHANGE_DECLARE_OK);
        buf.flip();
        return buf;
    }

    /** Decoded {@code exchange.declare} arguments — used by a server-side implementation. */
    static final class Declare {
        final String exchange;
        final String type;
        final boolean durable;

        Declare(String exchange, String type, boolean durable) {
            this.exchange = exchange;
            this.type = type;
            this.durable = durable;
        }
    }

    static Declare decodeDeclare(ByteBuffer payload) throws AMQPProtocolException {
        payload.getShort(); // reserved-1 (ticket)
        String exchange = FieldTable.getShortString(payload);
        String type = FieldTable.getShortString(payload);
        byte bits = payload.get();
        boolean durable = AMQPBits.unpack(bits, 1);
        int argsLen = payload.getInt();
        FieldTable.decode(payload, argsLen); // arguments, discarded
        return new Declare(exchange, type, durable);
    }
}
