/*
 * BasicMethodsTest.java
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

package org.bluezoo.gumdrop.amqp;

import java.nio.ByteBuffer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Round-trip tests for the {@code basic} class encoders and decoders in
 * {@link BasicMethods}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicMethodsTest {

    /** Checks the class/method header and returns the buffer positioned at the arguments. */
    private static ByteBuffer arguments(ByteBuffer encoded, int method) {
        assertEquals(AmqpMethod.CLASS_BASIC, encoded.getShort());
        assertEquals(method, encoded.getShort());
        return encoded;
    }

    @Test
    public void qosLayout() {
        ByteBuffer buf = arguments(BasicMethods.encodeQos(0x01020304L, 25, true), AmqpMethod.BASIC_QOS);
        assertEquals(0x01020304, buf.getInt());
        assertEquals(25, buf.getShort());
        assertEquals(1, buf.get());
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void qosOkAndRecoverOkTakeNothing() {
        ByteBuffer empty = ByteBuffer.allocate(0);
        BasicMethods.decodeQosOk(empty);
        BasicMethods.decodeRecoverOk(empty);
    }

    @Test
    public void consumeRoundTripWithAndWithoutArguments() throws AmqpProtocolException {
        ByteBuffer buf = arguments(BasicMethods.encodeConsume("q", "tag", false, true, true, false, null),
                AmqpMethod.BASIC_CONSUME);
        BasicMethods.Consume consume = BasicMethods.decodeConsume(buf);
        assertEquals("q", consume.queue);
        assertEquals("tag", consume.consumerTag);
        assertTrue(consume.noAck);
        assertTrue(consume.exclusive);
        assertFalse(buf.hasRemaining());

        FieldTable args = new FieldTable();
        args.put("x-priority", Integer.valueOf(5));
        buf = arguments(BasicMethods.encodeConsume("q2", "", true, false, false, true, args),
                AmqpMethod.BASIC_CONSUME);
        consume = BasicMethods.decodeConsume(buf);
        assertEquals("q2", consume.queue);
        assertEquals("", consume.consumerTag);
        assertFalse(consume.noAck);
        assertFalse(consume.exclusive);
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void consumeOkRoundTrip() throws AmqpProtocolException {
        ByteBuffer buf = arguments(BasicMethods.encodeConsumeOk("amq.ctag-1"), AmqpMethod.BASIC_CONSUME_OK);
        assertEquals("amq.ctag-1", BasicMethods.decodeConsumeOk(buf));
    }

    @Test
    public void cancelRoundTrip() throws AmqpProtocolException {
        ByteBuffer buf = arguments(BasicMethods.encodeCancel("tag", true), AmqpMethod.BASIC_CANCEL);
        assertEquals("tag", BasicMethods.decodeCancel(buf));
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void cancelOkRoundTrip() throws AmqpProtocolException {
        ByteBuffer buf = arguments(BasicMethods.encodeCancelOk("tag"), AmqpMethod.BASIC_CANCEL_OK);
        assertEquals("tag", BasicMethods.decodeCancelOk(buf));
    }

    @Test
    public void publishRoundTrip() throws AmqpProtocolException {
        ByteBuffer buf = arguments(BasicMethods.encodePublish("ex", "rk", true, false), AmqpMethod.BASIC_PUBLISH);
        BasicMethods.Publish publish = BasicMethods.decodePublish(buf);
        assertEquals("ex", publish.exchange);
        assertEquals("rk", publish.routingKey);
        assertTrue(publish.mandatory);
        assertFalse(publish.immediate);
    }

    @Test
    public void deliverRoundTrip() throws AmqpProtocolException {
        ByteBuffer buf = arguments(BasicMethods.encodeDeliver("tag", 42L, true, "ex", "rk"),
                AmqpMethod.BASIC_DELIVER);
        BasicMethods.Deliver deliver = BasicMethods.decodeDeliver(buf);
        assertEquals("tag", deliver.consumerTag);
        assertEquals(42L, deliver.deliveryTag);
        assertTrue(deliver.redelivered);
        assertEquals("ex", deliver.exchange);
        assertEquals("rk", deliver.routingKey);
    }

    @Test
    public void returnDecodes() throws AmqpProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.putShort((short) 312);
        FieldTable.putShortString(buf, "NO_ROUTE");
        FieldTable.putShortString(buf, "ex");
        FieldTable.putShortString(buf, "rk");
        buf.flip();
        BasicMethods.Return ret = BasicMethods.decodeReturn(buf);
        assertEquals(312, ret.replyCode);
        assertEquals("NO_ROUTE", ret.replyText);
        assertEquals("ex", ret.exchange);
        assertEquals("rk", ret.routingKey);
    }

    @Test
    public void getEncodesQueueAndNoAck() throws AmqpProtocolException {
        ByteBuffer buf = arguments(BasicMethods.encodeGet("jobs", true), AmqpMethod.BASIC_GET);
        assertEquals(0, buf.getShort());
        assertEquals("jobs", FieldTable.getShortString(buf));
        assertEquals(1, buf.get());
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void getOkDecodesMessageCountUnsigned() throws AmqpProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.putLong(7L);
        buf.put((byte) 1);
        FieldTable.putShortString(buf, "ex");
        FieldTable.putShortString(buf, "rk");
        buf.putInt(0xFFFFFFFF);
        buf.flip();
        BasicMethods.GetOk getOk = BasicMethods.decodeGetOk(buf);
        assertEquals(7L, getOk.deliveryTag);
        assertTrue(getOk.redelivered);
        assertEquals("ex", getOk.exchange);
        assertEquals("rk", getOk.routingKey);
        assertEquals(0xFFFFFFFFL, getOk.messageCount);
    }

    @Test
    public void getEmptyConsumesClusterId() throws AmqpProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(8);
        FieldTable.putShortString(buf, "");
        buf.flip();
        BasicMethods.decodeGetEmpty(buf);
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void ackRoundTrip() {
        ByteBuffer buf = arguments(BasicMethods.encodeAck(99L, true), AmqpMethod.BASIC_ACK);
        BasicMethods.Ack ack = BasicMethods.decodeAck(buf);
        assertEquals(99L, ack.deliveryTag);
        assertTrue(ack.multiple);
    }

    @Test
    public void rejectLayout() {
        ByteBuffer buf = arguments(BasicMethods.encodeReject(5L, true), AmqpMethod.BASIC_REJECT);
        assertEquals(5L, buf.getLong());
        assertEquals(1, buf.get());
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void nackRoundTrip() {
        ByteBuffer buf = arguments(BasicMethods.encodeNack(11L, false, true), AmqpMethod.BASIC_NACK);
        BasicMethods.Nack nack = BasicMethods.decodeNack(buf);
        assertEquals(11L, nack.deliveryTag);
        assertFalse(nack.multiple);
        assertTrue(nack.requeue);
    }

    @Test
    public void recoverLayout() {
        ByteBuffer buf = arguments(BasicMethods.encodeRecover(true), AmqpMethod.BASIC_RECOVER);
        assertEquals(1, buf.get());
        assertFalse(buf.hasRemaining());
    }
}
