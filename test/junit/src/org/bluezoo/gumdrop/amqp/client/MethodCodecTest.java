/*
 * MethodCodecTest.java
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

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Round-trip tests for the {@code *Methods} argument codecs. Each test
 * encodes a method, strips the 4-byte class/method-ID header the same
 * way {@link AMQPFrameParser} + a dispatcher would, and decodes the
 * remaining arguments.
 */
public class MethodCodecTest {

    private static ByteBuffer stripHeader(ByteBuffer encoded, int expectedClassId, int expectedMethodId) {
        int classId = encoded.getShort() & 0xFFFF;
        int methodId = encoded.getShort() & 0xFFFF;
        assertEquals(expectedClassId, classId);
        assertEquals(expectedMethodId, methodId);
        return encoded;
    }

    // ── connection ──

    @Test
    public void testConnectionStartRoundTrip() throws AMQPProtocolException {
        FieldTable serverProps = new FieldTable().put("product", "RabbitMQ");
        ByteBuffer buf = ByteBuffer.allocate(2 + 4 + serverProps.encodedContentSize()
                + 4 + 5 + 4 + 5);
        buf.put((byte) 0);
        buf.put((byte) 9);
        ByteBuffer encodedProps = serverProps.encode();
        buf.putInt(encodedProps.remaining());
        buf.put(encodedProps);
        FieldTable.putLongString(buf, "PLAIN");
        FieldTable.putLongString(buf, "en_US");
        buf.flip();

        ConnectionMethods.Start start = ConnectionMethods.decodeStart(buf);
        assertEquals(0, start.versionMajor);
        assertEquals(9, start.versionMinor);
        assertEquals("RabbitMQ", start.serverProperties.get("product"));
        assertEquals("PLAIN", start.mechanisms);
        assertEquals("en_US", start.locales);
    }

    @Test
    public void testConnectionStartOkRoundTrip() throws AMQPProtocolException {
        FieldTable clientProps = new FieldTable().put("platform", "Java");
        byte[] response = "\0guest\0guest".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer encoded = ConnectionMethods.encodeStartOk(clientProps, "PLAIN", response, "en_US");
        stripHeader(encoded, AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_START_OK);

        int len = encoded.getInt();
        FieldTable decodedProps = FieldTable.decode(encoded, len);
        String mechanism = FieldTable.getShortString(encoded);
        String decodedResponse = FieldTable.getLongString(encoded);
        String locale = FieldTable.getShortString(encoded);

        assertEquals("Java", decodedProps.get("platform"));
        assertEquals("PLAIN", mechanism);
        assertEquals("guest\0guest", decodedResponse.substring(1));
        assertEquals("en_US", locale);
        assertFalse(encoded.hasRemaining());
    }

    @Test
    public void testConnectionTuneRoundTrip() {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putShort((short) 2047);
        buf.putInt(131072);
        buf.putShort((short) 60);
        buf.flip();

        ConnectionMethods.Tune tune = ConnectionMethods.decodeTune(buf);
        assertEquals(2047, tune.channelMax);
        assertEquals(131072L, tune.frameMax);
        assertEquals(60, tune.heartbeat);
    }

    @Test
    public void testConnectionTuneOkRoundTrip() {
        ByteBuffer encoded = ConnectionMethods.encodeTuneOk(2047, 131072, 60);
        stripHeader(encoded, AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_TUNE_OK);
        assertEquals(2047, encoded.getShort() & 0xFFFF);
        assertEquals(131072, encoded.getInt());
        assertEquals(60, encoded.getShort() & 0xFFFF);
        assertFalse(encoded.hasRemaining());
    }

    @Test
    public void testConnectionOpenEncodesVirtualHost() throws AMQPProtocolException {
        ByteBuffer encoded = ConnectionMethods.encodeOpen("/");
        stripHeader(encoded, AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_OPEN);
        assertEquals("/", FieldTable.getShortString(encoded));
    }

    @Test
    public void testConnectionCloseRoundTrip() throws AMQPProtocolException {
        ByteBuffer encoded = ConnectionMethods.encodeClose(200, "goodbye");
        stripHeader(encoded, AMQPMethod.CLASS_CONNECTION, AMQPMethod.CONNECTION_CLOSE);
        ConnectionMethods.CloseReason reason = ConnectionMethods.decodeClose(encoded);
        assertEquals(200, reason.replyCode);
        assertEquals("goodbye", reason.replyText);
        assertEquals(0, reason.classId);
        assertEquals(0, reason.methodId);
    }

