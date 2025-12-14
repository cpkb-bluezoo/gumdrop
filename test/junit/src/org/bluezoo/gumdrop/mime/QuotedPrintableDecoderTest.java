/*
 * QuotedPrintableDecoderTest.java
 * Copyright (C) 2025 Chris Burdess
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for QuotedPrintableDecoder.
 * Tests include basic decoding, incomplete input handling, streaming scenarios,
 * and end-of-stream behavior.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuotedPrintableDecoderTest {

	// ========== Helper methods ==========

	private String decode(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.ISO_8859_1));
		ByteBuffer dst = ByteBuffer.allocate(QuotedPrintableDecoder.estimateDecodedSize(encoded.length()));
		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, dst.capacity());
		byte[] decoded = new byte[result.decodedBytes];
		dst.flip();
		dst.get(decoded);
		return new String(decoded, StandardCharsets.UTF_8);
	}

	private String decodeWithEndOfStream(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.ISO_8859_1));
		ByteBuffer dst = ByteBuffer.allocate(QuotedPrintableDecoder.estimateDecodedSize(encoded.length()));
		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, dst.capacity(), true);
		byte[] decoded = new byte[result.decodedBytes];
		dst.flip();
		dst.get(decoded);
		return new String(decoded, StandardCharsets.UTF_8);
	}

	private String decodeISO(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.ISO_8859_1));
		ByteBuffer dst = ByteBuffer.allocate(QuotedPrintableDecoder.estimateDecodedSize(encoded.length()));
		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, dst.capacity());
		byte[] decoded = new byte[result.decodedBytes];
		dst.flip();
		dst.get(decoded);
		return new String(decoded, StandardCharsets.ISO_8859_1);
	}

	private String decodeISOWithEndOfStream(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.ISO_8859_1));
		ByteBuffer dst = ByteBuffer.allocate(QuotedPrintableDecoder.estimateDecodedSize(encoded.length()));
		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, dst.capacity(), true);
		byte[] decoded = new byte[result.decodedBytes];
		dst.flip();
		dst.get(decoded);
		return new String(decoded, StandardCharsets.ISO_8859_1);
	}

	// ========== Basic decoding tests ==========

	@Test
	public void testDecodeSimple() {
		assertEquals("Hello World", decode("Hello World"));
	}

	@Test
	public void testDecodeHexEscape() {
		assertEquals("Hello World", decode("Hello=20World"));
	}

	@Test
	public void testDecodeMultipleHexEscapes() {
		assertEquals("Hello", decode("=48=65=6C=6C=6F"));
	}

	@Test
	public void testDecodeLowercaseHex() {
		assertEquals("Hello", decode("=48=65=6c=6c=6f"));
	}

	@Test
	public void testDecodeSoftLineBreakCRLF() {
		assertEquals("HelloWorld", decode("Hello=\r\nWorld"));
	}

	@Test
	public void testDecodeSoftLineBreakLF() {
		assertEquals("HelloWorld", decode("Hello=\nWorld"));
	}

	@Test
	public void testDecodeHighBytes() {
		// UTF-8 encoded 'é'
		assertEquals("é", decode("=C3=A9"));
	}

	@Test
	public void testDecodeEquals() {
		assertEquals("1+1=2", decode("1+1=3D2"));
	}

	@Test
	public void testDecodeEmptyInput() {
		ByteBuffer src = ByteBuffer.wrap(new byte[0]);
		ByteBuffer dst = ByteBuffer.allocate(100);
		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100);
		assertEquals(0, result.decodedBytes);
		assertEquals(0, result.consumedBytes);
	}

	@Test
	public void testDecodeMixedContent() {
		assertEquals("Café au lait", decode("Caf=C3=A9 au lait"));
	}

	@Test
	public void testDecodeLimitedOutput() {
		ByteBuffer src = ByteBuffer.wrap("Hello World".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(5);
		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 5);
		assertEquals(5, result.decodedBytes);
	}

	@Test
	public void testEstimateDecodedSize() {
		assertEquals(100, QuotedPrintableDecoder.estimateDecodedSize(100));
		assertEquals(0, QuotedPrintableDecoder.estimateDecodedSize(0));
	}

	@Test
	public void testDecodeInvalidHexTreatedAsLiteral() {
		assertEquals("=GG", decodeISOWithEndOfStream("=GG"));
	}

	@Test
	public void testDecodeTabAndNewline() {
		assertEquals("Hello\tWorld\nTest", decode("Hello\tWorld\nTest"));
	}

	@Test
	public void testDecodeJapaneseUTF8() {
		assertEquals("日本", decode("=E6=97=A5=E6=9C=AC"));
	}

	// ========== Incomplete input / underflow tests ==========

	@Test
	public void testIncompleteEqualsAtEnd() {
		// "=" at end without following chars - should wait for more data
		ByteBuffer src = ByteBuffer.wrap("Hello=".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, false);

		assertEquals("Should decode 5 bytes (Hello)", 5, result.decodedBytes);
		assertEquals("Should consume 5 bytes (stop at =)", 5, result.consumedBytes);
		assertEquals("Source position should be at =", 5, src.position());
	}

	@Test
	public void testIncompleteEqualsAtEndWithEndOfStream() {
		// "=" at end with endOfStream - should treat as literal
		ByteBuffer src = ByteBuffer.wrap("Hello=".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, true);

		assertEquals("Should decode 6 bytes (Hello=)", 6, result.decodedBytes);
		assertEquals("Should consume all 6 bytes", 6, result.consumedBytes);
		dst.flip();
		byte[] output = new byte[6];
		dst.get(output);
		assertEquals("Hello=", new String(output, StandardCharsets.US_ASCII));
	}

	@Test
	public void testIncompleteEscapeOneHexChar() {
		// "=4" - only one hex char, could be start of =4X
		ByteBuffer src = ByteBuffer.wrap("Hello=4".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, false);

		assertEquals("Should decode 5 bytes (Hello)", 5, result.decodedBytes);
		assertEquals("Should consume 5 bytes (stop before =4)", 5, result.consumedBytes);
	}

	@Test
	public void testIncompleteEscapeOneHexCharWithEndOfStream() {
		// "=4" at end of stream - treat = as literal, then 4 as literal
		ByteBuffer src = ByteBuffer.wrap("Hello=4".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, true);

		assertEquals("Should decode 7 bytes", 7, result.decodedBytes);
		dst.flip();
		byte[] output = new byte[7];
		dst.get(output);
		assertEquals("Hello=4", new String(output, StandardCharsets.US_ASCII));
	}

	@Test
	public void testIncompleteSoftBreakCR() {
		// "=\r" - could be start of =\r\n soft break
		ByteBuffer src = ByteBuffer.wrap("Hello=\r".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, false);

		assertEquals("Should decode 5 bytes (Hello)", 5, result.decodedBytes);
		assertEquals("Should consume 5 bytes (stop before =\\r)", 5, result.consumedBytes);
	}

	@Test
	public void testIncompleteSoftBreakCRWithEndOfStream() {
		// "=\r" at end of stream - treat = as literal
		ByteBuffer src = ByteBuffer.wrap("Hello=\r".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, true);

		assertEquals("Should decode 7 bytes", 7, result.decodedBytes);
	}

	// ========== Streaming / multi-chunk tests ==========

	@Test
	public void testStreamingDecodeSimple() {
		// Decode in chunks
		ByteBuffer dst = ByteBuffer.allocate(100);

		// First chunk: "Hello"
		ByteBuffer src1 = ByteBuffer.wrap("Hello".getBytes(StandardCharsets.US_ASCII));
		DecodeResult r1 = QuotedPrintableDecoder.decode(src1, dst, 100, false);
		assertEquals(5, r1.decodedBytes);

		// Second chunk: " World"
		ByteBuffer src2 = ByteBuffer.wrap(" World".getBytes(StandardCharsets.US_ASCII));
		DecodeResult r2 = QuotedPrintableDecoder.decode(src2, dst, 100, true);
		assertEquals(6, r2.decodedBytes);

		dst.flip();
		byte[] output = new byte[11];
		dst.get(output);
		assertEquals("Hello World", new String(output, StandardCharsets.US_ASCII));
	}

	@Test
	public void testStreamingWithSplitEscape() {
		// Escape sequence "=20" split across chunks
		ByteBuffer dst = ByteBuffer.allocate(100);

		// First chunk: "Hello=" (incomplete)
		ByteBuffer src1 = ByteBuffer.wrap("Hello=".getBytes(StandardCharsets.US_ASCII));
		DecodeResult r1 = QuotedPrintableDecoder.decode(src1, dst, 100, false);
		assertEquals("First chunk: decode Hello", 5, r1.decodedBytes);
		assertEquals("First chunk: consume Hello", 5, r1.consumedBytes);

		// Simulate compact: keep the "="
		ByteBuffer receiveBuffer = ByteBuffer.allocate(20);
		receiveBuffer.put((byte) '='); // The unconsumed =
		receiveBuffer.put("20World".getBytes(StandardCharsets.US_ASCII));
		receiveBuffer.flip();

		DecodeResult r2 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, true);
		assertEquals("Second chunk: decode space + World", 6, r2.decodedBytes);

		dst.flip();
		byte[] output = new byte[11];
		dst.get(output);
		assertEquals("Hello World", new String(output, StandardCharsets.US_ASCII));
	}

	@Test
	public void testStreamingWithSplitEscapeMiddle() {
		// "=2" split then "0" arrives
		ByteBuffer dst = ByteBuffer.allocate(100);
		ByteBuffer receiveBuffer = ByteBuffer.allocate(20);

		// First receive: "Hello=2"
		receiveBuffer.put("Hello=2".getBytes(StandardCharsets.US_ASCII));
		receiveBuffer.flip();
		DecodeResult r1 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, false);
		assertEquals("Should consume only Hello", 5, r1.consumedBytes);

		// Compact and add more: "=20World"
		receiveBuffer.compact();
		receiveBuffer.put("0World".getBytes(StandardCharsets.US_ASCII));
		receiveBuffer.flip();

		DecodeResult r2 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, true);
		assertEquals("Should decode space + World", 6, r2.decodedBytes);

		dst.flip();
		byte[] output = new byte[11];
		dst.get(output);
		assertEquals("Hello World", new String(output, StandardCharsets.US_ASCII));
	}

	@Test
	public void testStreamingWithSplitSoftBreak() {
		// Soft break "=\r\n" split across chunks
		ByteBuffer dst = ByteBuffer.allocate(100);
		ByteBuffer receiveBuffer = ByteBuffer.allocate(20);

		// First receive: "Hello=\r"
		receiveBuffer.put("Hello=\r".getBytes(StandardCharsets.US_ASCII));
		receiveBuffer.flip();
		DecodeResult r1 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, false);
		assertEquals("Should consume only Hello", 5, r1.consumedBytes);

		// Compact and add: "\nWorld"
		receiveBuffer.compact();
		receiveBuffer.put("\nWorld".getBytes(StandardCharsets.US_ASCII));
		receiveBuffer.flip();

		DecodeResult r2 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, true);
		assertEquals("Should decode World (soft break removed)", 5, r2.decodedBytes);

		dst.flip();
		byte[] output = new byte[10];
		dst.get(output);
		assertEquals("HelloWorld", new String(output, StandardCharsets.US_ASCII));
	}

	@Test
	public void testStreamingLargeDataInSmallChunks() {
		// Longer encoded string decoded in chunks
		String original = "The quick brown fox = jumps over the lazy dog";
		String encoded = "The quick brown fox =3D jumps over the lazy dog";

		ByteBuffer dst = ByteBuffer.allocate(200);
		int[] chunkSizes = {5, 7, 3, 11, 8, 100};
		int offset = 0;

		for (int i = 0; i < chunkSizes.length && offset < encoded.length(); i++) {
			int end = Math.min(offset + chunkSizes[i], encoded.length());
			String chunk = encoded.substring(offset, end);
			boolean isLast = (end >= encoded.length());

			ByteBuffer src = ByteBuffer.wrap(chunk.getBytes(StandardCharsets.US_ASCII));
			DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 200, isLast);

			offset += result.consumedBytes;
			if (result.consumedBytes < chunk.length()) {
				offset -= (chunk.length() - result.consumedBytes);
			}
		}

		dst.flip();
		byte[] output = new byte[dst.remaining()];
		dst.get(output);
		assertEquals(original, new String(output, StandardCharsets.US_ASCII));
	}

	// ========== Buffer position tracking tests ==========

	@Test
	public void testSourcePositionAfterComplete() {
		ByteBuffer src = ByteBuffer.wrap("Hello".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, false);

		assertEquals(5, result.decodedBytes);
		assertEquals(5, result.consumedBytes);
		assertEquals("Source position should be at end", 5, src.position());
		assertFalse("Source should have no remaining", src.hasRemaining());
	}

	@Test
	public void testSourcePositionAfterIncomplete() {
		ByteBuffer src = ByteBuffer.wrap("Hello=".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 100, false);

		assertEquals(5, result.decodedBytes);
		assertEquals(5, result.consumedBytes);
		assertEquals("Source position should be at =", 5, src.position());
		assertEquals("Source should have 1 remaining", 1, src.remaining());
	}

	@Test
	public void testDestinationBufferFull() {
		ByteBuffer src = ByteBuffer.wrap("Hello World!".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(5);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 5);

		assertEquals(5, result.decodedBytes);
		assertEquals(5, result.consumedBytes);
		assertTrue("Source should have remaining data", src.hasRemaining());
	}

	@Test
	public void testMaxParameterRespected() {
		ByteBuffer src = ByteBuffer.wrap("Hello World!".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 5);

		assertEquals("Should respect max parameter", 5, result.decodedBytes);
	}

	// ========== Edge cases ==========

	@Test
	public void testDecodeAllEscapes() {
		// String that's entirely escape sequences
		assertEquals("ABC", decodeWithEndOfStream("=41=42=43"));
	}

	@Test
	public void testDecodeConsecutiveSoftBreaks() {
		assertEquals("Hello", decode("Hel=\r\n=\r\nlo"));
	}

	@Test
	public void testDecodeMixedLineBreaks() {
		// Mix of soft breaks and real line breaks
		assertEquals("Hello\nWorld", decode("Hello=\r\n\nWorld"));
	}

	@Test
	public void testDecodeInvalidHexFirstChar() {
		// =GX where G is invalid
		assertEquals("=GX", decodeISOWithEndOfStream("=GX"));
	}

	@Test
	public void testDecodeInvalidHexSecondChar() {
		// =4G where G is invalid
		assertEquals("=4G", decodeISOWithEndOfStream("=4G"));
	}

	@Test
	public void testDecodeAllBytes() {
		// Test that all byte values can be decoded via hex escapes
		StringBuilder encoded = new StringBuilder();
		for (int i = 0; i < 256; i++) {
			encoded.append(String.format("=%02X", i));
		}

		ByteBuffer src = ByteBuffer.wrap(encoded.toString().getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(256);

		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, 256, true);

		assertEquals(256, result.decodedBytes);
		dst.flip();
		for (int i = 0; i < 256; i++) {
			assertEquals("Byte " + i + " mismatch", (byte) i, dst.get());
		}
	}

	@Test
	public void testCompactAndContinue() {
		// Realistic streaming scenario with ByteBuffer compact()
		String encoded = "Hello=20World=21";
		byte[] encodedBytes = encoded.getBytes(StandardCharsets.US_ASCII);

		ByteBuffer receiveBuffer = ByteBuffer.allocate(8);
		ByteBuffer output = ByteBuffer.allocate(100);

		int srcOffset = 0;
		while (srcOffset < encodedBytes.length) {
			int toRead = Math.min(receiveBuffer.remaining(), encodedBytes.length - srcOffset);
			receiveBuffer.put(encodedBytes, srcOffset, toRead);
			srcOffset += toRead;
			receiveBuffer.flip();

			boolean isLast = (srcOffset >= encodedBytes.length);
			DecodeResult result = QuotedPrintableDecoder.decode(receiveBuffer, output, 100, isLast);

			receiveBuffer.compact();
		}

		output.flip();
		byte[] decoded = new byte[output.remaining()];
		output.get(decoded);
		assertEquals("Hello World!", new String(decoded, StandardCharsets.US_ASCII));
	}

	@Test
	public void testSplitEscapeAtBufferBoundary() {
		// Test escape sequence exactly at buffer boundary
		ByteBuffer dst = ByteBuffer.allocate(100);

		// "Test=" then "20More"
		ByteBuffer src1 = ByteBuffer.wrap("Test=".getBytes(StandardCharsets.US_ASCII));
		QuotedPrintableDecoder.decode(src1, dst, 100, false);

		ByteBuffer receiveBuffer = ByteBuffer.allocate(10);
		receiveBuffer.put((byte) '=');
		receiveBuffer.put("20More".getBytes(StandardCharsets.US_ASCII));
		receiveBuffer.flip();

		QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, true);

		dst.flip();
		byte[] output = new byte[dst.remaining()];
		dst.get(output);
		assertEquals("Test More", new String(output, StandardCharsets.US_ASCII));
	}

	@Test
	public void testMultipleIncompleteThenComplete() {
		// Simulate network receiving byte by byte
		String encoded = "=41";
		ByteBuffer dst = ByteBuffer.allocate(100);
		ByteBuffer receiveBuffer = ByteBuffer.allocate(10);

		// Receive "="
		receiveBuffer.put((byte) '=');
		receiveBuffer.flip();
		DecodeResult r1 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, false);
		assertEquals("Just = should not be consumed", 0, r1.consumedBytes);
		receiveBuffer.compact();

		// Receive "4"
		receiveBuffer.put((byte) '4');
		receiveBuffer.flip();
		DecodeResult r2 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, false);
		assertEquals("=4 should not be consumed", 0, r2.consumedBytes);
		receiveBuffer.compact();

		// Receive "1"
		receiveBuffer.put((byte) '1');
		receiveBuffer.flip();
		DecodeResult r3 = QuotedPrintableDecoder.decode(receiveBuffer, dst, 100, true);
		assertEquals("=41 should decode to 1 byte", 1, r3.decodedBytes);

		dst.flip();
		assertEquals("Should decode to 'A'", 'A', dst.get());
	}
}
