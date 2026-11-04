/*
 * UuEncoderTest.java
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

/**
 * Unit tests for UuEncoder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UuEncoderTest {

	/** Encodes data fed in chunks of the given size through a small output buffer. */
	static byte[] encodeAll(UuEncoder e, byte[] data, int chunk) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer dst = ByteBuffer.allocate(70);
		int p = 0;
		boolean finished = false;
		while (!finished) {
			int n = Math.min(chunk, data.length - p);
			ByteBuffer src = ByteBuffer.wrap(data, p, n);
			boolean last = p + n >= data.length;
			while (true) {
				finished = e.encode(src, dst, last);
				dst.flip();
				boolean wrote = dst.hasRemaining();
				while (dst.hasRemaining()) {
					out.write(dst.get());
				}
				dst.clear();
				if (finished || (!src.hasRemaining() && !(last && wrote))) {
					break;
				}
			}
			p += n - src.remaining();
			if (src.hasRemaining()) {
				fail("encoder left input behind");
			}
		}
		return out.toByteArray();
	}

	@Test
	public void testKnownVector() {
		UuEncoder e = new UuEncoder("cat.txt", 0644);
		byte[] enc = encodeAll(e, "Cat".getBytes(StandardCharsets.ISO_8859_1), 100);
		assertEquals("begin 644 cat.txt\n#0V%T\n`\nend\n",
				new String(enc, StandardCharsets.ISO_8859_1));
	}

	@Test
	public void testEmpty() {
		UuEncoder e = new UuEncoder("empty", 0644);
		byte[] enc = encodeAll(e, new byte[0], 10);
		assertEquals("begin 644 empty\n`\nend\n", new String(enc, StandardCharsets.ISO_8859_1));
	}

	@Test
	public void testFullLinesAre45BytesAnd61Chars() {
		byte[] data = new byte[90];
		UuEncoder e = new UuEncoder("x", 0644);
		String s = new String(encodeAll(e, data, 13), StandardCharsets.ISO_8859_1);
		String[] lines = s.split("\n");
		assertEquals("begin 644 x", lines[0]);
		assertEquals(61, lines[1].length());
		assertEquals('M', lines[1].charAt(0));
		assertEquals(61, lines[2].length());
		assertEquals("`", lines[3]);
		assertEquals("end", lines[4]);
	}

	@Test
	public void testChunkingDoesNotChangeOutput() {
		byte[] data = new byte[301];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) (i * 31);
		}
		byte[] ref = encodeAll(new UuEncoder("f", 0644), data, 1000);
		for (int chunk : new int[] { 1, 2, 44, 45, 46, 100 }) {
			assertArrayEquals("chunk " + chunk, ref, encodeAll(new UuEncoder("f", 0644), data, chunk));
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void testNameWithNewlineRejected() {
		new UuEncoder("a\nb", 0644);
	}

}
