/*
 * PerformativeReaderTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;

import org.junit.Test;

/**
 * Tests for {@link PerformativeReader} and {@link Amqp1Decoder#encodedLength}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PerformativeReaderTest {

    private static byte[] openBytes() {
        Open o = new Open("container-with-a-fairly-long-identifier");
        o.setHostname("broker.example.org");
        o.setMaxFrameSize(65536);
        o.getOfferedCapabilities().add("ANONYMOUS-RELAY");
        ByteBuffer b = o.encode();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void testEncodedLengthOfEachForm() throws Exception {
        assertEquals(1, len("40"));
        assertEquals(2, len("5201"));
        assertEquals(3, len("610001"));
        assertEquals(5, len("7000000001"));
        assertEquals(9, len("800000000000000001"));
        assertEquals(17, len("98" + "00000000000000000000000000000000"));
        assertEquals(5, len("a10361" + "6263"));
        assertEquals(8, len("b1" + "00000003" + "616263" + "ffff"));
        assertEquals(4, len("c0" + "02" + "01" + "40" + "ff"));
    }

    @Test
    public void testEncodedLengthOfDescribed() throws Exception {
        // 00 53 10 c0 04 01 a1 01 61 : 9 octets
        assertEquals(9, len("005310c00401a10161" + "ffff"));
    }

    @Test
    public void testEncodedLengthNeedsMoreData() throws Exception {
        assertEquals(-1, len(""));
        assertEquals(-1, len("00"));
        assertEquals(-1, len("0053"));
        assertEquals(-1, len("005310"));
        assertEquals(-1, len("005310c0"));
        assertEquals(-1, len("b100"));
        assertEquals(-1, len("d000000"));
    }

    @Test(expected = Amqp1ProtocolException.class)
    public void testEncodedLengthRejectsUnknownCode() throws Exception {
        len("10");
    }

    private static long len(String hex) throws Exception {
        return Amqp1Decoder.encodedLength(ByteBuffer.wrap(Amqp1TypesTest.hex(hex)));
    }

    @Test
    public void testWholePerformativeInOneChunkLeavesPayload() throws Exception {
        byte[] payload = {9, 8, 7};
        ByteBuffer chunk = ByteBuffer.wrap(concat(openBytes(), payload));
        PerformativeReader reader = new PerformativeReader(4096);
        Performative p = reader.receive(chunk);
        assertTrue(p instanceof Open);
        assertEquals("container-with-a-fairly-long-identifier", ((Open) p).getContainerId());
        byte[] rest = new byte[chunk.remaining()];
        chunk.get(rest);
        assertArrayEquals(payload, rest);
    }

    @Test
    public void testPerformativeSplitAtEveryBoundary() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        byte[] body = concat(openBytes(), payload);
        for (int split = 1; split < body.length; split++) {
            PerformativeReader reader = new PerformativeReader(4096);
            ByteBuffer first = ByteBuffer.wrap(body, 0, split);
            ByteBuffer second = ByteBuffer.wrap(body, split, body.length - split);
            Performative p = reader.receive(first);
            java.io.ByteArrayOutputStream rest = new java.io.ByteArrayOutputStream();
            if (p == null) {
                assertTrue("all octets of an incomplete performative are consumed",
                        !first.hasRemaining());
                p = reader.receive(second);
                assertTrue("split at " + split, p instanceof Open);
                while (second.hasRemaining()) {
                    rest.write(second.get());
                }
            } else {
                while (first.hasRemaining()) {
                    rest.write(first.get());
                }
                while (second.hasRemaining()) {
                    rest.write(second.get());
                }
            }
            assertArrayEquals("split at " + split, payload, rest.toByteArray());
        }
    }

    @Test
    public void testByteByByte() throws Exception {
        byte[] payload = {5, 6};
        byte[] body = concat(openBytes(), payload);
        PerformativeReader reader = new PerformativeReader(4096);
        Performative p = null;
        int payloadSeen = 0;
        for (int i = 0; i < body.length; i++) {
            ByteBuffer one = ByteBuffer.wrap(body, i, 1);
            if (p == null) {
                p = reader.receive(one);
            }
            if (p != null) {
                payloadSeen += one.remaining();
            }
        }
        assertTrue(p instanceof Open);
        assertEquals(2, payloadSeen);
    }

    @Test
    public void testReaderIsReusableAfterReset() throws Exception {
        PerformativeReader reader = new PerformativeReader(4096);
        byte[] body = openBytes();
        assertNull(reader.receive(ByteBuffer.wrap(body, 0, 10)));
        reader.reset();
        assertTrue(reader.receive(ByteBuffer.wrap(body)) instanceof Open);
        assertTrue(reader.receive(ByteBuffer.wrap(body)) instanceof Open);
    }

    @Test
    public void testOversizePerformativeRejectedBeforeItArrives() {
        // list32 claiming 1 MB
        byte[] head = Amqp1TypesTest.hex("005310" + "d0" + "00100000");
        PerformativeReader reader = new PerformativeReader(1024);
        try {
            reader.receive(ByteBuffer.wrap(head));
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
        }
    }

    @Test
    public void testMalformedPerformativeRejected() {
        PerformativeReader reader = new PerformativeReader(1024);
        try {
            reader.receive(ByteBuffer.wrap(Amqp1TypesTest.hex("a10161")));
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            // ok
        }
    }
}
