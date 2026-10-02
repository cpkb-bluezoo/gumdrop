/*
 * QpackEdgeBranchesTest.java
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

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge and error branches of the QPACK table, Required Insert Count
 * arithmetic, decoders and encoder: forged field sections, eviction and
 * reference-count rules, and encoder-stream instruction validation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QpackEdgeBranchesTest {

    private static ByteBuffer hex(String s) {
        return ByteBuffer.wrap(ByteArrays.toByteArray(s));
    }

    private static Decoder decoderWithEntry() {
        Decoder d = new Decoder(4096);
        d.setDynamicTableCapacity(4096);
        d.insertWithLiteralName("x-a".getBytes(StandardCharsets.US_ASCII), "1".getBytes(StandardCharsets.US_ASCII));
        return d;
    }

    private static void assertDecodeFails(Decoder d, String block, String fragment) {
        try {
            d.decode(1, hex(block));
            fail("expected ProtocolException for " + block);
        } catch (ProtocolException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains(fragment));
        }
    }

    // ---- DynamicTable ----

    @Test
    public void tableRejectsOversizeEntry() {
        DynamicTable t = new DynamicTable(40);
        assertEquals(-1, t.insert("name", "value-that-is-long"));
        t.insertMirrored("name", "value-that-is-long");
        assertEquals(0, t.getInsertCount());
    }

    @Test
    public void tableRefusesToEvictReferencedEntryAndEvictsWhenFree() {
        DynamicTable t = new DynamicTable(DynamicTable.entrySize("a", "1") * 2);
        assertEquals(0, t.insert("a", "1"));
        assertEquals(1, t.insert("b", "2"));
        t.addRef(0);
        assertEquals(-1, t.insert("c", "3"));
        t.releaseRef(0);
        t.releaseRef(0);
        assertEquals(2, t.insert("c", "3"));
        assertNull(t.get(0));
        assertEquals(1, t.getBaseIndex());
        assertNotNull(t.get(2));
        assertNull(t.get(3));
        t.addRef(99);
        t.releaseRef(99);
        t.addRef(0);
    }

    @Test
    public void tableMirroredInsertEvictsAndGrows() {
        DynamicTable t = new DynamicTable(4096);
        for (int i = 0; i < 20; i++) {
            t.insertMirrored("n" + i, "v");
        }
        assertEquals(20, t.getInsertCount());
        assertEquals("n19", t.get(19).getName());
        t.setCapacity(DynamicTable.entrySize("n19", "v"));
        assertNull(t.get(18));
        assertNotNull(t.get(19));
        assertEquals(DynamicTable.entrySize("n19", "v"), t.getCapacity());
        t.insertMirrored("m0", "v");
        assertNull(t.get(19));
    }

    @Test
    public void tableFindPrefersFullMatchAndHonoursVisibility() {
        DynamicTable t = new DynamicTable(4096);
        t.insert("a", "1");
        t.insert("a", "2");
        t.insert("b", "3");
        DynamicTable.FindResult r = t.find("a", "2", 3);
        assertTrue(r.fullMatch);
        assertEquals(1, r.absoluteIndex);
        r = t.find("a", "9", 3);
        assertEquals(false, r.fullMatch);
        assertEquals(0, r.absoluteIndex);
        assertNull(t.find("b", "3", 2));
        assertNull(t.find("zz", "3", 3));
    }

    // ---- RequiredInsertCount ----

    @Test
    public void requiredInsertCountBranches() {
        assertEquals(0, RequiredInsertCount.encode(0, 128));
        assertEquals(2, RequiredInsertCount.encode(1, 128));
        assertEquals(0, RequiredInsertCount.decode(0, 0, 128));
        assertEquals(RequiredInsertCount.INVALID, RequiredInsertCount.decode(1, 0, 16));
        assertEquals(RequiredInsertCount.INVALID, RequiredInsertCount.decode(9, 0, 128));
        assertEquals(RequiredInsertCount.INVALID, RequiredInsertCount.decode(1, 0, 128));
        assertEquals(RequiredInsertCount.INVALID, RequiredInsertCount.decode(6, 0, 128));
        assertEquals(10, RequiredInsertCount.decode(3, 10, 128));
        assertEquals(7, RequiredInsertCount.decode(8, 10, 128));
    }

    // ---- Decoder field section errors ----

    @Test
    public void decoderRejectsTruncatedAndInvalidPrefixes() {
        Decoder d = decoderWithEntry();
        assertDecodeFails(d, "", "underflow reading prefix");
        assertDecodeFails(d, "02", "underflow reading Base");
        assertDecodeFails(d, "06", "blocked");
        assertDecodeFails(d, "0282", "invalid Base");
        Decoder noCapacity = new Decoder(0);
        assertDecodeFails(noCapacity, "01", "invalid Required Insert Count");
    }

    @Test
    public void decoderRejectsOutOfRangeAndMissingIndices() {
        Decoder d = decoderWithEntry();
        assertDecodeFails(d, "0000ff30", "static table index out of range");
        assertDecodeFails(d, "000080", "relative index out of range");
        assertDecodeFails(d, "000280", "not live");
        assertDecodeFails(d, "00005f7f00", "static table index out of range");
        assertDecodeFails(d, "00024000", "not live");
        assertDecodeFails(d, "000011", "not live");
        assertDecodeFails(d, "00000100", "not live");
    }

    @Test
    public void decoderRejectsInvalidHeaderName() {
        Decoder d = decoderWithEntry();
        assertDecodeFails(d, "00002361206201" + "76", "QPACK");
    }

    @Test
    public void decoderResolvesDynamicReferences() throws ProtocolException {
        Decoder d = decoderWithEntry();
        List<Header> fields = d.decode(4, hex("0200" + "80" + "40017a"));
        assertEquals(2, fields.size());
        assertEquals(new Header("x-a", "1"), fields.get(0));
        assertEquals(new Header("x-a", "z"), fields.get(1));
        byte[] pending = d.takePendingInstructions();
        assertTrue(pending.length > 0);

        List<Header> post = d.decode(8, hex("02801000" + "01" + "7a"));
        assertEquals(new Header("x-a", "1"), post.get(0));
        assertEquals(new Header("x-a", "z"), post.get(1));

        List<Header> lit = d.decode(12, hex("000023616263" + "0176"));
        assertEquals(new Header("abc", "v"), lit.get(0));
    }

    // ---- Decoder encoder-stream handler errors ----

    @Test
    public void decoderEncoderStreamInstructionErrors() {
        Decoder d = new Decoder(4096);
        d.setDynamicTableCapacity(4096);
        d.insertWithNameReference(true, 500, new byte[0]);
        assertTrue(d.takeLastInstructionError().contains("static table index out of range"));
        d.insertWithNameReference(false, 0, new byte[0]);
        assertTrue(d.takeLastInstructionError().contains("not live"));
        d.duplicate(0);
        assertTrue(d.takeLastInstructionError().contains("Duplicate"));
        d.instructionError("boom");
        assertEquals("boom", d.takeLastInstructionError());

        d.insertWithNameReference(true, 1, "/x".getBytes(StandardCharsets.US_ASCII));
        d.insertWithNameReference(false, 0, "/y".getBytes(StandardCharsets.US_ASCII));
        d.duplicate(0);
        assertNull(d.takeLastInstructionError());
    }

    // ---- SimpleDecoder ----

    private static void assertSimpleFails(String block, String fragment) {
        try {
            new SimpleDecoder().decode(hex(block));
            fail("expected ProtocolException for " + block);
        } catch (ProtocolException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains(fragment));
        }
    }

    @Test
    public void simpleDecoderErrors() {
        assertSimpleFails("00", "underflow reading prefix");
        assertSimpleFails("0100", "Required Insert Count must be 0");
        assertSimpleFails("0000" + "80", "indexed field line");
        assertSimpleFails("0000ff30", "static table index out of range");
        assertSimpleFails("000010", "post-Base indexed");
        assertSimpleFails("000000", "post-Base name reference");
        assertSimpleFails("00004000", "name reference");
        assertSimpleFails("00005f7f00", "static table index out of range");
        assertSimpleFails("000050", "string literal underflow");
        assertSimpleFails("0000500a", "exceeds payload");
        assertSimpleFails("000050" + "81ffffffff", "Huffman");
    }

    @Test
    public void simpleDecoderDecodesStaticReferencesAndLiterals() throws ProtocolException {
        List<Header> h = new SimpleDecoder().decode(hex("0000d1" + "50017a" + "23616263" + "0176"));
        assertEquals(3, h.size());
        assertEquals(":method", h.get(0).getName());
        assertEquals("z", h.get(1).getValue());
        assertEquals(new Header("abc", "v"), h.get(2));
    }

    // ---- Encoder ----

    @Test
    public void encoderUsesDynamicNameReferenceOnceInsertAcknowledged() throws ProtocolException {
        Encoder encoder = new Encoder(4096);
        Decoder decoder = new Decoder(4096);
        List<Header> first = new ArrayList<Header>();
        first.add(new Header("x-custom", "widget"));
        ByteBuffer fs = ByteBuffer.allocate(256);
        ByteBuffer ins = ByteBuffer.allocate(256);
        encoder.encode(fs, ins, 0, first);
        ins.flip();
        decoder.feedEncoderStream(ins);
        encoder.feedDecoderStream(ByteBuffer.wrap(decoder.takePendingInstructions()));
        assertNull(encoder.takeLastInstructionError());

        List<Header> second = new ArrayList<Header>();
        second.add(new Header("x-custom", "gadget"));
        fs = ByteBuffer.allocate(256);
        ins = ByteBuffer.allocate(256);
        encoder.encode(fs, ins, 1, second);
        fs.flip();
        ins.flip();
        decoder.feedEncoderStream(ins);
        List<Header> out = decoder.decode(1, fs);
        assertEquals(second, out);
    }

    @Test
    public void encoderFallsBackToLiteralWhenTableTooSmall() throws ProtocolException {
        Encoder encoder = new Encoder(0);
        List<Header> in = new ArrayList<Header>();
        in.add(new Header("x-custom", "widget"));
        in.add(new Header(":path", "/zzz"));
        ByteBuffer fs = ByteBuffer.allocate(256);
        ByteBuffer ins = ByteBuffer.allocate(256);
        encoder.encode(fs, ins, 0, in);
        ins.flip();
        assertTrue(!ins.hasRemaining());
        fs.flip();
        assertEquals(in, new SimpleDecoder().decode(fs));
    }

    // ---- stream parsers ----

    @Test
    public void streamParsersHandleIntegerOverflowAndUnderflow() {
        try {
            PrefixedInteger.decode(ByteBuffer.wrap(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}), 0x3f, 6);
            fail("expected overflow");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("overflow"));
        }
        try {
            PrefixedInteger.decode(ByteBuffer.wrap(new byte[0]), 0x3f, 6);
            fail("expected underflow");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("underflow"));
        }
    }

    @Test
    public void qpackStringsErrors() {
        try {
            QpackStrings.read(ByteBuffer.wrap(new byte[0]), 7);
            fail();
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("underflow"));
        }
        try {
            QpackStrings.read(hex("05" + "61"), 7);
            fail();
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("exceeds"));
        }
        try {
            QpackStrings.read(hex("84ffffffff"), 7);
            fail();
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("Huffman"));
        }
    }

    @Test
    public void decoderStreamParserBuffersPartialInstructions() {
        final List<String> calls = new ArrayList<String>();
        DecoderStreamParser p = new DecoderStreamParser(new DecoderStreamHandler() {
            public void sectionAcknowledgment(long id) {
                calls.add("ack" + id);
            }
            public void streamCancellation(long id) {
                calls.add("cancel" + id);
            }
            public void insertCountIncrement(long n) {
                calls.add("inc" + n);
            }
        });
        p.receive(hex("ff"));
        assertTrue(calls.isEmpty());
        p.receive(hex("01"));
        assertEquals("ack128", calls.get(0));
        p.receive(hex("7f"));
        p.receive(hex("0a"));
        assertEquals("cancel73", calls.get(1));
    }
}
