/*
 * RFC2231Decoder.java
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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Decoder for RFC 2231 extended parameter values (charset'lang'percent-encoded).
 * Operates on ByteBuffer and advances position past the value.
 *
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2231'>RFC 2231</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RFC2231Decoder {

	private RFC2231Decoder() {
	}

	/**
	 * Decodes one RFC 2231 parameter value from the buffer.
	 * Format: optional leading {@code "}, then {@code charset'lang'percent-encoded}, optional trailing {@code "}.
	 * Advances {@code value.position()} to the end of the value (including trailing quote if present).
	 *
	 * @param value the buffer (position at start of value); position is advanced to end of value
	 * @param fallback decoder used when charset is invalid or missing
	 * @return decoded string, or null if the value is not in RFC 2231 format
	 */
	public static String decodeParameterValue(ByteBuffer value, CharsetDecoder fallback) {
		if (value == null || !value.hasRemaining()) {
			return null;
		}
		int limit = value.limit();

		// Optional leading quote
		if (value.get(value.position()) == '"') {
			value.position(value.position() + 1);
		}
		if (!value.hasRemaining()) {
			return null;
		}

		// Charset: consume ASCII up to first '
		String charset = consumeAsciiUntil(value, (byte) '\'');
		if (charset == null || charset.isEmpty()) {
			return null;
		}
		value.position(value.position() + 1); // skip the '
		if (!value.hasRemaining()) {
			return null;
		}

		// Lang: consume up to second ' (we don't use it)
		if (consumeAsciiUntil(value, (byte) '\'') == null) {
			return null;
		}
		value.position(value.position() + 1); // skip the '
		int encodedStart = value.position();
		int encodedEnd = limit;
		if (encodedEnd > encodedStart && value.get(encodedEnd - 1) == '"') {
			encodedEnd--;
		}
		if (encodedStart >= encodedEnd) {
			value.position(limit);
			return "";
		}

		byte[] decodedBytes = percentDecode(value, encodedStart, encodedEnd);
		Charset cs;
		try {
			cs = Charset.forName(normalizeCharset(charset));
		} catch (UnsupportedCharsetException e) {
			cs = fallback != null ? fallback.charset() : StandardCharsets.ISO_8859_1;
		}
		String result = new String(decodedBytes, cs);
		value.position(limit);
		return result;
	}

	/** Consumes bytes from value.position() until delimiter or limit; advances position. Returns ASCII string or null if invalid. */
	private static String consumeAsciiUntil(ByteBuffer value, byte delimiter) {
		int limit = value.limit();
		StringBuilder sb = new StringBuilder();
		while (value.position() < limit) {
			byte b = value.get(value.position());
			if (b == delimiter) {
				return sb.toString().trim();
			}
			if (b < 0x20 || b > 0x7e) {
				return null;
			}
			sb.append((char) b);
			value.position(value.position() + 1);
		}
		return null;
	}

	private static String normalizeCharset(String charset) {
		if (charset == null) {
			return "UTF-8";
		}
		String u = charset.toUpperCase().trim();
		if ("UTF8".equals(u) || "UTF-8".equals(charset)) {
			return "UTF-8";
		}
		if ("ISO88591".equals(u) || "ISO-88591".equals(u) || "LATIN1".equals(u)) {
			return "ISO-8859-1";
		}
		return charset;
	}

	/** Percent-decode region [start, end) in one pass; ISO-8859-1 for %XX byte values. */
	private static byte[] percentDecode(ByteBuffer value, int start, int end) {
		int len = end - start;
		byte[] out = new byte[len];
		int write = 0;
		int i = start;
		while (i < end) {
			byte b = value.get(i);
			if (b == '%' && i + 2 < end) {
				int hi = hexValue(value.get(i + 1));
				int lo = hexValue(value.get(i + 2));
				if (hi >= 0 && lo >= 0) {
					out[write++] = (byte) ((hi << 4) | lo);
					i += 3;
					continue;
				}
			}
			out[write++] = b;
			i++;
		}
		if (write == out.length) {
			return out;
		}
		byte[] trimmed = new byte[write];
		System.arraycopy(out, 0, trimmed, 0, write);
		return trimmed;
	}

	private static int hexValue(byte b) {
		if (b >= '0' && b <= '9') {
			return b - '0';
		}
		if (b >= 'A' && b <= 'F') {
			return b - 'A' + 10;
		}
		if (b >= 'a' && b <= 'f') {
			return b - 'a' + 10;
		}
		return -1;
	}
}
