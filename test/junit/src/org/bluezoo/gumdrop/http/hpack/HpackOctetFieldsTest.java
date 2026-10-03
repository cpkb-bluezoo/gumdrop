/*
 * HpackOctetFieldsTest.java
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


package org.bluezoo.gumdrop.http.hpack;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.HeaderFieldHandler;
import org.junit.Test;

/**
 * The HPACK decoder hands each field to its handler as the octets that were
 * on the wire. HPACK itself says nothing about what those octets mean
 * (RFC 7541 section 5.2), and HTTP treats octets above 0x7F as opaque
 * (RFC 9110 section 5.5), so deciding whether a field is acceptable belongs
 * to the receiver, after the whole block has been consumed: a field the
 * receiver rejects must not leave the decoder's dynamic table out of step
 * with the peer's (RFC 9113 section 4.3).
 *
 * <p>The blocks are built by hand so they can carry octets that the
 * validating {@link Encoder} would refuse to produce.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HpackOctetFieldsTest {

    /** Records the octets of every field it is given. */
    private static final class Recorder implements HeaderFieldHandler {
        final List<byte[]> names = new ArrayList<byte[]>();
        final List<byte[]> values = new ArrayList<byte[]>();

        @Override
        public void field(ByteBuffer name, ByteBuffer value) {
            names.add(copy(name));
            values.add(copy(value));
        }

        private static byte[] copy(ByteBuffer b) {
            byte[] out = new byte[b.remaining()];
            b.duplicate().get(out);
            return out;
        }
    }

    /** A literal field with a new name and no Huffman coding (RFC 7541 section 6.2). */
    private static byte[] literal(int opcode, byte[] name, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(opcode);                 // 0x00 without indexing, 0x40 with incremental indexing
        out.write(name.length);            // H bit clear, length fits in 7 bits
        out.write(name, 0, name.length);
        out.write(value.length);
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void octetsAboveAsciiAreDeliveredUnchanged() throws Exception {
        byte[] value = new byte[] {'c', 'a', 'f', (byte) 0xE9, (byte) 0xC3, (byte) 0xA9};
        Recorder r = new Recorder();
        new Decoder(4096).decode(ByteBuffer.wrap(literal(0x00, ascii("x-raw"), value)), r);
        assertEquals(1, r.names.size());
        assertArrayEquals(ascii("x-raw"), r.names.get(0));
        assertArrayEquals(value, r.values.get(0));
    }

    @Test
    public void decoderDoesNotJudgeFieldSyntax() throws Exception {
        // CR, LF and NUL are not valid in a field value; whether to refuse the
        // message is the receiver's decision, not the decoder's.
        byte[] value = new byte[] {'a', '\r', '\n', 0, 'b'};
        Recorder r = new Recorder();
        new Decoder(4096).decode(ByteBuffer.wrap(literal(0x00, ascii("x-odd"), value)), r);
        assertArrayEquals(value, r.values.get(0));
    }

    @Test
    public void dynamicTableStaysInStepAfterAFieldTheReceiverWouldReject() throws Exception {
        Decoder d = new Decoder(4096);
        byte[] bad = new byte[] {'a', '\r', '\n', 'b'};
        Recorder first = new Recorder();
        d.decode(ByteBuffer.wrap(literal(0x40, ascii("x-bad"), bad)), first);

        // A later block refers to the entry just inserted: dynamic index 62.
        Recorder second = new Recorder();
        d.decode(ByteBuffer.wrap(new byte[] {(byte) (0x80 | 62)}), second);
        assertEquals(1, second.names.size());
        assertArrayEquals(ascii("x-bad"), second.names.get(0));
        assertArrayEquals(bad, second.values.get(0));
    }

    @Test
    public void indexedStaticEntryDeliversItsNameAndValue() throws Exception {
        Recorder r = new Recorder();
        new Decoder(4096).decode(ByteBuffer.wrap(new byte[] {(byte) (0x80 | 2)}), r);  // :method GET
        assertArrayEquals(ascii(":method"), r.names.get(0));
        assertArrayEquals(ascii("GET"), r.values.get(0));
    }

    @Test
    public void indexedNameWithLiteralValueKeepsTheValueOctets() throws Exception {
        // Literal without indexing, name from static index 32 (cookie).
        byte[] value = new byte[] {'a', '=', (byte) 0xFF};
        byte[] block = new byte[2 + value.length + 1];
        block[0] = 0x0F;                    // 4-bit prefix all ones: index continues
        block[1] = (byte) (32 - 15);
        block[2] = (byte) value.length;
        System.arraycopy(value, 0, block, 3, value.length);
        Recorder r = new Recorder();
        new Decoder(4096).decode(ByteBuffer.wrap(block), r);
        assertArrayEquals(ascii("cookie"), r.names.get(0));
        assertArrayEquals(value, r.values.get(0));
    }

    @Test
    public void fieldOctetsAreDeliveredReadOnly() throws Exception {
        final boolean[] readOnly = new boolean[2];
        new Decoder(4096).decode(ByteBuffer.wrap(literal(0x40, ascii("x-a"), ascii("v"))),
                new HeaderFieldHandler() {
                    @Override
                    public void field(ByteBuffer name, ByteBuffer value) {
                        readOnly[0] = name.isReadOnly();
                        readOnly[1] = value.isReadOnly();
                    }
                });
        // A handler must not be able to corrupt the dynamic table through its slices.
        assertEquals(true, readOnly[0]);
        assertEquals(true, readOnly[1]);
    }
}
