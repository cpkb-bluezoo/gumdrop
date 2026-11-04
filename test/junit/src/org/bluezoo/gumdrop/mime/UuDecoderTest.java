/*
 * UuDecoderTest.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Unit tests for UuDecoder: whole-input, every two-way split, and
 * byte-at-a-time feeds, with small and direct output buffers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UuDecoderTest {

	/** "Cat" encoded by uuencode(1). */
	private static final String CAT = "begin 644 cat.txt\n#0V%T\n`\nend\n";

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	/** Feeds the pieces in order through one decoder, with a tiny output buffer. */
	private static byte[] run(UuDecoder d, byte[]... pieces) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer dst = ByteBuffer.allocate(5);
		for (byte[] piece : pieces) {
			ByteBuffer src = ByteBuffer.wrap(piece);
			while (src.hasRemaining() && !d.isDone()) {
				int before = src.position();
				d.decode(src, dst);
				dst.flip();
				while (dst.hasRemaining()) {
					out.write(dst.get());
				}
				dst.clear();
				assertTrue("no progress", src.position() > before || !src.hasRemaining() || d.isDone());
			}
		}
		return out.toByteArray();
	}

	private static byte[][] split(byte[] all, int at) {
		byte[] a = new byte[at];
		byte[] b = new byte[all.length - at];
		System.arraycopy(all, 0, a, 0, at);
		System.arraycopy(all, at, b, 0, b.length);
		return new byte[][] { a, b };
	}

	@Test
	public void testBasic() {
		UuDecoder d = new UuDecoder();
		assertEquals("Cat", new String(run(d, ascii(CAT)), StandardCharsets.ISO_8859_1));
		assertTrue(d.isDone());
		assertTrue(d.hasHeader());
		assertEquals(0644, d.getMode());
		assertEquals("cat.txt", d.getName());
	}

	@Test
	public void testEveryTwoWaySplit() {
		byte[] all = ascii(CAT);
		for (int i = 0; i <= all.length; i++) {
			UuDecoder d = new UuDecoder();
			assertEquals("split at " + i, "Cat",
					new String(run(d, split(all, i)), StandardCharsets.ISO_8859_1));
			assertTrue(d.isDone());
		}
	}

	@Test
	public void testByteAtATime() {
		byte[] all = ascii(CAT);
		byte[][] pieces = new byte[all.length][];
		for (int i = 0; i < all.length; i++) {
			pieces[i] = new byte[] { all[i] };
		}
		UuDecoder d = new UuDecoder();
		assertEquals("Cat", new String(run(d, pieces), StandardCharsets.ISO_8859_1));
		assertTrue(d.isDone());
		assertEquals("cat.txt", d.getName());
	}

	@Test
	public void testRoundTripRandomLengthsAndChunks() {
		Random rnd = new Random(42);
		for (int len : new int[] { 0, 1, 2, 3, 44, 45, 46, 90, 1000, 4097 }) {
			byte[] data = new byte[len];
			rnd.nextBytes(data);
			UuEncoder e = new UuEncoder("f.bin", 0600);
			byte[] enc = UuEncoderTest.encodeAll(e, data, 7);
			for (int chunk : new int[] { 1, 3, 61, 62, 63, 4096 }) {
				UuDecoder d = new UuDecoder();
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				ByteBuffer dst = ByteBuffer.allocate(64);
				for (int p = 0; p < enc.length; p += chunk) {
					ByteBuffer src = ByteBuffer.wrap(enc, p, Math.min(chunk, enc.length - p));
					while (src.hasRemaining() && !d.isDone()) {
						d.decode(src, dst);
						dst.flip();
						while (dst.hasRemaining()) {
							out.write(dst.get());
						}
						dst.clear();
					}
				}
				assertArrayEquals("len " + len + " chunk " + chunk, data, out.toByteArray());
				assertTrue(d.isDone());
				assertEquals(0600, d.getMode());
			}
		}
	}

	@Test
	public void testDirectBuffers() {
		UuDecoder d = new UuDecoder();
		ByteBuffer src = ByteBuffer.allocateDirect(64);
		src.put(ascii(CAT)).flip();
		ByteBuffer dst = ByteBuffer.allocateDirect(16);
		d.decode(src, dst);
		dst.flip();
		byte[] got = new byte[dst.remaining()];
		dst.get(got);
		assertEquals("Cat", new String(got, StandardCharsets.ISO_8859_1));
		assertTrue(d.isDone());
	}

	@Test
	public void testJunkBeforeBeginIsSkipped() {
		UuDecoder d = new UuDecoder();
		byte[] got = run(d, ascii("Here is the file:\n\nbegin 644 cat.txt\n#0V%T\n`\nend\ntrailer\n"));
		assertEquals("Cat", new String(got, StandardCharsets.ISO_8859_1));
		assertTrue(d.isDone());
	}

	@Test
	public void testShortLinesWithStrippedTrailingSpaces() {
		// a one byte line is "!" plus four characters; here the trailing
		// zero characters were stripped, leaving "!0" (0x40 then zeros)
		UuDecoder d = new UuDecoder();
		byte[] got = run(d, ascii("begin 644 a\n!0\n`\nend\n"));
		assertEquals(1, got.length);
		assertEquals(0x40, got[0]);
	}

	@Test
	public void testCrLfLineEndings() {
		UuDecoder d = new UuDecoder();
		byte[] got = run(d, ascii("begin 644 cat.txt\r\n#0V%T\r\n`\r\nend\r\n"));
		assertEquals("Cat", new String(got, StandardCharsets.ISO_8859_1));
		assertTrue(d.isDone());
		assertEquals("cat.txt", d.getName());
	}

	@Test
	public void testDataLineStartingWithEIsNotEnd() {
		// a five byte line is canonically "%" and 8 characters, but some
		// encoders write 'e' (the length plus 64), which starts "end"
		byte[] data = new byte[] { 1, 2, 3, 4, 5 };
		UuEncoder e = new UuEncoder("e", 0644);
		byte[] enc = UuEncoderTest.encodeAll(e, data, 1000);
		String text = new String(enc, StandardCharsets.ISO_8859_1).replace("e\n%", "e\ne");
		assertTrue(text.contains("\ne"));
		enc = ascii(text);
		UuDecoder d = new UuDecoder();
		assertArrayEquals(data, run(d, enc));
		assertTrue(d.isDone());
	}

	@Test
	public void testFinishWithoutEnd() {
		UuDecoder d = new UuDecoder();
		run(d, ascii("begin 644 cat.txt\n#0V%T\n"));
		assertFalse(d.isDone());
		assertFalse(d.finish(ByteBuffer.allocate(8)));
	}

	@Test
	public void testStopsAfterEnd() {
		UuDecoder d = new UuDecoder();
		ByteBuffer src = ByteBuffer.wrap(ascii(CAT + "after\n"));
		d.decode(src, ByteBuffer.allocate(16));
		assertTrue(d.isDone());
		assertEquals("after\n", new String(src.array(), src.position(),
				src.remaining(), StandardCharsets.ISO_8859_1));
	}

}
