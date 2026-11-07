/*
 * UuRandomisedTest.java
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Many generated uuencoded documents, with line endings, blank lines,
 * leading noise, junk after the zero line and an end line with no line
 * feed, run through the decoder, encoder and scanner with random chunk
 * sizes and heap and direct buffers. What goes in must come out, however
 * the bytes are split.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UuRandomisedTest {

	private static final String[] NOISE = {
		"", "hello", "b", "be", "beg", "begi", "begin", "begin ", "begin x", "begin 99",
		"begin 644", "begin 7777777 f", "end", "e", "x begin 644 f", "begin 64"
	};

	/** A generated document and what it should decode to. */
	private static final class Doc {
		String text;
		byte[] data;
		int mode;
		String name;
		boolean endHasLineFeed;
		String before;
		String after;
	}

	private static byte[] latin1(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static char chr(int v) {
		return v == 0 ? '`' : (char) (v + 32);
	}

	private static String encodeLine(byte[] d, int off, int n, Random r) {
		StringBuilder sb = new StringBuilder();
		if (n == 5 && r.nextBoolean()) {
			sb.append('e'); // an alias of '%' that looks like the start of "end"
		} else if (n == 0) {
			sb.append(r.nextBoolean() ? '`' : ' ');
		} else {
			sb.append(chr(n));
		}
		for (int i = 0; i < n; i += 3) {
			int b0 = d[off + i] & 0xff;
			int b1 = i + 1 < n ? d[off + i + 1] & 0xff : 0;
			int b2 = i + 2 < n ? d[off + i + 2] & 0xff : 0;
			sb.append(chr(b0 >> 2));
			sb.append(chr(((b0 << 4) | (b1 >> 4)) & 63));
			sb.append(chr(((b1 << 2) | (b2 >> 6)) & 63));
			sb.append(chr(b2 & 63));
		}
		return sb.toString();
	}

	private static Doc generate(Random r, boolean withSurroundingText) {
		Doc doc = new Doc();
		doc.data = new byte[r.nextInt(5) == 0 ? r.nextInt(8) : r.nextInt(200)];
		r.nextBytes(doc.data);
		doc.mode = r.nextInt(0777 + 1);
		String[] names = {"cat.txt", "a b", "x", "file.tar.gz"};
		doc.name = names[r.nextInt(names.length)];
		String eol = r.nextBoolean() ? "\n" : "\r\n";
		StringBuilder pre = new StringBuilder();
		if (withSurroundingText) {
			pre.append("Some text\n");
		}
		int noise = r.nextInt(4);
		for (int i = 0; i < noise; i++) {
			pre.append(NOISE[r.nextInt(NOISE.length)]).append(eol);
		}
		doc.before = pre.toString();
		StringBuilder sb = new StringBuilder();
		sb.append("begin ").append(Integer.toOctalString(doc.mode)).append(' ').append(doc.name).append(eol);
		int p = 0;
		while (p < doc.data.length) {
			int n = Math.min(doc.data.length - p, r.nextInt(3) == 0 ? 1 + r.nextInt(45) : 45);
			sb.append(encodeLine(doc.data, p, n, r)).append(eol);
			p += n;
			if (r.nextInt(6) == 0) {
				sb.append(eol); // a blank line
			}
		}
		if (r.nextInt(4) != 0) {
			sb.append(r.nextBoolean() ? "`" : " ").append(eol);
			int junk = r.nextInt(3);
			for (int i = 0; i < junk; i++) {
				sb.append(i == 0 ? "xyz" : "").append(eol);
			}
		}
		doc.endHasLineFeed = r.nextInt(4) != 0;
		sb.append("end");
		if (doc.endHasLineFeed) {
			sb.append(eol);
		}
		doc.text = sb.toString();
		doc.after = withSurroundingText && doc.endHasLineFeed ? "-- \nregards\n" : "";
		return doc;
	}

	private static ByteBuffer buffer(byte[] b, int off, int len, boolean direct) {
		if (direct) {
			ByteBuffer d = ByteBuffer.allocateDirect(len);
			d.put(b, off, len);
			d.flip();
			return d;
		}
		return ByteBuffer.wrap(b, off, len);
	}

	private static void drain(ByteBuffer dst, ByteArrayOutputStream out) {
		dst.flip();
		while (dst.hasRemaining()) {
			out.write(dst.get());
		}
		dst.clear();
	}

	@Test(timeout = 60000)
	public void testDecoderRecoversWhatWasEncoded() {
		Random r = new Random(20260501L);
		for (int n = 0; n < 3000; n++) {
			Doc doc = generate(r, false);
			byte[] all = latin1(doc.before + doc.text);
			UuDecoder d = new UuDecoder();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			// the decoder needs room for one group of three bytes
			int room = 3 + r.nextInt(6);
			ByteBuffer dst = r.nextBoolean() ? ByteBuffer.allocate(room) : ByteBuffer.allocateDirect(room);
			int p = 0;
			while (p < all.length && !d.isDone()) {
				int len = Math.min(1 + r.nextInt(9), all.length - p);
				ByteBuffer src = buffer(all, p, len, r.nextBoolean());
				int guard = 0;
				while (src.hasRemaining() && !d.isDone()) {
					int before = src.position();
					int wrote = dst.position();
					d.decode(src, dst);
					wrote = dst.position() - wrote;
					drain(dst, out);
					if (src.position() == before && wrote == 0 && ++guard > 3) {
						fail("no progress on " + doc.text);
					}
				}
				p += len - src.remaining();
			}
			ByteBuffer big = ByteBuffer.allocate(64);
			d.finish(big);
			drain(big, out);
			String why = "doc " + n + ": " + doc.before + doc.text;
			assertTrue(why, d.isDone());
			assertArrayEquals(why, doc.data, out.toByteArray());
			assertEquals(why, doc.mode, d.getMode());
			assertEquals(why, doc.name, d.getName());
		}
	}

	@Test(timeout = 60000)
	public void testEncoderOutputDoesNotDependOnBufferSizesOrKinds() {
		Random r = new Random(7L);
		for (int n = 0; n < 600; n++) {
			byte[] data = new byte[r.nextInt(300)];
			r.nextBytes(data);
			byte[] expected = UuEncoderTest.encodeAll(new UuEncoder("f.bin", 0644), data, 1000);
			UuEncoder e = new UuEncoder("f.bin", 0644);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			boolean directDst = r.nextBoolean();
			int cap = 1 + r.nextInt(80);
			int p = 0;
			boolean finished = false;
			boolean last = false;
			ByteBuffer src = null;
			int stalls = 0;
			for (int guard = 0; !finished; guard++) {
				assertTrue("case " + n + " does not finish: cap=" + cap + " direct=" + directDst + " len=" + data.length + " p=" + p + " last=" + last + " srcRemaining=" + (src == null ? -1 : src.remaining()) + " out=" + out.size() + " expected=" + expected.length, guard < 3000);
				if (src == null || !src.hasRemaining()) {
					int len = Math.min(1 + r.nextInt(100), data.length - p);
					last = p + len >= data.length;
					src = buffer(data, p, len, r.nextBoolean());
					p += len;
				}
				ByteBuffer dst = directDst ? ByteBuffer.allocateDirect(cap) : ByteBuffer.allocate(cap);
				int before = src.remaining();
				finished = e.encode(src, dst, last);
				int wrote = dst.position();
				drain(dst, out);
				if (!finished && before == src.remaining() && wrote == 0) {
					if (++stalls > 3) {
						cap = cap * 2 + 1; // too little room to make progress
						stalls = 0;
					}
				} else {
					stalls = 0;
				}
			}
			assertArrayEquals("case " + n, expected, out.toByteArray());
		}
	}

	/** The events and text of a scan, merged where adjacent. */
	private static List<String> scan(byte[] all, Random r, boolean chunked) {
		final List<String> events = new ArrayList<String>();
		UuScanner s = new UuScanner(new UuScanner.Handler() {
			public void text(ByteBuffer b) {
				add("T", b);
			}

			public void begin(int mode, String name) {
				events.add("B" + Integer.toOctalString(mode) + " " + name);
			}

			public void content(ByteBuffer b) {
				add("C", b);
			}

			public void end(boolean complete) {
				events.add(complete ? "E" : "A");
			}

			private void add(String kind, ByteBuffer b) {
				StringBuilder sb = new StringBuilder();
				while (b.hasRemaining()) {
					sb.append(String.format("%02x", b.get() & 0xff));
				}
				int last = events.size() - 1;
				if (last >= 0 && events.get(last).startsWith(kind + ":")) {
					events.set(last, events.get(last) + sb);
				} else {
					events.add(kind + ":" + sb);
				}
			}
		});
		int p = 0;
		while (p < all.length) {
			int len = chunked ? Math.min(1 + r.nextInt(11), all.length - p) : all.length;
			s.receive(buffer(all, p, len, chunked && r.nextBoolean()));
			p += len;
		}
		s.finish();
		return events;
	}

	@Test(timeout = 60000)
	public void testScannerFindsTheSameBlocksWhateverTheChunking() {
		Random r = new Random(99L);
		for (int n = 0; n < 1500; n++) {
			Doc a = generate(r, true);
			Doc b = generate(r, true);
			String text = a.before + a.text + a.after + (r.nextBoolean() ? b.before + b.text + b.after : "");
			byte[] all = latin1(text);
			List<String> whole = scan(all, r, false);
			List<String> chunked = scan(all, r, true);
			assertEquals("doc " + n + ": " + text, whole, chunked);
		}
	}

	@Test
	public void testScannerAbortsABlockThatNeverEnds() {
		UuScanner s = new UuScanner(new UuScanner.Handler() {
			public void text(ByteBuffer b) {
			}

			public void begin(int mode, String name) {
			}

			public void content(ByteBuffer b) {
			}

			public void end(boolean complete) {
				assertFalse(complete);
			}
		});
		s.receive(ByteBuffer.wrap(latin1("begin 644 f\n#0V%T\n")));
		s.finish();
	}

	@Test
	public void testDecoderStartedAfterTheBeginLine() {
		UuDecoder d = new UuDecoder(0755);
		ByteBuffer src = ByteBuffer.wrap(latin1("run.sh\n#0V%T\n`\nend\n"));
		ByteBuffer dst = ByteBuffer.allocate(16);
		d.decode(src, dst);
		dst.flip();
		assertEquals(3, dst.remaining());
		assertTrue(d.isDone());
		assertEquals(0755, d.getMode());
		assertEquals("run.sh", d.getName());
	}

	@Test
	public void testDataLineThatLooksLikeTheEndLine() {
		// "endAAAAA" is a data line (length character 'e' means 5 bytes), not the end
		UuDecoder d = new UuDecoder();
		ByteBuffer dst = ByteBuffer.allocate(16);
		d.decode(ByteBuffer.wrap(latin1("begin 644 f\nendAAAAA\n`\nend\n")), dst);
		dst.flip();
		assertEquals(5, dst.remaining());
		// the characters n, d, A, A decode to 0x9a, 0x84, 0x00 ... as uudecode(1) would
		assertEquals((byte) (((('n' - 32) & 63) << 2) | ((('d' - 32) & 63) >> 4)), dst.get());
		assertTrue(d.isDone());
	}

	@Test
	public void testInputThatEndsInTheMiddleOfALine() {
		UuDecoder d = new UuDecoder();
		ByteBuffer dst = ByteBuffer.allocate(16);
		d.decode(ByteBuffer.wrap(latin1("begin 644 f\n#0V%T")), dst);
		assertFalse(d.finish(dst));
		dst.flip();
		assertEquals(3, dst.remaining());
	}

	@Test
	public void testEncoderAfterItHasFinished() {
		UuEncoder e = new UuEncoder("f", 0644);
		ByteBuffer dst = ByteBuffer.allocate(200);
		assertTrue(e.encode(ByteBuffer.allocate(0), dst, true));
		int written = dst.position();
		assertTrue(e.encode(ByteBuffer.allocate(0), dst, true));
		assertEquals(written, dst.position());
	}
}