    // ── channel ──

    @Test
    public void testChannelOpenEncodesReservedField() {
        ByteBuffer encoded = ChannelMethods.encodeOpen();
        stripHeader(encoded, AMQPMethod.CLASS_CHANNEL, AMQPMethod.CHANNEL_OPEN);
        assertEquals(0, encoded.get()); // empty short-string
        assertFalse(encoded.hasRemaining());
    }

    @Test
    public void testChannelCloseRoundTrip() throws AMQPProtocolException {
        ByteBuffer encoded = ChannelMethods.encodeClose(404, "not found");
        stripHeader(encoded, AMQPMethod.CLASS_CHANNEL, AMQPMethod.CHANNEL_CLOSE);
        ConnectionMethods.CloseReason reason = ChannelMethods.decodeClose(encoded);
        assertEquals(404, reason.replyCode);
        assertEquals("not found", reason.replyText);
    }

    @Test
    public void testChannelFlowRoundTrip() {
        ByteBuffer encoded = ChannelMethods.encodeFlow(true);
        stripHeader(encoded, AMQPMethod.CLASS_CHANNEL, AMQPMethod.CHANNEL_FLOW);
        assertTrue(ChannelMethods.decodeFlow(encoded));
    }

    // ── exchange / queue ──

    @Test
    public void testExchangeDeclareRoundTrip() throws AMQPProtocolException {
        FieldTable args = new FieldTable().put("x-arg", 1);
        ByteBuffer encoded = ExchangeMethods.encodeDeclare(
                "my-exchange", "topic", false, true, false, false, false, args);
        stripHeader(encoded, AMQPMethod.CLASS_EXCHANGE, AMQPMethod.EXCHANGE_DECLARE);
        assertEquals(0, encoded.getShort()); // reserved ticket
        assertEquals("my-exchange", FieldTable.getShortString(encoded));
        assertEquals("topic", FieldTable.getShortString(encoded));
        byte bits = encoded.get();
        assertFalse(AMQPBits.unpack(bits, 0)); // passive
        assertTrue(AMQPBits.unpack(bits, 1));  // durable
        int argsLen = encoded.getInt();
        FieldTable decodedArgs = FieldTable.decode(encoded, argsLen);
        assertEquals(1, decodedArgs.get("x-arg"));
        assertFalse(encoded.hasRemaining());
    }

    @Test
    public void testQueueDeclareOkRoundTrip() throws AMQPProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(1 + 8 + 4 + 4);
        FieldTable.putShortString(buf, "my-queue");
        buf.putInt(42);
        buf.putInt(3);
        buf.flip();

