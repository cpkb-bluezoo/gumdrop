/*
 * RFC2047EncoderTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.mime.rfc2047;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

/**
 * Unit tests for RFC2047Encoder.
 */
public class RFC2047EncoderTest {

	// ========== containsNonAscii tests ==========

	@Test
	public void testContainsNonAsciiTrue() {
		byte[] data = "Café".getBytes(StandardCharsets.UTF_8);
		assertTrue(RFC2047Encoder.containsNonAscii(data));
	}

	@Test
	public void testContainsNonAsciiFalse() {
		byte[] data = "Hello World".getBytes(StandardCharsets.US_ASCII);
		assertFalse(RFC2047Encoder.containsNonAscii(data));
	}

	@Test
	public void testContainsNonAsciiEmpty() {
		assertFalse(RFC2047Encoder.containsNonAscii(new byte[0]));
	}

	@Test
	public void testContainsNonAsciiRange() {
		byte[] data = "Hello Café World".getBytes(StandardCharsets.UTF_8);
		// Only check the "Hello " part (first 6 bytes)
		assertFalse(RFC2047Encoder.containsNonAscii(data, 0, 6));
		// Check from "Café" onwards
		assertTrue(RFC2047Encoder.containsNonAscii(data, 6, data.length));
	}

	// ========== getEncodingForData tests ==========

	@Test
	public void testGetEncodingForASCII() {
		// Mostly ASCII should prefer Q-encoding
		byte[] data = "Hello World".getBytes(StandardCharsets.US_ASCII);
		assertEquals("Q", RFC2047Encoder.getEncodingForData(data));
	}

	@Test
	public void testGetEncodingForMostlyNonASCII() {
		// Mostly non-ASCII should prefer B-encoding
		byte[] data = "日本語テスト".getBytes(StandardCharsets.UTF_8);
		assertEquals("B", RFC2047Encoder.getEncodingForData(data));
	}

	@Test
	public void testGetEncodingForEmpty() {
		assertEquals("B", RFC2047Encoder.getEncodingForData(new byte[0]));
	}

	// ========== encodeB tests ==========

	@Test
	public void testEncodeBSimple() {
		byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeB(data, "UTF-8");
		// Pure ASCII should not be encoded
		assertEquals("Hello", encoded);
	}

	@Test
	public void testEncodeBWithNonASCII() {
		byte[] data = "Café".getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeB(data, "UTF-8");
		// Should contain an encoded word
		assertTrue(encoded.contains("=?UTF-8?B?"));
		assertTrue(encoded.contains("?="));
		// Should be decodable
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Café", decoded);
	}

	@Test
	public void testEncodeBJapanese() {
		byte[] data = "日本語".getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeB(data, "UTF-8");
		assertTrue(encoded.contains("=?UTF-8?B?"));
		// Verify round-trip
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("日本語", decoded);
	}

	@Test
	public void testEncodeBMixed() {
		byte[] data = "Hello 日本語 World".getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeB(data, "UTF-8");
		// Should contain ASCII parts and encoded parts
		assertTrue(encoded.contains("Hello"));
		assertTrue(encoded.contains("=?UTF-8?B?"));
		// Verify round-trip
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Hello 日本語 World", decoded);
	}

	// ========== encodeQ tests ==========

	@Test
	public void testEncodeQSimple() {
		byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeQ(data, "UTF-8");
		// Pure ASCII should not be encoded
		assertEquals("Hello", encoded);
	}

	@Test
	public void testEncodeQWithNonASCII() {
		byte[] data = "Café".getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeQ(data, "UTF-8");
		assertTrue(encoded.contains("=?UTF-8?Q?"));
		// Verify round-trip
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Café", decoded);
	}

	@Test
	public void testEncodeQHighBytes() {
		// Test that high bytes are properly hex-encoded
		byte[] data = new byte[] { (byte) 0xC3, (byte) 0xA9 }; // UTF-8 for 'é'
		String encoded = RFC2047Encoder.encodeQ(data, "UTF-8");
		assertTrue(encoded.contains("=C3"));
		assertTrue(encoded.contains("=A9"));
	}

	// ========== encodeHeaderValue tests ==========

	@Test
	public void testEncodeHeaderValueASCII() {
		byte[] data = "Hello World".getBytes(StandardCharsets.US_ASCII);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		assertEquals("Hello World", encoded);
	}

	@Test
	public void testEncodeHeaderValueNonASCII() {
		byte[] data = "Café".getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		// Should be encoded
		assertTrue(encoded.contains("=?UTF-8?"));
		// Verify round-trip
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Café", decoded);
	}

	// ========== Round-trip tests ==========

	@Test
	public void testRoundTripSimple() {
		String original = "Hello World";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripFrench() {
		String original = "Café résumé naïve";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripGerman() {
		String original = "Größe Übung";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripJapanese() {
		String original = "日本語テスト";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripChinese() {
		String original = "中文测试";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripKorean() {
		String original = "한국어 테스트";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripRussian() {
		String original = "Русский текст";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripArabic() {
		String original = "نص عربي";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripEmoji() {
		String original = "Hello 👋 World 🌍";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}

	@Test
	public void testRoundTripLongMixed() {
		String original = "Subject: Re: Fwd: 日本語 meeting about Größe and café résumé 会议";
		byte[] data = original.getBytes(StandardCharsets.UTF_8);
		String encoded = RFC2047Encoder.encodeHeaderValue(data, StandardCharsets.UTF_8);
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals(original, decoded);
	}
}

