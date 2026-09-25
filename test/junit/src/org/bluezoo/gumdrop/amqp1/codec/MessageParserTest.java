/*
 * MessageParserTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

/**
 * Tests for {@link MessageParser}, {@link MessageWriter} and the header
 * and properties sections, including input split at every boundary.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageParserTest {

    // ── building messages ──

    private static byte[] body(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    private static void appendData(ByteArrayOutputStream out, byte[] data) {
        ByteBuffer prefix = MessageWriter.dataSectionPrefix(data.length);
        byte[] p = new byte[prefix.remaining()];
        prefix.get(p);
        out.write(p, 0, p.length);
        out.write(data, 0, data.length);
    }

    private static byte[] bytes(Amqp1Encoder e) {
        return e.toByteArray();
    }

    /** A message using every section type that can coexist. */
    private static byte[] fullMessage() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageHeader h = new MessageHeader();
        h.setDurable(true);
        h.setPriority(7);
        h.setTtl(60000);
        h.setDeliveryCount(2);
        MessageWriter.writeHeader(e, h);
        Map<Object, Object> da = new LinkedHashMap<Object, Object>();
        da.put(new Amqp1Symbol("x-da"), "d");
        MessageWriter.writeDeliveryAnnotations(e, da);
        Map<Object, Object> ma = new LinkedHashMap<Object, Object>();
        ma.put(new Amqp1Symbol("x-opt-routing"), "r1");
        MessageWriter.writeMessageAnnotations(e, ma);
        MessageProperties p = new MessageProperties();
        p.setMessageId("msg-1");
        p.setTo("queue://orders");
        p.setSubject("hello");
        p.setContentType("text/plain");
        MessageWriter.writeProperties(e, p);
        Map<Object, Object> ap = new LinkedHashMap<Object, Object>();
        ap.put("colour", "red");
        ap.put("count", Integer.valueOf(3));
        MessageWriter.writeApplicationProperties(e, ap);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = bytes(e);
        out.write(head, 0, head.length);
        appendData(out, body(10, 1));
        appendData(out, body(300, 50));
        Amqp1Encoder f = new Amqp1Encoder();
        Map<Object, Object> footer = new LinkedHashMap<Object, Object>();
        footer.put(new Amqp1Symbol("x-sig"), "s");
        MessageWriter.writeFooter(f, footer);
        byte[] foot = bytes(f);
        out.write(foot, 0, foot.length);
        return out.toByteArray();
    }

    // ── parse helpers ──

    private static Recorder parseWhole(byte[] message) {
        Recorder r = new Recorder();
        MessageParser p = new MessageParser(r);
        p.receive(ByteBuffer.wrap(message));
        p.endMessage();
        return r;
    }

    private static Recorder parseInChunks(byte[] message, int chunk) {
        Recorder r = new Recorder();
        MessageParser p = new MessageParser(r);
        for (int off = 0; off < message.length; off += chunk) {
            ByteBuffer b = ByteBuffer.wrap(message, off, Math.min(chunk, message.length - off));
            p.receive(b);
            assertFalse("parser must consume the whole chunk", b.hasRemaining());
        }
        p.endMessage();
        return r;
    }

    private void assertFullMessage(Recorder r, String context) {
        assertNull(context + ": " + r.error, r.error);
        assertTrue(context, r.ended);
        assertEquals(context, Arrays.asList("header", "da", "ma", "properties", "ap",
                "data 10", "data 300", "footer", "end"), r.events);
        assertEquals(context, 7, r.header.getPriority());
        assertTrue(r.header.isDurable());
        assertEquals(60000L, r.header.getTtl());
        assertEquals(2L, r.header.getDeliveryCount());
        assertEquals("msg-1", r.properties.getMessageId());
        assertEquals("queue://orders", r.properties.getTo());
        assertEquals("text/plain", r.properties.getContentType());
        assertEquals("red", r.applicationProperties.get("colour"));
        assertEquals(Integer.valueOf(3), r.applicationProperties.get("count"));
        assertEquals("r1", r.messageAnnotations.get(new Amqp1Symbol("x-opt-routing")));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(body(10, 1), 0, 10);
        expected.write(body(300, 50), 0, 300);
        assertArrayEquals(context, expected.toByteArray(), r.dataBytes.toByteArray());
    }

    // ── tests ──

    @Test
    public void testFullMessageInOneChunk() {
        assertFullMessage(parseWhole(fullMessage()), "whole");
    }

    @Test
    public void testFullMessageSplitAtEveryBoundary() {
        byte[] message = fullMessage();
        for (int split = 1; split < message.length; split++) {
            Recorder r = new Recorder();
            MessageParser p = new MessageParser(r);
            p.receive(ByteBuffer.wrap(message, 0, split));
            p.receive(ByteBuffer.wrap(message, split, message.length - split));
            p.endMessage();
            assertFullMessage(r, "split at " + split);
        }
    }

    @Test
    public void testFullMessageOneByteAtATime() {
        assertFullMessage(parseInChunks(fullMessage(), 1), "byte by byte");
    }

    @Test
    public void testFullMessageInAwkwardChunkSizes() {
        byte[] message = fullMessage();
        for (int size = 2; size <= 64; size++) {
            assertFullMessage(parseInChunks(message, size), "chunks of " + size);
        }
    }

    @Test
    public void testDataIsStreamedNotBuffered() {
        // a 1 MB body under a 1 KiB section limit: only streaming can carry it
        byte[] data = body(1 << 20, 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appendData(out, data);
        byte[] message = out.toByteArray();
        Recorder r = new Recorder();
        MessageParser p = new MessageParser(r, 1024);
        int chunk = 4096;
        int delivered = 0;
        for (int off = 0; off < message.length; off += chunk) {
            int n = Math.min(chunk, message.length - off);
            p.receive(ByteBuffer.wrap(message, off, n));
            delivered += n;
            if (delivered > 2 * chunk) {
                assertTrue("body octets are forwarded as they arrive",
                        r.dataBytes.size() >= delivered - 16);
            }
        }
        p.endMessage();
        assertNull(r.error, r.error);
        assertEquals(data.length, r.dataBytes.size());
        assertArrayEquals(data, r.dataBytes.toByteArray());
        assertTrue("many chunks, not one", r.dataChunks > 100);
    }

    @Test
    public void testEmptyDataSection() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appendData(out, new byte[0]);
        Recorder r = parseWhole(out.toByteArray());
        assertEquals(Arrays.asList("data 0", "end"), r.events);
        assertEquals(0, r.dataBytes.size());
    }

    @Test
    public void testDataSectionLengthEncodings() {
        assertEquals("005375" + "a003", hex(MessageWriter.dataSectionPrefix(3)));
        assertEquals("005375" + "a0ff", hex(MessageWriter.dataSectionPrefix(255)));
        assertEquals("005375" + "b000000100", hex(MessageWriter.dataSectionPrefix(256)));
        assertEquals("005375" + "b00001e240", hex(MessageWriter.dataSectionPrefix(123456)));
    }

    private static String hex(ByteBuffer b) {
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        return Amqp1TypesTest.toHex(bytes);
    }

    @Test
    public void testHeaderDefaults() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeHeader(e, new MessageHeader());
        Recorder r = parseWhole(bytes(e));
        assertFalse(r.header.isDurable());
        assertEquals(MessageHeader.DEFAULT_PRIORITY, r.header.getPriority());
        assertEquals(0L, r.header.getTtl());
        assertFalse(r.header.isFirstAcquirer());
        assertEquals(0L, r.header.getDeliveryCount());
    }

    @Test
    public void testPropertiesRoundTrip() {
        MessageProperties p = new MessageProperties();
        UUID id = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        p.setMessageId(id);
        p.setUserId(new byte[] {1, 2});
        p.setTo("to");
        p.setSubject("subject");
        p.setReplyTo("reply");
        p.setCorrelationId(Long.valueOf(42));
        p.setContentType("application/json");
        p.setContentEncoding("gzip");
        p.setAbsoluteExpiryTime(new Date(1800000000000L));
        p.setCreationTime(new Date(1700000000000L));
        p.setGroupId("g");
        p.setGroupSequence(Long.valueOf(5));
        p.setReplyToGroupId("rg");
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeProperties(e, p);
        MessageProperties d = parseWhole(bytes(e)).properties;
        assertEquals(id, d.getMessageId());
        assertArrayEquals(new byte[] {1, 2}, d.getUserId());
        assertEquals("to", d.getTo());
        assertEquals("subject", d.getSubject());
        assertEquals("reply", d.getReplyTo());
        assertEquals(Long.valueOf(42), d.getCorrelationId());
        assertEquals("application/json", d.getContentType());
        assertEquals("gzip", d.getContentEncoding());
        assertEquals(new Date(1800000000000L), d.getAbsoluteExpiryTime());
        assertEquals(new Date(1700000000000L), d.getCreationTime());
        assertEquals("g", d.getGroupId());
        assertEquals(Long.valueOf(5), d.getGroupSequence());
        assertEquals("rg", d.getReplyToGroupId());
    }

    @Test
    public void testMessageIdTypes() {
        Object[] ids = {Long.valueOf(7), "text-id", UUID.randomUUID(), new byte[] {9, 9}};
        for (int i = 0; i < ids.length; i++) {
            MessageProperties p = new MessageProperties();
            p.setMessageId(ids[i]);
            Amqp1Encoder e = new Amqp1Encoder();
            MessageWriter.writeProperties(e, p);
            Object got = parseWhole(bytes(e)).properties.getMessageId();
            if (ids[i] instanceof byte[]) {
                assertArrayEquals((byte[]) ids[i], (byte[]) got);
            } else {
                assertEquals(ids[i], got);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidMessageIdTypeRejected() {
        MessageProperties p = new MessageProperties();
        p.setMessageId(Double.valueOf(1.5));
        MessageWriter.writeProperties(new Amqp1Encoder(), p);
    }

    @Test
    public void testAmqpValueBody() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeAmqpValue(e, "a string body");
        Recorder r = parseWhole(bytes(e));
        assertEquals("a string body", r.value);
        assertEquals(Arrays.asList("value", "end"), r.events);
    }

    @Test
    public void testAmqpValueMapBody() {
        Map<Object, Object> m = new LinkedHashMap<Object, Object>();
        m.put("k", Long.valueOf(1));
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeAmqpValue(e, m);
        assertEquals(m, parseWhole(bytes(e)).value);
    }

    @Test
    public void testAmqpValueNull() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeAmqpValue(e, null);
        Recorder r = parseWhole(bytes(e));
        assertNull(r.value);
        assertEquals(Arrays.asList("value", "end"), r.events);
    }

    @Test
    public void testAmqpSequenceBody() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeAmqpSequence(e, Arrays.<Object>asList("a", Long.valueOf(1)));
        MessageWriter.writeAmqpSequence(e, Arrays.<Object>asList("b"));
        Recorder r = parseWhole(bytes(e));
        assertEquals(Arrays.asList("seq", "seq", "end"), r.events);
        assertEquals(Arrays.<Object>asList("b"), r.rows.get(1));
    }

    @Test
    public void testEmptyMessageIsAccepted() {
        Recorder r = parseWhole(new byte[0]);
        assertEquals(Arrays.asList("end"), r.events);
    }

    // ── ordering and validity ──

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            out.write(parts[i], 0, parts[i].length);
        }
        return out.toByteArray();
    }

    private static byte[] header() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeHeader(e, new MessageHeader());
        return bytes(e);
    }

    private static byte[] properties() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeProperties(e, new MessageProperties());
        return bytes(e);
    }

    private static byte[] value() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeAmqpValue(e, "v");
        return bytes(e);
    }

    private static byte[] data() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appendData(out, new byte[] {1});
        return out.toByteArray();
    }

    private static byte[] footer() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeFooter(e, new LinkedHashMap<Object, Object>());
        return bytes(e);
    }

    private static void assertError(byte[] message, String fragment) {
        Recorder r = parseWhole(message);
        assertTrue("expected an error", r.error != null);
        assertTrue(r.error, r.error.contains(fragment));
        assertFalse(r.ended);
    }

    @Test
    public void testSectionsOutOfOrderRejected() {
        assertError(concat(properties(), header()), "out of order");
    }

    @Test
    public void testRepeatedSectionRejected() {
        assertError(concat(header(), header()), "out of order or repeated");
    }

    @Test
    public void testBodyAfterFooterRejected() {
        assertError(concat(footer(), data()), "after the footer");
    }

    @Test
    public void testMixedBodyTypesRejected() {
        assertError(concat(data(), value()), "Mixed body");
    }

    @Test
    public void testSecondAmqpValueRejected() {
        assertError(concat(value(), value()), "More than one amqp-value");
    }

    @Test
    public void testMultipleDataSectionsAllowed() {
        Recorder r = parseWhole(concat(data(), data(), data()));
        assertNull(r.error);
        assertEquals(Arrays.asList("data 1", "data 1", "data 1", "end"), r.events);
    }

    @Test
    public void testUndescribedSectionRejected() {
        assertError(new byte[] {(byte) 0xA1, 0x01, 'x'}, "not a described type");
    }

    @Test
    public void testUnknownSectionRejected() {
        assertError(Amqp1TypesTest.hex("005399" + "45"), "Unknown message section");
    }

    @Test
    public void testSymbolicDescriptorRejected() {
        assertError(Amqp1TypesTest.hex("00a3016f" + "45"), "Unsupported message section descriptor");
    }

    @Test
    public void testDataSectionThatIsNotBinaryRejected() {
        assertError(Amqp1TypesTest.hex("005375" + "a10161"), "not a binary");
    }

    @Test
    public void testSectionOfWrongShapeRejected() {
        // a header that is a string instead of a list
        assertError(Amqp1TypesTest.hex("005370" + "a10161"), "not a list");
        // application-properties that is a list
        assertError(Amqp1TypesTest.hex("005374" + "45"), "not a map");
    }

    @Test
    public void testOversizeSectionRejectedBeforeItArrives() {
        // amqp-value binary claiming 1 MB, limit 1 KiB: rejected from the header
        Recorder r = new Recorder();
        MessageParser p = new MessageParser(r, 1024);
        p.receive(ByteBuffer.wrap(Amqp1TypesTest.hex("005377" + "b0" + "00100000")));
        assertTrue(r.error.contains("exceeds limit"));
    }

    @Test
    public void testTruncatedMessageRejected() {
        byte[] whole = fullMessage();
        // cut inside the properties section (a small section)
        Recorder r = new Recorder();
        MessageParser p = new MessageParser(r);
        int cut = header().length + 3;
        p.receive(ByteBuffer.wrap(whole, 0, cut));
        p.endMessage();
        assertTrue(r.error.contains("ends inside a section"));
    }

    @Test
    public void testMessageEndingInsideDataRejected() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appendData(out, body(50, 0));
        byte[] message = out.toByteArray();
        Recorder r = new Recorder();
        MessageParser p = new MessageParser(r);
        p.receive(ByteBuffer.wrap(message, 0, message.length - 10));
        p.endMessage();
        assertTrue(r.error.contains("ends inside a section"));
        assertFalse(r.ended);
    }

    @Test
    public void testNothingDeliveredAfterAnError() {
        Recorder r = new Recorder();
        MessageParser p = new MessageParser(r);
        p.receive(ByteBuffer.wrap(concat(properties(), header())));
        assertTrue(r.error != null);
        int events = r.events.size();
        p.receive(ByteBuffer.wrap(data()));
        p.endMessage();
        assertEquals(events, r.events.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullHandlerRejected() {
        new MessageParser(null);
    }

    // ── recorder ──

    private static final class Recorder implements MessageHandler {
        final List<String> events = new ArrayList<String>();
        final ByteArrayOutputStream dataBytes = new ByteArrayOutputStream();
        final List<List<Object>> rows = new ArrayList<List<Object>>();
        int dataChunks;
        boolean ended;
        String error;
        MessageHeader header;
        MessageProperties properties;
        Map<Object, Object> applicationProperties;
        Map<Object, Object> messageAnnotations;
        Object value;
        private long dataLength;
        private long dataSeen;

        @Override
        public void header(MessageHeader h) {
            events.add("header");
            header = h;
        }

        @Override
        public void deliveryAnnotations(Map<Object, Object> m) {
            events.add("da");
        }

        @Override
        public void messageAnnotations(Map<Object, Object> m) {
            events.add("ma");
            messageAnnotations = m;
        }

        @Override
        public void properties(MessageProperties p) {
            events.add("properties");
            properties = p;
        }

        @Override
        public void applicationProperties(Map<Object, Object> m) {
            events.add("ap");
            applicationProperties = m;
        }

        @Override
        public void startData(long length) {
            dataLength = length;
            dataSeen = 0;
        }

        @Override
        public void dataChunk(ByteBuffer chunk) {
            dataChunks++;
            byte[] b = new byte[chunk.remaining()];
            chunk.get(b);
            dataBytes.write(b, 0, b.length);
            dataSeen += b.length;
        }

        @Override
        public void endData() {
            assertEquals("declared length matches streamed octets", dataLength, dataSeen);
            events.add("data " + dataLength);
        }

        @Override
        public void amqpSequence(List<Object> row) {
            events.add("seq");
            rows.add(row);
        }

        @Override
        public void amqpValue(Object v) {
            events.add("value");
            value = v;
        }

        @Override
        public void footer(Map<Object, Object> m) {
            events.add("footer");
        }

        @Override
        public void endMessage() {
            events.add("end");
            ended = true;
        }

        @Override
        public void messageError(String message) {
            error = message;
        }
    }
}