        QueueMethods.DeclareOk result = QueueMethods.decodeDeclareOk(buf);
        assertEquals("my-queue", result.queue);
        assertEquals(42L, result.messageCount);
        assertEquals(3L, result.consumerCount);
    }

    @Test
    public void testQueueBindEncodesFields() throws AMQPProtocolException {
        ByteBuffer encoded = QueueMethods.encodeBind("q1", "ex1", "rk1", false, null);
        stripHeader(encoded, AMQPMethod.CLASS_QUEUE, AMQPMethod.QUEUE_BIND);
        encoded.getShort(); // reserved ticket
        assertEquals("q1", FieldTable.getShortString(encoded));
        assertEquals("ex1", FieldTable.getShortString(encoded));
        assertEquals("rk1", FieldTable.getShortString(encoded));
    }

    // ── basic ──

    @Test
    public void testBasicPublishEncodesFields() throws AMQPProtocolException {
        ByteBuffer encoded = BasicMethods.encodePublish("ex1", "rk1", true, false);
        stripHeader(encoded, AMQPMethod.CLASS_BASIC, AMQPMethod.BASIC_PUBLISH);
        encoded.getShort(); // reserved ticket
        assertEquals("ex1", FieldTable.getShortString(encoded));
        assertEquals("rk1", FieldTable.getShortString(encoded));
        byte bits = encoded.get();
        assertTrue(AMQPBits.unpack(bits, 0)); // mandatory
        assertFalse(AMQPBits.unpack(bits, 1)); // immediate
    }

    @Test
    public void testBasicDeliverRoundTrip() throws AMQPProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(1 + 14 + 8 + 1 + 1 + 3 + 1 + 3);
        FieldTable.putShortString(buf, "consumer-tag-1");
        buf.putLong(99L);
        buf.put(AMQPBits.pack(true));
        FieldTable.putShortString(buf, "ex1");
        FieldTable.putShortString(buf, "rk1");
        buf.flip();

        BasicMethods.Deliver deliver = BasicMethods.decodeDeliver(buf);
        assertEquals("consumer-tag-1", deliver.consumerTag);
        assertEquals(99L, deliver.deliveryTag);
        assertTrue(deliver.redelivered);
        assertEquals("ex1", deliver.exchange);
        assertEquals("rk1", deliver.routingKey);
    }

    @Test
    public void testBasicConsumeOkRoundTrip() throws AMQPProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(1 + 3);
        FieldTable.putShortString(buf, "tag");
        buf.flip();
        assertEquals("tag", BasicMethods.decodeConsumeOk(buf));
    }

    @Test
    public void testBasicAckRoundTrip() {
        ByteBuffer encoded = BasicMethods.encodeAck(55L, true);
        stripHeader(encoded, AMQPMethod.CLASS_BASIC, AMQPMethod.BASIC_ACK);
        BasicMethods.Ack ack = BasicMethods.decodeAck(encoded);
        assertEquals(55L, ack.deliveryTag);
        assertTrue(ack.multiple);
    }

    @Test
    public void testBasicNackRoundTrip() {
        ByteBuffer encoded = BasicMethods.encodeNack(77L, false, true);
        stripHeader(encoded, AMQPMethod.CLASS_BASIC, AMQPMethod.BASIC_NACK);
        BasicMethods.Nack nack = BasicMethods.decodeNack(encoded);
        assertEquals(77L, nack.deliveryTag);
        assertFalse(nack.multiple);
        assertTrue(nack.requeue);
    }

    @Test
    public void testBasicRejectEncodesFields() {
        ByteBuffer encoded = BasicMethods.encodeReject(33L, true);
        stripHeader(encoded, AMQPMethod.CLASS_BASIC, AMQPMethod.BASIC_REJECT);
        assertEquals(33L, encoded.getLong());
        assertTrue(AMQPBits.unpack(encoded.get(), 0));
    }

    @Test
    public void testBasicGetOkRoundTrip() throws AMQPProtocolException {
        ByteBuffer buf = ByteBuffer.allocate(8 + 1 + 1 + 3 + 1 + 3 + 4);
        buf.putLong(11L);
        buf.put(AMQPBits.pack(false));
        FieldTable.putShortString(buf, "ex1");
        FieldTable.putShortString(buf, "rk1");
        buf.putInt(5);
        buf.flip();

        BasicMethods.GetOk getOk = BasicMethods.decodeGetOk(buf);
        assertEquals(11L, getOk.deliveryTag);
        assertFalse(getOk.redelivered);
        assertEquals("ex1", getOk.exchange);
        assertEquals("rk1", getOk.routingKey);
        assertEquals(5L, getOk.messageCount);
    }

    // ── tx / confirm ──

    @Test
    public void testTxMethodsHaveNoArguments() {
        ByteBuffer select = TxMethods.encodeSelect();
        stripHeader(select, AMQPMethod.CLASS_TX, AMQPMethod.TX_SELECT);
        assertFalse(select.hasRemaining());

        ByteBuffer commit = TxMethods.encodeCommit();
        stripHeader(commit, AMQPMethod.CLASS_TX, AMQPMethod.TX_COMMIT);
        assertFalse(commit.hasRemaining());

        ByteBuffer rollback = TxMethods.encodeRollback();
        stripHeader(rollback, AMQPMethod.CLASS_TX, AMQPMethod.TX_ROLLBACK);
        assertFalse(rollback.hasRemaining());
    }

    @Test
    public void testConfirmSelectEncodesNoWait() {
        ByteBuffer encoded = ConfirmMethods.encodeSelect(true);
        stripHeader(encoded, AMQPMethod.CLASS_CONFIRM, AMQPMethod.CONFIRM_SELECT);
        assertTrue(AMQPBits.unpack(encoded.get(), 0));
    }
}
