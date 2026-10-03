/*
 * Amqp091CodecSweepTest.java
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

import java.math.BigDecimal;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Truncation, corruption and hostile-length sweeps over the AMQP 0-9-1
 * decoders: the frame parser, field tables, content headers and the method
 * argument decoders. Field tables and content headers may only throw
 * {@link AmqpProtocolException}; the method decoders may additionally
 * throw {@link BufferUnderflowException}, which the client handler treats
 * as a protocol error.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp091CodecSweepTest {

    private static final int FRAME = 0;
    private static final int TABLE = 1;
    private static final int HEADER = 2;
    private static final int START = 3;
    private static final int START_OK = 4;
    private static final int SECURE = 5;
    private static final int TUNE = 6;
    private static final int CLOSE = 7;
    private static final int OPEN_OK = 8;
    private static final int DELIVER = 9;
    private static final int QUEUE_DECLARE = 10;
    private static final int RETURN = 11;
    private static final int PUBLISH = 12;

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    /** Drops the 4-byte class-id and method-id that the dispatcher consumes. */
    private static byte[] args(ByteBuffer method) {
        method.position(method.position() + 4);
        return bytes(method);
    }

    private static FieldTable table() {
        FieldTable nested = new FieldTable().put("n", 1);
        List<Object> list = new ArrayList<Object>();
        list.add("x");
        list.add(Long.valueOf(5));
        list.add(new FieldTable().put("deep", Boolean.TRUE));
        return new FieldTable()
                .put("bool", Boolean.TRUE)
                .put("byte", Byte.valueOf((byte) 1))
                .put("short", Short.valueOf((short) 2))
                .put("int", Integer.valueOf(3))
                .put("long", Long.valueOf(4))
                .put("float", Float.valueOf(1.5f))
                .put("double", Double.valueOf(2.5))
                .put("dec", new BigDecimal("12.34"))
                .put("str", "hello")
                .put("bytes", new byte[] {1, 2, 3})
                .put("ts", new Date(1000000L))
                .put("nested", nested)
                .put("list", list)
                .put("void", null);
    }

    private static void decode(int which, byte[] input) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(input);
        switch (which) {
            case FRAME:
                AmqpFrameParser p = new AmqpFrameParser(new AmqpFrameHandler() {
                    public void methodFrame(int channel, ByteBuffer payload) { }
                    public void headerFrame(int channel, ByteBuffer payload) { }
                    public void bodyFrame(int channel, ByteBuffer payload) { }
                    public void heartbeatFrame() { }
                    public void frameError(String message) { }
                });
                p.receive(buf);
                break;
            case TABLE:
                FieldTable.decode(buf, input.length);
                break;
            case HEADER:
                BasicProperties.decode(buf);
                break;
            case START:
                ConnectionMethods.decodeStart(buf);
                break;
            case START_OK:
                ConnectionMethods.decodeStartOk(buf);
                break;
            case SECURE:
                ConnectionMethods.decodeSecure(buf);
                break;
            case TUNE:
                ConnectionMethods.decodeTune(buf);
                break;
            case CLOSE:
                ConnectionMethods.decodeClose(buf);
                break;
            case OPEN_OK:
                ChannelMethods.decodeOpenOk(buf);
                break;
            case DELIVER:
                BasicMethods.decodeDeliver(buf);
                break;
            case QUEUE_DECLARE:
                QueueMethods.decodeDeclare(buf);
                break;
            case RETURN:
                BasicMethods.decodeReturn(buf);
                break;
            case PUBLISH:
                BasicMethods.decodePublish(buf);
                break;
            default:
                break;
        }
    }

    private static void sweep(final int which, byte[] valid, Class<?>... allowed) {
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                Amqp091CodecSweepTest.decode(which, input);
            }
        };
        List<String> failures = TruncationSweep.allFailures(valid, target, allowed);
        assertTrue(failures.toString(), failures.isEmpty());
    }

    /** Decodes exactly this input once (no sweep), for large hostile inputs. */
    private static void single(final int which, byte[] input, Class<?>... allowed) {
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] in) throws Exception {
                Amqp091CodecSweepTest.decode(which, in);
            }
        };
        List<String> failures = TruncationSweep.inputFailures(target, input, "single", allowed);
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void frameParserNeverThrows() {
        byte[] frame = bytes(AmqpFrame.encodeHeartbeat());
        sweep(FRAME, frame);
        sweep(FRAME, new byte[] {1, 0, 1, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0});
    }

    @Test
    public void fieldTableOnlyThrowsProtocolException() {
        sweep(TABLE, bytes(table().encode()), AmqpProtocolException.class);
    }

    @Test
    public void hostileFieldTableLengthsAreRejected() {
        // byte-array value with a 2GB length and with a negative length
        sweep(TABLE, new byte[] {1, 'a', 'x', 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                AmqpProtocolException.class);
        sweep(TABLE, new byte[] {1, 'a', 'x', (byte) 0x80, 0, 0, 0},
                AmqpProtocolException.class);
        // array and table values with negative lengths
        sweep(TABLE, new byte[] {1, 'a', 'A', (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                AmqpProtocolException.class);
        sweep(TABLE, new byte[] {1, 'a', 'F', (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                AmqpProtocolException.class);
    }

    @Test
    public void deeplyNestedFieldTableIsRejected() {
        // each level is: name "a" (1,'a'), tag 'F', int length, content
        byte[] body = new byte[0];
        for (int i = 0; i < 20000; i++) {
            ByteBuffer b = ByteBuffer.allocate(body.length + 7);
            b.put((byte) 1).put((byte) 'a').put((byte) 'F').putInt(body.length).put(body);
            body = b.array();
        }
        single(TABLE, body, AmqpProtocolException.class);
    }

    @Test
    public void deeplyNestedFieldArrayIsRejected() {
        // array of array of array ... : name "a", tag 'A', length, then tag 'A' ...
        byte[] body = new byte[0];
        for (int i = 0; i < 20000; i++) {
            ByteBuffer b = ByteBuffer.allocate(body.length + 5);
            b.put((byte) 'A').putInt(body.length).put(body);
            body = b.array();
        }
        ByteBuffer top = ByteBuffer.allocate(body.length + 2);
        top.put((byte) 1).put((byte) 'a').put(body);
        single(TABLE, top.array(), AmqpProtocolException.class);
    }

    @Test
    public void contentHeaderOnlyThrowsProtocolException() {
        BasicProperties props = new BasicProperties()
                .withContentType("text/plain")
                .withContentEncoding("utf-8")
                .withHeaders(table())
                .withDeliveryMode((byte) 2)
                .withPriority((byte) 3)
                .withCorrelationId("c")
                .withReplyTo("r")
                .withExpiration("60000")
                .withMessageId("m")
                .withTimestamp(new Date(2000000L))
                .withType("t")
                .withUserId("u")
                .withAppId("a");
        sweep(HEADER, bytes(props.encode(10)), AmqpProtocolException.class);
        // continuation flag chain running off the end
        sweep(HEADER, new byte[] {0, 60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1},
                AmqpProtocolException.class);
    }

    @Test
    public void methodDecodersOnlyThrowProtocolOrUnderflow() {
        Class<?>[] ok = {AmqpProtocolException.class, BufferUnderflowException.class};
        sweep(START, args(ConnectionMethods.encodeStart(0, 9, table(), "PLAIN AMQPLAIN", "en_US")), ok);
        sweep(START_OK, args(ConnectionMethods.encodeStartOk(table(), "PLAIN",
                new byte[] {1, 2, 3}, "en_US")), ok);
        sweep(SECURE, args(ConnectionMethods.encodeSecureOk(new byte[] {1, 2, 3})), ok);
        sweep(TUNE, args(ConnectionMethods.encodeTune(10, 131072, 60)), ok);
        sweep(CLOSE, args(ConnectionMethods.encodeClose(320, "bye")), ok);
        sweep(OPEN_OK, args(ChannelMethods.encodeOpenOk()), ok);
        sweep(DELIVER, args(BasicMethods.encodeDeliver("ctag", 5, true, "ex", "rk")), ok);
        sweep(PUBLISH, args(BasicMethods.encodePublish("ex", "rk", true, false)), ok);
        sweep(QUEUE_DECLARE, args(QueueMethods.encodeDeclare("q", false, true, false, false,
                false, table())), ok);
        sweep(RETURN, new byte[] {1, 0x40, 2, 'n', 'o', 2, 'e', 'x', 2, 'r', 'k'}, ok);
    }

    @Test
    public void hostileMethodLengthsAreRejectedWithoutAllocation() {
        Class<?>[] ok = {AmqpProtocolException.class, BufferUnderflowException.class};
        // start-ok: empty table, short-string mechanism "", response length 0x7fffffff
        sweep(START_OK, new byte[] {0, 0, 0, 0, 0, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}, ok);
        sweep(START_OK, new byte[] {0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}, ok);
        sweep(SECURE, new byte[] {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}, ok);
        sweep(SECURE, new byte[] {(byte) 0x80, 0, 0, 0}, ok);
        sweep(OPEN_OK, new byte[] {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}, ok);
        sweep(OPEN_OK, new byte[] {(byte) 0x80, 0, 0, 0}, ok);
    }
}
