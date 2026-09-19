/*
 * RFC2231DecoderTest.java
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

package org.bluezoo.gumdrop.mime.rfc2231;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for Rfc2231Decoder.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
		String out = Rfc2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertEquals("naïve", out);
		assertFalse(buf.hasRemaining());
	}

	@Test
	public void testDecodeCharsetLangValue() {
		ByteBuffer buf = ByteBuffer.wrap("iso-8859-1'en'Hello%20World".getBytes(StandardCharsets.ISO_8859_1));
		String out = Rfc2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertEquals("Hello World", out);
		assertFalse(buf.hasRemaining());
	}

	@Test
	public void testDecodeWithQuotes() {
		ByteBuffer buf = ByteBuffer.wrap("\"UTF-8''%C3%A9\"".getBytes(StandardCharsets.ISO_8859_1));
		String out = Rfc2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertNotNull(out);
		assertEquals("é", out);
		assertFalse(buf.hasRemaining());
	}

	@Test
	public void testNotRFC2231FormatReturnsNull() {
		ByteBuffer buf = ByteBuffer.wrap("plain".getBytes(StandardCharsets.ISO_8859_1));
		String out = Rfc2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertNull(out);
	}

	@Test
	public void testEmptyValue() {
		ByteBuffer buf = ByteBuffer.wrap("UTF-8''".getBytes(StandardCharsets.ISO_8859_1));
		String out = Rfc2231Decoder.decodeParameterValue(buf, fallbackDecoder());
		assertEquals("", out);
		assertFalse(buf.hasRemaining());
	}
}
