/*
 * Base64DecoderTest.java
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
import java.util.Base64;

/**
 * Unit tests for Base64Decoder.
 * Tests include basic decoding, incomplete input handling, streaming scenarios,
 * and end-of-stream behaviour.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Base64DecoderTest {

	// ========== Helper methods ==========

	private String decode(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(Base64Decoder.estimateDecodedSize(encoded.length()));
		Base64Decoder.decode(src, dst, dst.capacity());
		byte[] decoded = new byte[dst.position()];
		dst.flip();
		dst.get(decoded);
		return new String(decoded, StandardCharsets.UTF_8);
	}

	private String decodeWithEndOfStream(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(Base64Decoder.estimateDecodedSize(encoded.length()));
		Base64Decoder.decode(src, dst, dst.capacity(), true);
		byte[] decoded = new byte[dst.position()];
		dst.flip();
		dst.get(decoded);
		return new String(decoded, StandardCharsets.UTF_8);
	}

	private byte[] decodeBytes(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(Base64Decoder.estimateDecodedSize(encoded.length()));
		Base64Decoder.decode(src, dst, dst.capacity());
		byte[] decoded = new byte[dst.position()];
		dst.flip();
		dst.get(decoded);
		return decoded;
	}

	private byte[] decodeBytesWithEndOfStream(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(Base64Decoder.estimateDecodedSize(encoded.length()));
		Base64Decoder.decode(src, dst, dst.capacity(), true);
		byte[] decoded = new byte[dst.position()];
		dst.flip();
		dst.get(decoded);
		return decoded;
	}

	// ========== Basic decoding tests ==========

	@Test
	public void testDecodeSimple() {
		assertEquals("Hello", decode("SGVsbG8="));
	}

	@Test
	public void testDecodeWorld() {
		assertEquals("World", decode("V29ybGQ="));
	}

	@Test
	public void testDecodeNoPadding() {
		// "Man" in Base64 (no padding needed)
		assertEquals("Man", decode("TWFu"));
	}

	@Test
	public void testDecodeOnePadding() {
		// "Ma" in Base64 (one = padding)
		assertEquals("Ma", decode("TWE="));
	}

	@Test
	public void testDecodeTwoPadding() {
		// "M" in Base64 (two == padding)
		assertEquals("M", decode("TQ=="));
	}

	@Test
	public void testDecodeWithWhitespace() {
		// Base64 with embedded CRLF (common in MIME)
		assertEquals("Hello", decode("SGVs\r\nbG8="));
	}

	@Test
	public void testDecodeWithSpaces() {
		assertEquals("Hello", decode("SGVs bG8="));
	}

	@Test
	public void testDecodeLongerString() {
		String original = "The quick brown fox jumps over the lazy dog";
		String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
		assertEquals(original, decode(encoded));
	}

	@Test
	public void testDecodeBinaryData() {
		byte[] original = new byte[256];
		for (int i = 0; i < 256; i++) {
			original[i] = (byte) i;
		}
		String encoded = Base64.getEncoder().encodeToString(original);
		assertArrayEquals(original, decodeBytes(encoded));
	}

	@Test
	public void testDecodeEmptyInput() {
		ByteBuffer src = ByteBuffer.wrap(new byte[0]);
		ByteBuffer dst = ByteBuffer.allocate(100);
		int consumedBytes = Base64Decoder.decode(src, dst, 100);
		assertEquals(0, dst.position());
		assertEquals(0, consumedBytes);
	}

	@Test
	public void testDecodeLimitedOutput() {
		String encoded = "SGVsbG8gV29ybGQ="; // "Hello World"
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(3);
		Base64Decoder.decode(src, dst, 3);
		assertTrue(dst.position() <= 3);
	}

	@Test
	public void testEstimateDecodedSize() {
		assertTrue(Base64Decoder.estimateDecodedSize(4) >= 3);
		assertTrue(Base64Decoder.estimateDecodedSize(100) >= 75);
		assertTrue(Base64Decoder.estimateDecodedSize(0) >= 0);
	}

	@Test
	public void testDecodeSpecialChars() {
		// Test + and / characters which are valid Base64
		byte[] original = { (byte) 0xFB, (byte) 0xEF, (byte) 0xBE };
		String encoded = Base64.getEncoder().encodeToString(original);
		assertArrayEquals(original, decodeBytes(encoded));
	}

	@Test
	public void testDecodeMultipleQuantums() {
		String original = "ABCDEFGHIJKL"; // 12 chars
		String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
		assertEquals(original, decode(encoded));
	}

	// ========== Incomplete input / underflow tests ==========

	@Test
	public void testIncompleteQuantum1Char() {
		// Only 1 char of a 4-char quantum - should not consume without endOfStream
		ByteBuffer src = ByteBuffer.wrap("S".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		int consumedBytes = Base64Decoder.decode(src, dst, 100, false);

		assertEquals("1 char incomplete: should decode 0 bytes", 0, dst.position());
		assertEquals("1 char incomplete: should consume 0 bytes", 0, consumedBytes);
		assertEquals("Source position should be at start", 0, src.position());
	}

	@Test
	public void testIncompleteQuantum2Chars() {
		// Only 2 chars of a 4-char quantum (12 bits = 1 byte + 4 extra bits)
		ByteBuffer src = ByteBuffer.wrap("SG".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		int consumedBytes = Base64Decoder.decode(src, dst, 100, false);

		assertEquals("2 char incomplete: should decode 0 bytes", 0, dst.position());
		assertEquals("2 char incomplete: should consume 0 bytes", 0, consumedBytes);
	}

	@Test
	public void testIncompleteQuantum3Chars() {
		// 3 chars of a 4-char quantum (18 bits = 2 bytes + 2 extra bits)
		ByteBuffer src = ByteBuffer.wrap("SGV".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		int consumedBytes = Base64Decoder.decode(src, dst, 100, false);

		assertEquals("3 char incomplete: should decode 0 bytes", 0, dst.position());
		assertEquals("3 char incomplete: should consume 0 bytes", 0, consumedBytes);
	}

	@Test
	public void testIncompleteQuantum2CharsWithEndOfStream() {
		// 2 chars at end of stream - should flush the available byte
		ByteBuffer src = ByteBuffer.wrap("SG".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		Base64Decoder.decode(src, dst, 100, true);

		assertEquals("2 chars EOS: should decode 1 byte", 1, dst.position());
		dst.flip();
		assertEquals("First byte should be 'H'", 'H', dst.get());
	}

	@Test
	public void testIncompleteQuantum3CharsWithEndOfStream() {
		// 3 chars at end of stream - should flush 2 available bytes
		ByteBuffer src = ByteBuffer.wrap("SGV".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		int consumedBytes = Base64Decoder.decode(src, dst, 100, true);

		assertEquals("3 chars EOS: should decode 2 bytes", 2, dst.position());
		assertEquals("3 chars EOS: should consume 3 bytes", 3, consumedBytes);
		dst.flip();
		assertEquals("First byte should be 'H'", 'H', dst.get());
		assertEquals("Second byte should be 'e'", 'e', dst.get());
	}

	// ========== Streaming / multi-chunk tests ==========

	@Test
	public void testStreamingDecodeInChunks() {
		// "Hello" = "SGVsbG8=" - decode in 4-char chunks
		String encoded = "SGVsbG8=";
		ByteBuffer dst = ByteBuffer.allocate(100);
		ByteBuffer src = ByteBuffer.allocate(100);
		StringBuilder result = new StringBuilder();

		// First chunk: "SGVs" (complete quantum)
		src.put("SGVs".getBytes(StandardCharsets.US_ASCII));
        src.flip();
		int consumedBytes = Base64Decoder.decode(src, dst, 100, false);
		assertEquals("First chunk: should decode 3 bytes", 3, dst.position());
		assertEquals("First chunk: should consume 4 bytes", 4, consumedBytes);

		// Second chunk: "bG8=" (complete quantum with padding)
		src.compact();
		src.put("bG8=".getBytes(StandardCharsets.US_ASCII));
        src.flip();
		Base64Decoder.decode(src, dst, 100, false);
		assertEquals("Second chunk: should decode 2 bytes", 5, dst.position());
	}

	@Test
	public void testStreamingWithIncompleteCarryOver() {
		// Simulate streaming where we get incomplete data, then more data arrives
		// "Hello" = "SGVsbG8="

		// Buffer that simulates network receive - 10 bytes capacity
		ByteBuffer src = ByteBuffer.allocate(10);
		ByteBuffer dst = ByteBuffer.allocate(100);

		// First receive: "SGVsb" (5 chars - 1 complete quantum + 1 incomplete)
		src.put("SGVsb".getBytes(StandardCharsets.US_ASCII));
		src.flip();

		int consumedBytes = Base64Decoder.decode(src, dst, 100, false);
		assertEquals("First pass: should decode 3 bytes (1 quantum)", 3, dst.position());
		assertEquals("First pass: should consume 4 bytes", 4, consumedBytes);
		assertEquals("Buffer should have 1 byte remaining", 1, src.remaining());

		// Compact and add more data: "G8="
		src.compact();
		src.put("G8=".getBytes(StandardCharsets.US_ASCII));
		src.flip();

		consumedBytes = Base64Decoder.decode(src, dst, 100, false);
		assertEquals("Second pass: should decode 2 bytes", 5, dst.position());
		assertEquals("Second pass: should consume 4 bytes", 4, consumedBytes);

		// Verify complete output
		dst.flip();
		byte[] output = new byte[5];
		dst.get(output);
		assertEquals("Hello", new String(output, StandardCharsets.UTF_8));
	}

	@Test
	public void testStreamingLargeDataInSmallChunks() {
		// Encode a larger string and decode it in small chunks using compact pattern
		String original = "The quick brown fox jumps over the lazy dog";
		String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
		byte[] encodedBytes = encoded.getBytes(StandardCharsets.US_ASCII);

		ByteBuffer src = ByteBuffer.allocate(16);
		ByteBuffer dst = ByteBuffer.allocate(200);
		int srcOffset = 0;

		while (srcOffset < encodedBytes.length) {
			// Simulate receiving data in chunks
			int toRead = Math.min(src.remaining(), encodedBytes.length - srcOffset);
			src.put(encodedBytes, srcOffset, toRead);
			srcOffset += toRead;
			src.flip();

			boolean isLast = (srcOffset >= encodedBytes.length);
			Base64Decoder.decode(src, dst, 200, isLast);

			// Compact moves unconsumed bytes to the start
			src.compact();
		}

		dst.flip();
		byte[] output = new byte[dst.remaining()];
		dst.get(output);
		assertEquals(original, new String(output, StandardCharsets.UTF_8));
	}

	// ========== Buffer position tracking tests ==========

	@Test
	public void testSourcePositionAfterCompleteQuantum() {
		ByteBuffer src = ByteBuffer.wrap("TWFu".getBytes(StandardCharsets.US_ASCII)); // "Man"
		ByteBuffer dst = ByteBuffer.allocate(100);

	    int consumedBytes = Base64Decoder.decode(src, dst, 100, false);

		assertEquals("Should decode 3 bytes", 3, dst.position());
		assertEquals("Should consume 4 bytes", 4, consumedBytes);
		assertEquals("Source position should be at end", 4, src.position());
		assertFalse("Source should have no remaining", src.hasRemaining());
	}

	@Test
	public void testSourcePositionAfterIncompleteQuantum() {
		// "TWFuT" - complete quantum "TWFu" + incomplete "T"
		ByteBuffer src = ByteBuffer.wrap("TWFuT".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		int consumedBytes = Base64Decoder.decode(src, dst, 100, false);

		assertEquals("Should decode 3 bytes", 3, dst.position());
		assertEquals("Should consume 4 bytes", 4, consumedBytes);
		assertEquals("Source position should be after complete quantum", 4, src.position());
		assertEquals("Source should have 1 remaining", 1, src.remaining());
	}

	@Test
	public void testDestinationBufferFull() {
		// "SGVsbG8gV29ybGQ=" = "Hello World" (11 bytes)
		ByteBuffer src = ByteBuffer.wrap("SGVsbG8gV29ybGQ=".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(3); // Only room for 1 quantum

		int consumedBytes = Base64Decoder.decode(src, dst, 3);

		assertEquals("Should decode exactly 3 bytes", 3, dst.position());
		assertEquals("Should consume 4 bytes (1 quantum)", 4, consumedBytes);
		assertTrue("Source should have remaining data", src.hasRemaining());
	}

	@Test
	public void testMaxParameterRespected() {
		ByteBuffer src = ByteBuffer.wrap("SGVsbG8gV29ybGQ=".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		Base64Decoder.decode(src, dst, 3); // max=3

		assertTrue("Should respect max parameter", dst.position() <= 3);
	}

	// ========== Edge cases ==========

	@Test
	public void testDecodeWithInvalidChars() {
		// Invalid characters should be skipped per RFC 2045
		assertEquals("Hello", decode("SG!V@s#b$G%8^="));
	}

	@Test
	public void testDecodeAllWhitespace() {
		ByteBuffer src = ByteBuffer.wrap("   \r\n\t  ".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		Base64Decoder.decode(src, dst, 100, true);

		assertEquals("Whitespace only: should decode 0 bytes", 0, dst.position());
	}

	@Test
	public void testDecodeWithLeadingWhitespace() {
		assertEquals("Hello", decode("   SGVsbG8="));
	}

	@Test
	public void testDecodeWithTrailingWhitespace() {
		assertEquals("Hello", decode("SGVsbG8=   "));
	}

	@Test
	public void testDecodeNoPaddingNoEndOfStream() {
		// "TWFu" (no padding) decodes to "Man" - should work without EOS
		ByteBuffer src = ByteBuffer.wrap("TWFu".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		Base64Decoder.decode(src, dst, 100, false);

		assertEquals("Complete quantum: should decode 3 bytes", 3, dst.position());
		dst.flip();
		byte[] output = new byte[3];
		dst.get(output);
		assertEquals("Man", new String(output, StandardCharsets.UTF_8));
	}

	@Test
	public void testMultipleCompleteQuantumsNoEndOfStream() {
		// "TWFuTWFu" = "ManMan" - two complete quantums
		ByteBuffer src = ByteBuffer.wrap("TWFuTWFu".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		int consumedBytes = Base64Decoder.decode(src, dst, 100, false);

		assertEquals("Two quantums: should decode 6 bytes", 6, dst.position());
		assertEquals("Should consume all 8 bytes", 8, consumedBytes);
	}

	@Test
	public void testRoundTripWithEndOfStream() {
		// Test various lengths to exercise different padding scenarios
		String[] testStrings = {"A", "AB", "ABC", "ABCD", "ABCDE", "ABCDEF"};

		for (String original : testStrings) {
			String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
			String decoded = decodeWithEndOfStream(encoded);
			assertEquals("Round trip failed for: " + original, original, decoded);
		}
	}

	@Test
	public void testStreamingWithWhitespaceBreaks() {
		// MIME often has line breaks every 76 chars
		String original = "The quick brown fox jumps over the lazy dog";
		String encoded = "VGhlIHF1aWNrIGJyb3duIGZveCBqdW1wcyBvdmVyIHRoZSBsYXp5IGRvZw==";

		ByteBuffer src = ByteBuffer.allocate(100);
		ByteBuffer dst = ByteBuffer.allocate(200);
        src.put(encoded.getBytes(StandardCharsets.US_ASCII));
        src.flip();

		Base64Decoder.decode(src, dst, 200, true);

		byte[] output = new byte[dst.position()];
		dst.flip();
		dst.get(output);
		assertEquals(original, new String(output, StandardCharsets.UTF_8));
	}

	@Test
	public void testSingleByteIncompleteAtEndOfStream() {
		// Single char "T" at end of stream - only 6 bits, not enough for a byte
		ByteBuffer src = ByteBuffer.wrap("T".getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(100);

		Base64Decoder.decode(src, dst, 100, true);

		// Only 6 bits - not enough for even 1 byte
		assertEquals("Single char at EOS: should decode 0 bytes (only 6 bits)", 0, dst.position());
	}

	@Test
	public void testCompactAndContinue() {
		// Realistic streaming scenario with ByteBuffer compact()
		String encoded = "SGVsbG8gV29ybGQh"; // "Hello World!"
		byte[] encodedBytes = encoded.getBytes(StandardCharsets.US_ASCII);

		ByteBuffer receiveBuffer = ByteBuffer.allocate(8);
		ByteBuffer output = ByteBuffer.allocate(100);

		int srcOffset = 0;
		while (srcOffset < encodedBytes.length) {
			// Simulate receiving data
			int toRead = Math.min(receiveBuffer.remaining(), encodedBytes.length - srcOffset);
			receiveBuffer.put(encodedBytes, srcOffset, toRead);
			srcOffset += toRead;
			receiveBuffer.flip();

			boolean isLast = (srcOffset >= encodedBytes.length);
			Base64Decoder.decode(receiveBuffer, output, 100, isLast);

			// Compact remaining unconsumed bytes
			receiveBuffer.compact();
		}

		output.flip();
		byte[] decoded = new byte[output.remaining()];
		output.get(decoded);
		assertEquals("Hello World!", new String(decoded, StandardCharsets.UTF_8));
	}

}
