/*
 * QuotedPrintableDecoderTest.java
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

/**
 * Unit tests for QuotedPrintableDecoder.
 */
public class QuotedPrintableDecoderTest {

	private String decode(String encoded) {
		ByteBuffer src = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.ISO_8859_1));
		ByteBuffer dst = ByteBuffer.allocate(QuotedPrintableDecoder.estimateDecodedSize(encoded.length()));
		DecodeResult result = QuotedPrintableDecoder.decode(src, dst, dst.capacity());
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
		assertEquals("=GG", decodeISO("=GG"));
	}

	@Test
	public void testDecodeEqualsAtEnd() {
		assertEquals("Hello=", decodeISO("Hello="));
	}

	@Test
	public void testDecodeTabAndNewline() {
		assertEquals("Hello\tWorld\nTest", decode("Hello\tWorld\nTest"));
	}

	@Test
	public void testDecodeJapaneseUTF8() {
		assertEquals("日本", decode("=E6=97=A5=E6=9C=AC"));
	}
}
