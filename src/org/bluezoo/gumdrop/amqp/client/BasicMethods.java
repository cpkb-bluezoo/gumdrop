/*
 * BasicMethods.java
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
 * Encode/decode for {@code basic} class (60) method arguments.
 *
 * <p>Note that none of these methods carry a message body or its
 * properties — {@code basic.publish} and {@code basic.deliver} only
 * carry routing metadata (exchange, routing key, flags, tags). The
 * associated content-header and content-body frames that follow are
 * handled separately by {@link BasicProperties} and delivered as
 * incremental chunks by {@link AMQPFrameHandler#bodyFrame}, never
 * buffered whole by this codec layer — that streaming boundary is the
 * whole point of keeping method arguments (always small) and message
 * bodies (potentially large) on separate code paths.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class BasicMethods {

    private BasicMethods() {
    }

    /** {@code basic.qos} (60,10) — sent by the client. */
    static ByteBuffer encodeQos(long prefetchSize, int prefetchCount, boolean global) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 2 + 1);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_QOS);
        buf.putInt((int) prefetchSize);
        buf.putShort((short) prefetchCount);
        buf.put(AMQPBits.pack(global));
        buf.flip();
        return buf;
    }

    /** {@code basic.qos-ok} (60,11) — sent by the server; no arguments. */
    static void decodeQosOk(ByteBuffer payload) {
        // No arguments.
    }

    /** {@code basic.consume} (60,20) — sent by the client. */
    static ByteBuffer encodeConsume(String queue, String consumerTag, boolean noLocal,
            boolean noAck, boolean exclusive, boolean noWait, FieldTable arguments) {
        FieldTable args = (arguments != null) ? arguments : new FieldTable();
        int size = 4
                + 2 // reserved-1 (ticket)
                + FieldTable.shortStringEncodedSize(queue)
                + FieldTable.shortStringEncodedSize(consumerTag)
                + 1 // bits
                + 4 + args.encodedContentSize();
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_CONSUME);
        buf.putShort((short) 0);
        FieldTable.putShortString(buf, queue);
        FieldTable.putShortString(buf, consumerTag);
        buf.put(AMQPBits.pack(noLocal, noAck, exclusive, noWait));
        ByteBuffer encodedArgs = args.encode();
        buf.putInt(encodedArgs.remaining());
        buf.put(encodedArgs);
        buf.flip();
        return buf;
    }

    /** {@code basic.consume-ok} (60,21) — sent by the server. */
    static String decodeConsumeOk(ByteBuffer payload) throws AMQPProtocolException {
        return FieldTable.getShortString(payload);
    }

    static ByteBuffer encodeConsumeOk(String consumerTag) {
        int size = 4 + FieldTable.shortStringEncodedSize(consumerTag);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_CONSUME_OK);
        FieldTable.putShortString(buf, consumerTag);
        buf.flip();
        return buf;
    }

    /** Decoded {@code basic.consume} arguments — used by a server-side implementation. */
    static final class Consume {
        final String queue;
        final String consumerTag;
        final boolean noAck;
        final boolean exclusive;

        Consume(String queue, String consumerTag, boolean noAck, boolean exclusive) {
            this.queue = queue;
            this.consumerTag = consumerTag;
            this.noAck = noAck;
            this.exclusive = exclusive;
        }
    }

    static Consume decodeConsume(ByteBuffer payload) throws AMQPProtocolException {
        payload.getShort(); // reserved-1 (ticket)
        String queue = FieldTable.getShortString(payload);
        String consumerTag = FieldTable.getShortString(payload);
        byte bits = payload.get();
        boolean noAck = AMQPBits.unpack(bits, 1);
        boolean exclusive = AMQPBits.unpack(bits, 2);
        int argsLen = payload.getInt();
        FieldTable.decode(payload, argsLen); // arguments, discarded
        return new Consume(queue, consumerTag, noAck, exclusive);
    }

    /** {@code basic.cancel} (60,30) — sent by either peer. */
    static ByteBuffer encodeCancel(String consumerTag, boolean noWait) {
        int size = 4 + FieldTable.shortStringEncodedSize(consumerTag) + 1;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_CANCEL);
        FieldTable.putShortString(buf, consumerTag);
        buf.put(AMQPBits.pack(noWait));
        buf.flip();
        return buf;
    }

    /** Decodes a {@code basic.cancel} request (consumer tag + no-wait bit) — used by a server-side implementation. */
    static String decodeCancel(ByteBuffer payload) throws AMQPProtocolException {
        String consumerTag = FieldTable.getShortString(payload);
        payload.get(); // no-wait bit, discarded
        return consumerTag;
    }

    /** {@code basic.cancel-ok} (60,31) — sent by either peer. */
    static String decodeCancelOk(ByteBuffer payload) throws AMQPProtocolException {
        return FieldTable.getShortString(payload);
    }

    static ByteBuffer encodeCancelOk(String consumerTag) {
        int size = 4 + FieldTable.shortStringEncodedSize(consumerTag);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_CANCEL_OK);
        FieldTable.putShortString(buf, consumerTag);
        buf.flip();
        return buf;
    }

    /** {@code basic.publish} (60,40) — sent by the client. Message body follows separately. */
    static ByteBuffer encodePublish(String exchange, String routingKey,
            boolean mandatory, boolean immediate) {
        int size = 4
                + 2 // reserved-1 (ticket)
                + FieldTable.shortStringEncodedSize(exchange)
                + FieldTable.shortStringEncodedSize(routingKey)
                + 1; // bits
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_PUBLISH);
        buf.putShort((short) 0);
        FieldTable.putShortString(buf, exchange);
        FieldTable.putShortString(buf, routingKey);
        buf.put(AMQPBits.pack(mandatory, immediate));
        buf.flip();
        return buf;
    }

    /** Decoded {@code basic.publish} arguments — used by a server-side implementation. */
    static final class Publish {
        final String exchange;
        final String routingKey;
        final boolean mandatory;
        final boolean immediate;

        Publish(String exchange, String routingKey, boolean mandatory, boolean immediate) {
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.mandatory = mandatory;
            this.immediate = immediate;
        }
    }

    static Publish decodePublish(ByteBuffer payload) throws AMQPProtocolException {
        payload.getShort(); // reserved-1 (ticket)
        String exchange = FieldTable.getShortString(payload);
        String routingKey = FieldTable.getShortString(payload);
        byte bits = payload.get();
        return new Publish(exchange, routingKey, AMQPBits.unpack(bits, 0), AMQPBits.unpack(bits, 1));
    }

    /** {@code basic.return} (60,50) — sent by the server. */
    static final class Return {
        final int replyCode;
        final String replyText;
        final String exchange;
        final String routingKey;

        Return(int replyCode, String replyText, String exchange, String routingKey) {
            this.replyCode = replyCode;
            this.replyText = replyText;
            this.exchange = exchange;
            this.routingKey = routingKey;
        }
    }

    static Return decodeReturn(ByteBuffer payload) throws AMQPProtocolException {
        int replyCode = payload.getShort() & 0xFFFF;
        String replyText = FieldTable.getShortString(payload);
        String exchange = FieldTable.getShortString(payload);
        String routingKey = FieldTable.getShortString(payload);
        return new Return(replyCode, replyText, exchange, routingKey);
    }

    /** {@code basic.deliver} (60,60) — sent by the server. Message body follows separately. */
    static final class Deliver {
        final String consumerTag;
        final long deliveryTag;
        final boolean redelivered;
        final String exchange;
        final String routingKey;

        Deliver(String consumerTag, long deliveryTag, boolean redelivered,
                String exchange, String routingKey) {
            this.consumerTag = consumerTag;
            this.deliveryTag = deliveryTag;
            this.redelivered = redelivered;
            this.exchange = exchange;
            this.routingKey = routingKey;
        }
    }

    static Deliver decodeDeliver(ByteBuffer payload) throws AMQPProtocolException {
        String consumerTag = FieldTable.getShortString(payload);
        long deliveryTag = payload.getLong();
        boolean redelivered = AMQPBits.unpack(payload.get(), 0);
        String exchange = FieldTable.getShortString(payload);
        String routingKey = FieldTable.getShortString(payload);
        return new Deliver(consumerTag, deliveryTag, redelivered, exchange, routingKey);
    }

    static ByteBuffer encodeDeliver(String consumerTag, long deliveryTag, boolean redelivered,
            String exchange, String routingKey) {
        int size = 4 + FieldTable.shortStringEncodedSize(consumerTag) + 8 + 1
                + FieldTable.shortStringEncodedSize(exchange)
                + FieldTable.shortStringEncodedSize(routingKey);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_DELIVER);
        FieldTable.putShortString(buf, consumerTag);
        buf.putLong(deliveryTag);
        buf.put(AMQPBits.pack(redelivered));
        FieldTable.putShortString(buf, exchange);
        FieldTable.putShortString(buf, routingKey);
        buf.flip();
        return buf;
    }

    /** {@code basic.get} (60,70) — sent by the client. */
    static ByteBuffer encodeGet(String queue, boolean noAck) {
        int size = 4 + 2 + FieldTable.shortStringEncodedSize(queue) + 1;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_GET);
        buf.putShort((short) 0);
        FieldTable.putShortString(buf, queue);
        buf.put(AMQPBits.pack(noAck));
        buf.flip();
        return buf;
    }

    /** {@code basic.get-ok} (60,71) — sent by the server. Message body follows separately. */
    static final class GetOk {
        final long deliveryTag;
        final boolean redelivered;
        final String exchange;
        final String routingKey;
        final long messageCount;

        GetOk(long deliveryTag, boolean redelivered, String exchange, String routingKey,
                long messageCount) {
            this.deliveryTag = deliveryTag;
            this.redelivered = redelivered;
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.messageCount = messageCount;
        }
    }

    static GetOk decodeGetOk(ByteBuffer payload) throws AMQPProtocolException {
        long deliveryTag = payload.getLong();
        boolean redelivered = AMQPBits.unpack(payload.get(), 0);
        String exchange = FieldTable.getShortString(payload);
        String routingKey = FieldTable.getShortString(payload);
        long messageCount = payload.getInt() & 0xFFFFFFFFL;
        return new GetOk(deliveryTag, redelivered, exchange, routingKey, messageCount);
    }

    /** {@code basic.get-empty} (60,72) — sent by the server; no fields we care about. */
    static void decodeGetEmpty(ByteBuffer payload) throws AMQPProtocolException {
        FieldTable.getShortString(payload); // reserved-1, discarded
    }

    /** {@code basic.ack} (60,80) — sent by either peer. */
    static ByteBuffer encodeAck(long deliveryTag, boolean multiple) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 1);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_ACK);
        buf.putLong(deliveryTag);
        buf.put(AMQPBits.pack(multiple));
        buf.flip();
        return buf;
    }

    static final class Ack {
        final long deliveryTag;
        final boolean multiple;

        Ack(long deliveryTag, boolean multiple) {
            this.deliveryTag = deliveryTag;
            this.multiple = multiple;
        }
    }

    static Ack decodeAck(ByteBuffer payload) {
        long deliveryTag = payload.getLong();
        boolean multiple = AMQPBits.unpack(payload.get(), 0);
        return new Ack(deliveryTag, multiple);
    }

    /** {@code basic.reject} (60,90) — sent by the client. */
    static ByteBuffer encodeReject(long deliveryTag, boolean requeue) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 1);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_REJECT);
        buf.putLong(deliveryTag);
        buf.put(AMQPBits.pack(requeue));
        buf.flip();
        return buf;
    }

    /** {@code basic.nack} (60,120, RabbitMQ extension) — sent by either peer. */
    static ByteBuffer encodeNack(long deliveryTag, boolean multiple, boolean requeue) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 1);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_NACK);
        buf.putLong(deliveryTag);
        buf.put(AMQPBits.pack(multiple, requeue));
        buf.flip();
        return buf;
    }

    static final class Nack {
        final long deliveryTag;
        final boolean multiple;
        final boolean requeue;

        Nack(long deliveryTag, boolean multiple, boolean requeue) {
            this.deliveryTag = deliveryTag;
            this.multiple = multiple;
            this.requeue = requeue;
        }
    }

    static Nack decodeNack(ByteBuffer payload) {
        long deliveryTag = payload.getLong();
        byte bits = payload.get();
        return new Nack(deliveryTag, AMQPBits.unpack(bits, 0), AMQPBits.unpack(bits, 1));
    }

    /** {@code basic.recover} (60,110) — sent by the client. */
    static ByteBuffer encodeRecover(boolean requeue) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 1);
        buf.putShort((short) AMQPMethod.CLASS_BASIC);
        buf.putShort((short) AMQPMethod.BASIC_RECOVER);
        buf.put(AMQPBits.pack(requeue));
        buf.flip();
        return buf;
    }

    /** {@code basic.recover-ok} (60,111) — sent by the server; no arguments. */
    static void decodeRecoverOk(ByteBuffer payload) {
        // No arguments.
    }
}
