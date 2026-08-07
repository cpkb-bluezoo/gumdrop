/*
 * QueueMethods.java
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
 * Encode/decode for {@code queue} class (50) method arguments used by
 * this client. Only {@code declare} and {@code bind} are implemented —
 * queue administration ({@code unbind}, {@code purge}, {@code delete})
 * is explicitly out of scope for this client (issue #154).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QueueMethods {

    private QueueMethods() {
    }

    /** {@code queue.declare} (50,10) — sent by the client. */
    static ByteBuffer encodeDeclare(String queue, boolean passive, boolean durable,
            boolean exclusive, boolean autoDelete, boolean noWait, FieldTable arguments) {
        FieldTable args = (arguments != null) ? arguments : new FieldTable();
        int size = 4
                + 2 // reserved-1 (ticket)
                + FieldTable.shortStringEncodedSize(queue)
                + 1 // bits
                + 4 + args.encodedContentSize();
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_QUEUE);
        buf.putShort((short) AMQPMethod.QUEUE_DECLARE);
        buf.putShort((short) 0);
        FieldTable.putShortString(buf, queue);
        buf.put(AMQPBits.pack(passive, durable, exclusive, autoDelete, noWait));
        ByteBuffer encodedArgs = args.encode();
        buf.putInt(encodedArgs.remaining());
        buf.put(encodedArgs);
        buf.flip();
        return buf;
    }

    /** {@code queue.declare-ok} (50,11) — sent by the server. */
    static final class DeclareOk {
        final String queue;
        final long messageCount;
        final long consumerCount;

        DeclareOk(String queue, long messageCount, long consumerCount) {
            this.queue = queue;
            this.messageCount = messageCount;
            this.consumerCount = consumerCount;
        }
    }

    static DeclareOk decodeDeclareOk(ByteBuffer payload) throws AMQPProtocolException {
        String queue = FieldTable.getShortString(payload);
        long messageCount = payload.getInt() & 0xFFFFFFFFL;
        long consumerCount = payload.getInt() & 0xFFFFFFFFL;
        return new DeclareOk(queue, messageCount, consumerCount);
    }

    static ByteBuffer encodeDeclareOk(String queue, long messageCount, long consumerCount) {
        int size = 4 + FieldTable.shortStringEncodedSize(queue) + 4 + 4;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_QUEUE);
        buf.putShort((short) AMQPMethod.QUEUE_DECLARE_OK);
        FieldTable.putShortString(buf, queue);
        buf.putInt((int) messageCount);
        buf.putInt((int) consumerCount);
        buf.flip();
        return buf;
    }

    /** Decoded {@code queue.declare} arguments — used by a server-side implementation. */
    static final class Declare {
        final String queue;
        final boolean durable;
        final boolean exclusive;
        final boolean autoDelete;

        Declare(String queue, boolean durable, boolean exclusive, boolean autoDelete) {
            this.queue = queue;
            this.durable = durable;
            this.exclusive = exclusive;
            this.autoDelete = autoDelete;
        }
    }

    static Declare decodeDeclare(ByteBuffer payload) throws AMQPProtocolException {
        payload.getShort(); // reserved-1 (ticket)
        String queue = FieldTable.getShortString(payload);
        byte bits = payload.get();
        boolean durable = AMQPBits.unpack(bits, 1);
        boolean exclusive = AMQPBits.unpack(bits, 2);
        boolean autoDelete = AMQPBits.unpack(bits, 3);
        int argsLen = payload.getInt();
        FieldTable.decode(payload, argsLen); // arguments, discarded
        return new Declare(queue, durable, exclusive, autoDelete);
    }

    /** {@code queue.bind} (50,20) — sent by the client. */
    static ByteBuffer encodeBind(String queue, String exchange, String routingKey,
            boolean noWait, FieldTable arguments) {
        FieldTable args = (arguments != null) ? arguments : new FieldTable();
        int size = 4
                + 2 // reserved-1 (ticket)
                + FieldTable.shortStringEncodedSize(queue)
                + FieldTable.shortStringEncodedSize(exchange)
                + FieldTable.shortStringEncodedSize(routingKey)
                + 1 // bits
                + 4 + args.encodedContentSize();
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_QUEUE);
        buf.putShort((short) AMQPMethod.QUEUE_BIND);
        buf.putShort((short) 0);
        FieldTable.putShortString(buf, queue);
        FieldTable.putShortString(buf, exchange);
        FieldTable.putShortString(buf, routingKey);
        buf.put(AMQPBits.pack(noWait));
        ByteBuffer encodedArgs = args.encode();
        buf.putInt(encodedArgs.remaining());
        buf.put(encodedArgs);
        buf.flip();
        return buf;
    }

    /** {@code queue.bind-ok} (50,21) — sent by the server; no arguments. */
    static void decodeBindOk(ByteBuffer payload) {
        // No arguments.
    }

    static ByteBuffer encodeBindOk() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short) AMQPMethod.CLASS_QUEUE);
        buf.putShort((short) AMQPMethod.QUEUE_BIND_OK);
        buf.flip();
        return buf;
    }

    /** Decoded {@code queue.bind} arguments — used by a server-side implementation. */
    static final class Bind {
        final String queue;
        final String exchange;
        final String routingKey;

        Bind(String queue, String exchange, String routingKey) {
            this.queue = queue;
            this.exchange = exchange;
            this.routingKey = routingKey;
        }
    }

    static Bind decodeBind(ByteBuffer payload) throws AMQPProtocolException {
        payload.getShort(); // reserved-1 (ticket)
        String queue = FieldTable.getShortString(payload);
        String exchange = FieldTable.getShortString(payload);
        String routingKey = FieldTable.getShortString(payload);
        payload.get(); // no-wait bit
        int argsLen = payload.getInt();
        FieldTable.decode(payload, argsLen); // arguments, discarded
        return new Bind(queue, exchange, routingKey);
    }
}
