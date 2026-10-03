/*
 * QpackTruncationSweepTest.java
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


package org.bluezoo.gumdrop.http.qpack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.junit.Test;

/**
 * Feeds every prefix (and every single-byte value as a lone instruction)
 * of each QPACK wire structure through its real entry point: truncated
 * peer input must be reported as a {@link ProtocolException} or left as
 * underflow, never escape as a runtime exception.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QpackTruncationSweepTest {

    private static byte[] bytes(ByteBuffer buf) {
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private static byte[] prefix(byte[] whole, int cut) {
        byte[] out = new byte[cut];
        System.arraycopy(whole, 0, out, 0, cut);
        return out;
    }

    private static List<Header> sampleFields() {
        List<Header> fields = new ArrayList<Header>();
        fields.add(new Header(":method", "GET"));
        fields.add(new Header(":path", "/index.html"));
        fields.add(new Header("x-custom-name", "some value that is not in any table"));
        fields.add(new Header("user-agent", "sweep/1.0"));
        return fields;
    }

    @Test
    public void testEveryPrefixOfAFieldSectionIsDecodedOrRejected() throws Exception {
        Encoder encoder = new Encoder(4096);
        ByteBuffer section = ByteBuffer.allocate(4096);
        ByteBuffer instructions = ByteBuffer.allocate(4096);
        encoder.encode(section, instructions, 0L, sampleFields());
        byte[] whole = bytes(section);
        for (int cut = 0; cut <= whole.length; cut++) {
            Decoder decoder = new Decoder(4096);
            try {
                decoder.decode(0L, ByteBuffer.wrap(prefix(whole, cut)));
            } catch (ProtocolException expected) {
                assertTrue("cut " + cut, cut < whole.length);
            }
        }
    }

    @Test
    public void testEveryPrefixOfEncoderStreamInstructionsIsConsumedOrHeld() throws Exception {
        Encoder encoder = new Encoder(4096);
        ByteBuffer capacity = ByteBuffer.allocate(64);
        encoder.setCapacity(capacity, 4096);
        ByteBuffer section = ByteBuffer.allocate(4096);
        ByteBuffer instructions = ByteBuffer.allocate(4096);
        encoder.encode(section, instructions, 0L, sampleFields());
        byte[] whole = concat(bytes(capacity), bytes(instructions));
        assertTrue(whole.length > 10);
        for (int cut = 0; cut <= whole.length; cut++) {
            Decoder decoder = new Decoder(4096);
            ByteBuffer in = ByteBuffer.wrap(prefix(whole, cut));
            decoder.feedEncoderStream(in);
            assertTrue("leftover is the unparsed tail only", in.remaining() <= cut);
            if (cut == whole.length) {
                assertEquals("a complete stream is fully consumed", 0, in.remaining());
            }
        }
    }

    @Test
    public void testEveryLoneByteOnTheEncoderAndDecoderStreamsIsHandled() {
        for (int value = 0; value < 256; value++) {
            Decoder decoder = new Decoder(4096);
            decoder.feedEncoderStream(ByteBuffer.wrap(new byte[] {(byte) value}));
            decoder.takeLastInstructionError();
            Encoder encoder = new Encoder(4096);
            encoder.feedDecoderStream(ByteBuffer.wrap(new byte[] {(byte) value}));
            encoder.takeLastInstructionError();
            Decoder blockDecoder = new Decoder(4096);
            try {
                blockDecoder.decode(0L, ByteBuffer.wrap(new byte[] {(byte) value}));
            } catch (ProtocolException expected) {
                assertEquals(ProtocolException.class, expected.getClass());
            }
        }
    }

    @Test
    public void testEveryPrefixOfDecoderStreamInstructionsIsConsumedOrHeld() {
        ByteBuffer out = ByteBuffer.allocate(64);
        DecoderStreamWriter.writeSectionAcknowledgment(out, 300L);
        DecoderStreamWriter.writeStreamCancellation(out, 70000L);
        DecoderStreamWriter.writeInsertCountIncrement(out, 5000L);
        byte[] whole = bytes(out);
        for (int cut = 0; cut <= whole.length; cut++) {
            Encoder encoder = new Encoder(4096);
            ByteBuffer in = ByteBuffer.wrap(prefix(whole, cut));
            encoder.feedDecoderStream(in);
            assertTrue(in.remaining() <= cut);
            if (cut == whole.length) {
                assertEquals(0, in.remaining());
            }
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
