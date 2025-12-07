/*
 * Base64DecoderTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.mime;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Unit tests for Base64Decoder.
 */
public class Base64DecoderTest {

	private String decode(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(Base64Decoder.estimateDecodedSize(encoded.length()));
		DecodeResult result = Base64Decoder.decode(src, dst, dst.capacity());
		byte[] decoded = new byte[result.decodedBytes];
		dst.flip();
		dst.get(decoded);
		return new String(decoded, StandardCharsets.UTF_8);
	}

	private byte[] decodeBytes(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(Base64Decoder.estimateDecodedSize(encoded.length()));
		DecodeResult result = Base64Decoder.decode(src, dst, dst.capacity());
		byte[] decoded = new byte[result.decodedBytes];
		dst.flip();
		dst.get(decoded);
		return decoded;
	}

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
		DecodeResult result = Base64Decoder.decode(src, dst, 100);
		assertEquals(0, result.decodedBytes);
		assertEquals(0, result.consumedBytes);
	}

	@Test
	public void testDecodeLimitedOutput() {
		String encoded = "SGVsbG8gV29ybGQ="; // "Hello World"
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
		ByteBuffer dst = ByteBuffer.allocate(3);
		DecodeResult result = Base64Decoder.decode(src, dst, 3);
		assertTrue(result.decodedBytes <= 3);
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
		byte[] original = {(byte) 0xFB, (byte) 0xEF, (byte) 0xBE};
		String encoded = Base64.getEncoder().encodeToString(original);
		assertArrayEquals(original, decodeBytes(encoded));
	}

	@Test
	public void testDecodeMultipleQuantums() {
		String original = "ABCDEFGHIJKL"; // 12 chars
		String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
		assertEquals(original, decode(encoded));
	}
}
