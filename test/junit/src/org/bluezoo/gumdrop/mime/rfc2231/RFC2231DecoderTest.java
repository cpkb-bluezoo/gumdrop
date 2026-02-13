/*
 * RFC2231DecoderTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop.
 */

package org.bluezoo.gumdrop.mime.rfc2231;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for RFC2231Decoder.
 */
public class RFC2231DecoderTest {

	private static CharsetDecoder fallbackDecoder() {
		return StandardCharsets.ISO_8859_1.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);
	}

	@Test
	public void testDecodeUtf8PercentEncoded() {
		// UTF-8''na%C3%AFve → "naïve" (C3 AF is UTF-8 for ï)
		ByteBuffer buf = ByteBuffer.wrap("UTF-8''na%C3%AFve".getBytes(StandardCharsets.ISO_8859_1));
		String out = RFC2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertEquals("naïve", out);
		assertFalse(buf.hasRemaining());
	}

	@Test
	public void testDecodeCharsetLangValue() {
		ByteBuffer buf = ByteBuffer.wrap("iso-8859-1'en'Hello%20World".getBytes(StandardCharsets.ISO_8859_1));
		String out = RFC2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertEquals("Hello World", out);
		assertFalse(buf.hasRemaining());
	}

	@Test
	public void testDecodeWithQuotes() {
		ByteBuffer buf = ByteBuffer.wrap("\"UTF-8''%C3%A9\"".getBytes(StandardCharsets.ISO_8859_1));
		String out = RFC2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertNotNull(out);
		assertEquals("é", out);
		assertFalse(buf.hasRemaining());
	}

	@Test
	public void testNotRFC2231FormatReturnsNull() {
		ByteBuffer buf = ByteBuffer.wrap("plain".getBytes(StandardCharsets.ISO_8859_1));
		String out = RFC2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertNull(out);
	}

	@Test
	public void testEmptyValue() {
		ByteBuffer buf = ByteBuffer.wrap("UTF-8''".getBytes(StandardCharsets.ISO_8859_1));
		String out = RFC2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertEquals("", out);
		assertFalse(buf.hasRemaining());
	}
}
