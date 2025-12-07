/*
 * RFC2047DecoderTest.java
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
 * Unit tests for RFC2047Decoder.
 */
public class RFC2047DecoderTest {

	// ========== decodeEncodedWords tests ==========

	@Test
	public void testDecodeSimpleBase64() {
		// =?UTF-8?B?SGVsbG8=?= is "Hello" in Base64
		String encoded = "=?UTF-8?B?SGVsbG8=?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Hello", decoded);
	}

	@Test
	public void testDecodeSimpleQuotedPrintable() {
		// =?UTF-8?Q?Hello?= should decode to "Hello"
		String encoded = "=?UTF-8?Q?Hello?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Hello", decoded);
	}

	@Test
	public void testDecodeQEncodingWithUnderscore() {
		// In Q-encoding, underscore represents space
		String encoded = "=?UTF-8?Q?Hello_World?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Hello World", decoded);
	}

	@Test
	public void testDecodeQEncodingWithHex() {
		// =XX hex escapes in Q-encoding
		String encoded = "=?UTF-8?Q?Caf=C3=A9?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Café", decoded);
	}

	@Test
	public void testDecodeLowercaseEncoding() {
		// Lowercase 'b' and 'q' should work
		String encodedB = "=?UTF-8?b?SGVsbG8=?=";
		String encodedQ = "=?UTF-8?q?Hello?=";
		assertEquals("Hello", RFC2047Decoder.decodeEncodedWords(encodedB));
		assertEquals("Hello", RFC2047Decoder.decodeEncodedWords(encodedQ));
	}

	@Test
	public void testDecodeMixedPlainAndEncoded() {
		// Plain text before and after encoded word
		String encoded = "Subject: =?UTF-8?B?SGVsbG8=?= World";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Subject: Hello World", decoded);
	}

	@Test
	public void testDecodeAdjacentEncodedWords() {
		// Adjacent encoded words with whitespace between should be concatenated
		String encoded = "=?UTF-8?B?SGVs?= =?UTF-8?B?bG8=?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Hello", decoded);
	}

	@Test
	public void testDecodeISO88591() {
		// ISO-8859-1 encoded word
		String encoded = "=?ISO-8859-1?Q?Caf=E9?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Café", decoded);
	}

	@Test
	public void testDecodeWindows1252() {
		// Windows-1252 charset
		String encoded = "=?windows-1252?Q?=93Hello=94?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		// Windows-1252 has smart quotes at 0x93 and 0x94
		assertTrue(decoded.contains("Hello"));
	}

	@Test
	public void testDecodeJapanese() {
		// Japanese text in UTF-8 Base64
		// "日本語" = E6 97 A5 E6 9C AC E8 AA 9E
		String encoded = "=?UTF-8?B?5pel5pys6Kqe?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("日本語", decoded);
	}

	@Test
	public void testDecodeEmptyString() {
		assertEquals("", RFC2047Decoder.decodeEncodedWords(""));
		assertNull(RFC2047Decoder.decodeEncodedWords(null));
	}

	@Test
	public void testDecodePlainASCII() {
		// Plain ASCII without any encoding should pass through unchanged
		String plain = "Hello World";
		assertEquals(plain, RFC2047Decoder.decodeEncodedWords(plain));
	}

	@Test
	public void testDecodeInvalidEncodedWord() {
		// Invalid encoded word should be left as-is
		String invalid = "=?UTF-8?X?Invalid?="; // X is not a valid encoding
		String decoded = RFC2047Decoder.decodeEncodedWords(invalid);
		assertEquals(invalid, decoded);
	}

	@Test
	public void testDecodeIncompleteEncodedWord() {
		// Incomplete encoded word
		String incomplete = "=?UTF-8?B?SGVsbG8";
		String decoded = RFC2047Decoder.decodeEncodedWords(incomplete);
		assertEquals(incomplete, decoded);
	}

	// ========== decodeHeaderValue tests ==========

	@Test
	public void testDecodeHeaderValueSimple() {
		byte[] header = "Hello World".getBytes(StandardCharsets.US_ASCII);
		String decoded = RFC2047Decoder.decodeHeaderValue(header);
		assertEquals("Hello World", decoded);
	}

	@Test
	public void testDecodeHeaderValueWithEncodedWord() {
		byte[] header = "=?UTF-8?B?SGVsbG8=?= World".getBytes(StandardCharsets.US_ASCII);
		String decoded = RFC2047Decoder.decodeHeaderValue(header);
		assertEquals("Hello World", decoded);
	}

	@Test
	public void testDecodeHeaderValueEmpty() {
		assertEquals("", RFC2047Decoder.decodeHeaderValue(new byte[0]));
		assertEquals("", RFC2047Decoder.decodeHeaderValue(null));
	}

	// ========== decodeRFC2231Parameter tests ==========

	@Test
	public void testDecodeRFC2231Simple() {
		// RFC 2231 format: charset'language'encoded-value
		String param = "filename*=UTF-8''Hello%20World";
		String decoded = RFC2047Decoder.decodeRFC2231Parameter(param);
		assertEquals("Hello World", decoded);
	}

	@Test
	public void testDecodeRFC2231Japanese() {
		// Japanese filename in RFC 2231
		String param = "filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E.txt";
		String decoded = RFC2047Decoder.decodeRFC2231Parameter(param);
		assertEquals("日本語.txt", decoded);
	}

	@Test
	public void testDecodeRFC2231WithLanguage() {
		// With language tag
		String param = "filename*=UTF-8'en'Hello%20World";
		String decoded = RFC2047Decoder.decodeRFC2231Parameter(param);
		assertEquals("Hello World", decoded);
	}

	@Test
	public void testDecodeRFC2231NotEncoded() {
		// Plain value without encoding
		String param = "filename=test.txt";
		String decoded = RFC2047Decoder.decodeRFC2231Parameter(param);
		// Should return as-is since it's not RFC 2231 format
		assertEquals(param, decoded);
	}

	@Test
	public void testDecodeRFC2231Empty() {
		assertEquals("", RFC2047Decoder.decodeRFC2231Parameter(""));
		assertNull(RFC2047Decoder.decodeRFC2231Parameter(null));
	}

	// ========== Edge cases ==========

	@Test
	public void testDecodeMultipleEncodedWordsInSubject() {
		// Common real-world scenario: long subject split across multiple encoded words
		String encoded = "=?UTF-8?Q?This_is_a?= =?UTF-8?Q?_long_subject?=";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("This is a long subject", decoded);
	}

	@Test
	public void testDecodeEncodedWordInMiddle() {
		String encoded = "Re: =?UTF-8?B?SGVsbG8=?= message";
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("Re: Hello message", decoded);
	}

	@Test
	public void testDecodeSpecialCharactersInQ() {
		// Q-encoding of special characters
		String encoded = "=?UTF-8?Q?test=3F=3D?="; // =3F is ?, =3D is =
		String decoded = RFC2047Decoder.decodeEncodedWords(encoded);
		assertEquals("test?=", decoded);
	}

	@Test
	public void testDecodeCharsetNormalization() {
		// Various charset name formats should be normalized
		String encoded1 = "=?utf8?B?SGVsbG8=?=";
		String encoded2 = "=?UTF8?B?SGVsbG8=?=";
		assertEquals("Hello", RFC2047Decoder.decodeEncodedWords(encoded1));
		assertEquals("Hello", RFC2047Decoder.decodeEncodedWords(encoded2));
	}
}

