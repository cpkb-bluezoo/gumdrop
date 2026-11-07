/*
 * MimeParserUuencodeTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Tests that uuencoded bodies (Content-Transfer-Encoding: x-uuencode) are
 * decoded as they arrive, whatever the chunking.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // shares the older-events Sink with the base64 tests
public class MimeParserUuencodeTest {

	private static byte[] parse(byte[] data, int chunk, String encoding) throws MimeParseException {
		MimeParserTransferEncodingStreamingTest.Sink sink = new MimeParserTransferEncodingStreamingTest.Sink();
		MimeParser parser = new MimeParser();
		parser.setHandler(sink);
		ByteBuffer buf = ByteBuffer.allocate(Math.max(chunk, 2048));
		int off = 0;
		while (off < data.length) {
			int n = Math.min(chunk, data.length - off);
			buf.put(data, off, n);
			off += n;
			buf.flip();
			parser.receive(buf);
			buf.compact();
		}
		buf.flip();
		parser.receive(buf);
		parser.close();
		return sink.body.toByteArray();
	}

	private static byte[] message(String encoding, byte[] raw) {
		UuEncoder e = new UuEncoder("data.bin", 0644);
		byte[] enc = UuEncoderTest.encodeAll(e, raw, 1000);
		String head = "Content-Type: application/octet-stream\r\nContent-Transfer-Encoding: "
				+ encoding + "\r\n\r\n";
		byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
		byte[] all = new byte[h.length + enc.length];
		System.arraycopy(h, 0, all, 0, h.length);
		System.arraycopy(enc, 0, all, h.length, enc.length);
		return all;
	}

	@Test
	public void testDecodesInAnyChunking() throws MimeParseException {
		byte[] raw = new byte[5000];
		new Random(7).nextBytes(raw);
		for (String encoding : new String[] { "x-uuencode", "uuencode", "X-UUE" }) {
			byte[] msg = message(encoding, raw);
			for (int chunk : new int[] { 1, 2, 7, 61, 62, 63, 500, 100000 }) {
				assertArrayEquals(encoding + " chunk " + chunk, raw, parse(msg, chunk, encoding));
			}
		}
	}

	@Test
	public void testSmall() throws MimeParseException {
		byte[] msg = ("Content-Transfer-Encoding: x-uuencode\r\n\r\n"
				+ "begin 644 cat.txt\r\n#0V%T\r\n`\r\nend\r\n").getBytes(StandardCharsets.ISO_8859_1);
		assertEquals("Cat", new String(parse(msg, 1, "x-uuencode"), StandardCharsets.ISO_8859_1));
	}

}
