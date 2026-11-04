/*
 * UuScannerTest.java
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
 * Unit tests for UuScanner, which finds uuencoded files inline in text.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UuScannerTest {

	private static final class Rec implements UuScanner.Handler {
		final ByteArrayOutputStream text = new ByteArrayOutputStream();
		final ByteArrayOutputStream content = new ByteArrayOutputStream();
		final StringBuilder events = new StringBuilder();

		public void text(ByteBuffer b) {
			while (b.hasRemaining()) {
				text.write(b.get());
			}
		}

		public void begin(int mode, String name) {
			events.append("[begin ").append(Integer.toOctalString(mode)).append(' ').append(name).append(']');
		}

		public void content(ByteBuffer b) {
			while (b.hasRemaining()) {
				content.write(b.get());
			}
		}

		public void end(boolean complete) {
			events.append(complete ? "[end]" : "[abort]");
		}

		String t() {
			return new String(text.toByteArray(), StandardCharsets.ISO_8859_1);
		}

		String c() {
			return new String(content.toByteArray(), StandardCharsets.ISO_8859_1);
		}
	}

	private static final String ARTICLE = "Hello,\nhere is the file.\n\n"
			+ "begin 644 cat.txt\n#0V%T\n`\nend\n"
			+ "-- \nregards\n";

	private static Rec scan(String text, int chunk) {
		Rec r = new Rec();
		UuScanner s = new UuScanner(r);
		byte[] b = text.getBytes(StandardCharsets.ISO_8859_1);
		for (int p = 0; p < b.length; p += chunk) {
			s.receive(ByteBuffer.wrap(b, p, Math.min(chunk, b.length - p)));
		}
		s.finish();
		return r;
	}

	@Test
	public void testInlineBlock() {
		Rec r = scan(ARTICLE, 4096);
		assertEquals("Hello,\nhere is the file.\n\n-- \nregards\n", r.t());
		assertEquals("Cat", r.c());
		assertEquals("[begin 644 cat.txt][end]", r.events.toString());
	}

	@Test
	public void testEveryChunkSize() {
		for (int chunk = 1; chunk <= ARTICLE.length(); chunk++) {
			Rec r = scan(ARTICLE, chunk);
			assertEquals("chunk " + chunk, "Hello,\nhere is the file.\n\n-- \nregards\n", r.t());
			assertEquals("chunk " + chunk, "Cat", r.c());
			assertEquals("chunk " + chunk, "[begin 644 cat.txt][end]", r.events.toString());
		}
	}

	@Test
	public void testLookalikeLinesArePassedThrough() {
		String t = "begin the meeting\nbegin 999 x\nbegin 644\nbeginning 644 x\n begin 644 x\n";
		for (int chunk : new int[] { 1, 3, 4096 }) {
			Rec r = scan(t, chunk);
			assertEquals("chunk " + chunk, t, r.t());
			assertEquals("", r.events.toString());
		}
	}

	@Test
	public void testPartialBeginAtEndOfInputIsText() {
		Rec r = scan("hello\nbegin 6", 2);
		assertEquals("hello\nbegin 6", r.t());
	}

	@Test
	public void testUnterminatedBlockAborts() {
		Rec r = scan("x\nbegin 644 f\n#0V%T\n", 3);
		assertEquals("Cat", r.c());
		assertEquals("[begin 644 f][abort]", r.events.toString());
	}

	@Test
	public void testTwoBlocks() {
		String t = "a\nbegin 644 one\n#0V%T\n`\nend\nb\nbegin 600 two\n#0V%T\n`\nend\nc\n";
		Rec r = scan(t, 5);
		assertEquals("a\nb\nc\n", r.t());
		assertEquals("CatCat", r.c());
		assertEquals("[begin 644 one][end][begin 600 two][end]", r.events.toString());
	}

}
