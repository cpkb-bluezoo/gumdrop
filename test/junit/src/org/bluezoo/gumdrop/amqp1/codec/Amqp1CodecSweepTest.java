/*
 * Amqp1CodecSweepTest.java
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Truncation and corruption sweeps over the AMQP 1.0 decoders: the typed
 * value decoder, every performative, the message-section parser and the
 * frame parser. Only {@link Amqp1ProtocolException} may escape from the
 * value and performative decoders; the push parsers report problems
 * through their handlers and must never throw.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1CodecSweepTest {

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static void assertNone(List<String> failures) {
        assertTrue(failures.toString(), failures.isEmpty());
    }

    private static void sweepPerformative(Performative p) {
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                PerformativeCodec.decode(ByteBuffer.wrap(input));
            }
        };
        assertNone(TruncationSweep.allFailures(bytes(p.encode()), target,
                Amqp1ProtocolException.class));
    }

    @Test
    public void performativesOnlyThrowProtocolException() {
        Open open = new Open("c1");
        open.setHostname("h");
        open.getOfferedCapabilities().add("cap");
        open.getProperties().put(new Amqp1Symbol("k"), "v");
        sweepPerformative(open);
        sweepPerformative(new Begin(1, 2048, 2048));
        Attach attach = new Attach("link", 3, true);
        Source source = new Source("src");
        source.setDefaultOutcome(DeliveryState.accepted());
        attach.setSource(source);
        attach.setTarget(new Target("tgt"));
        attach.setInitialDeliveryCount(Long.valueOf(0));
        attach.setMaxMessageSize(1000);
        attach.getUnsettled().put(new byte[] {1, 2}, null);
        sweepPerformative(attach);
        Flow flow = new Flow(Long.valueOf(1), 10, 2, 10);
        flow.setLink(1, 5, 7);
        sweepPerformative(flow);
        Transfer transfer = new Transfer(1);
        transfer.setFirst(9, new byte[] {1, 2, 3}, false);
        transfer.setMore(true);
        transfer.setState(DeliveryState.received(1, 2));
        sweepPerformative(transfer);
        Disposition disposition = new Disposition(true, 1, Long.valueOf(4));
        disposition.setState(DeliveryState.rejected(new Amqp1Error(Amqp1Error.NOT_FOUND, "no")));
        sweepPerformative(disposition);
        sweepPerformative(new Detach(1, true, new Amqp1Error(Amqp1Error.DETACH_FORCED, "x")));
        sweepPerformative(new End(new Amqp1Error(Amqp1Error.ILLEGAL_STATE)));
        sweepPerformative(new Close(new Amqp1Error(Amqp1Error.CONNECTION_FORCED, "bye")));
        List<String> mechs = new ArrayList<String>();
        mechs.add("PLAIN");
        mechs.add("ANONYMOUS");
        sweepPerformative(new SaslMechanisms(mechs));
        sweepPerformative(new SaslInit("PLAIN", new byte[] {0, 'a', 0, 'b'}));
        sweepPerformative(new SaslChallenge(new byte[] {1}));
        sweepPerformative(new SaslResponse(new byte[] {2}));
        sweepPerformative(new SaslOutcome(0, new byte[] {3}));
    }

    @Test
    public void valueDecoderOnlyThrowsProtocolException() {
        Amqp1Encoder e = new Amqp1Encoder();
        Map<Object, Object> map = new LinkedHashMap<Object, Object>();
        map.put("a", Integer.valueOf(1));
        map.put(new Amqp1Symbol("s"), Long.valueOf(1L << 40));
        List<Object> list = new ArrayList<Object>();
        list.add(Boolean.TRUE);
        list.add("str");
        list.add(new byte[] {1, 2, 3});
        list.add(new Date(5000));
        list.add(UUID.randomUUID());
        list.add(Double.valueOf(1.5));
        list.add(map);
        e.writeList(list);
        e.writeSymbolArray(java.util.Arrays.asList("x", "yy"));
        e.writeDescriptor(7);
        e.writeList(list);
        byte[] valid = e.toByteArray();
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                ByteBuffer buf = ByteBuffer.wrap(input);
                Amqp1Decoder.encodedLength(buf);
                while (buf.hasRemaining()) {
                    Amqp1Decoder.read(buf);
                }
            }
        };
        assertNone(TruncationSweep.allFailures(valid, target, Amqp1ProtocolException.class));
    }

    @Test
    public void hostileCompoundHeadersAreRejected() {
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                ByteBuffer buf = ByteBuffer.wrap(input);
                Amqp1Decoder.encodedLength(buf);
                Amqp1Decoder.read(buf);
            }
        };
        byte[][] hostile = {
            {(byte) 0xd0, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff},
            {(byte) 0xd0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0},
            {(byte) 0xd1, 0, 0, 0, 4, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
            {(byte) 0xf0, 0, 0, 0, 5, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x40},
            {(byte) 0xb1, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
            {(byte) 0xb1, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff},
        };
        for (int i = 0; i < hostile.length; i++) {
            assertNone(TruncationSweep.inputFailures(target, hostile[i], "hostile " + i,
                    Amqp1ProtocolException.class));
        }
    }

    @Test
    public void deeplyNestedValuesAreRejected() {
        // 40000 nested one-element lists: list8 size 3? use list32 for exactness
        byte[] body = new byte[] {0x40};
        for (int i = 0; i < 40000 && body.length < 200000; i++) {
            ByteBuffer b = ByteBuffer.allocate(body.length + 9);
            b.put((byte) 0xd0).putInt(body.length + 4).putInt(1).put(body);
            body = b.array();
        }
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                ByteBuffer buf = ByteBuffer.wrap(input);
                Amqp1Decoder.encodedLength(buf);
                Amqp1Decoder.read(buf);
            }
        };
        assertNone(TruncationSweep.inputFailures(target, body, "nest",
                Amqp1ProtocolException.class));
    }

    /** Ignores everything. */
    private static final class NullMessageHandler implements MessageHandler {
        public void header(MessageHeader header) { }
        public void deliveryAnnotations(Map<Object, Object> annotations) { }
        public void messageAnnotations(Map<Object, Object> annotations) { }
        public void properties(MessageProperties properties) { }
        public void applicationProperties(Map<Object, Object> properties) { }
        public void startData(long length) { }
        public void dataChunk(ByteBuffer chunk) { }
        public void endData() { }
        public void amqpSequence(List<Object> row) { }
        public void amqpValue(Object value) { }
        public void footer(Map<Object, Object> footer) { }
        public void endMessage() { }
        public void messageError(String message) { }
    }

    @Test
    public void messageParserNeverThrows() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageHeader header = new MessageHeader();
        header.setDurable(true);
        header.setTtl(1000);
        MessageWriter.writeHeader(e, header);
        MessageProperties props = new MessageProperties();
        props.setMessageId("id");
        props.setTo("to");
        props.setSubject("subj");
        MessageWriter.writeProperties(e, props);
        Map<String, Object> app = new LinkedHashMap<String, Object>();
        app.put("k", "v");
        MessageWriter.writeApplicationProperties(e, app);
        byte[] data = new byte[] {1, 2, 3, 4};
        byte[] prefix = bytes(MessageWriter.dataSectionPrefix(data.length));
        e.writeRaw(prefix, 0, prefix.length);
        e.writeRaw(data, 0, data.length);
        MessageWriter.writeAmqpValue(e, "tail");
        MessageWriter.writeFooter(e, app);
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) {
                MessageParser parser = new MessageParser(new NullMessageHandler());
                parser.receive(ByteBuffer.wrap(input));
                parser.endMessage();
            }
        };
        assertNone(TruncationSweep.allFailures(e.toByteArray(), target));
    }

    @Test
    public void frameParserNeverThrows() {
        byte[] body = bytes(new Close().encode());
        ByteBuffer b = ByteBuffer.allocate(8 + 8 + body.length);
        b.put(new byte[] {'A', 'M', 'Q', 'P', 0, 1, 0, 0});
        b.putInt(8 + body.length).put((byte) 2).put((byte) 0).putShort((short) 0);
        b.put(body);
        TruncationSweep.Target target = new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) {
                Amqp1FrameParser parser = new Amqp1FrameParser(new Amqp1FrameHandler() {
                    public void protocolHeader(int protocolId, int major, int minor, int revision) { }
                    public void startFrame(int type, int channel, int bodyLength) { }
                    public void frameBody(ByteBuffer chunk) { }
                    public void endFrame() { }
                    public void heartbeat(int channel) { }
                    public void frameError(String message) { }
                });
                parser.expectProtocolHeader();
                parser.receive(ByteBuffer.wrap(input));
            }
        };
        assertNone(TruncationSweep.allFailures(b.array(), target));
    }
}
