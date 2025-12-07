/*
 * RFC2047Decoder.java
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

package org.bluezoo.gumdrop.mime.rfc2047;

import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * High-performance decoder for RFC 2047 encoded-words and RFC 2231 parameter
 * encoding with support for:
 * <ul>
 * <li>RFC 2047 encoded-words (=?charset?encoding?encoded-text?=)</li>
 * <li>RFC 2231 parameter encoding (name*=charset'lang'%XX%YY...)</li>
 * <li>Multiple character sets and encodings</li>
 * </ul>
 *
 * This implementation uses character-by-character parsing instead of regex
 * for significantly better performance, especially for large headers.
 *
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2047'>RFC 2047</a>
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2231'>RFC 2231</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RFC2047Decoder {

	private RFC2047Decoder() {
		// Static utility class
	}

	// Pre-allocated StringBuilder pool for better memory efficiency
	private static final ThreadLocal<StringBuilder> STRING_BUILDER_POOL =
		ThreadLocal.withInitial(() -> new StringBuilder(256));

	private static StringBuilder getPooledStringBuilder() {
		StringBuilder sb = STRING_BUILDER_POOL.get();
		sb.setLength(0);
		return sb;
	}

	private static void returnPooledStringBuilder(StringBuilder sb) {
		if (sb.capacity() > 8192) {
			STRING_BUILDER_POOL.set(new StringBuilder(256));
		}
	}

	/**
	 * Main decoding method for RFC 822 header field-values.
	 * @param headerBytes raw header field-value bytes (without CRLF or continuation whitespace)
	 * @return decoded Unicode String
	 */
	public static String decodeHeaderValue(byte[] headerBytes) {
		if (headerBytes == null || headerBytes.length == 0) {
			return "";
		}
		try {
			String rawHeader = new String(headerBytes, StandardCharsets.ISO_8859_1);
			String decoded = decodeEncodedWords(rawHeader);
			decoded = handleRaw8BitData(decoded);
			return decoded;
		} catch (Exception e) {
			return fallbackDecode(headerBytes);
		}
	}

	/**
	 * Decode RFC 2047 encoded-words in a header value.
	 * @param input the header value possibly containing encoded-words
	 * @return the decoded string
	 */
	public static String decodeEncodedWords(String input) {
		if (input == null || input.isEmpty()) {
			return input;
		}

		StringBuilder result = getPooledStringBuilder();
		try {
			EncodedWordParser parser = new EncodedWordParser();
			List<EncodedWord> adjacentWords = new ArrayList<>();
			int pos = 0;
			int lastEnd = 0;

			while (parser.findNext(input, pos)) {
				if (parser.start > lastEnd) {
					String beforeText = input.substring(lastEnd, parser.start);
					if (!adjacentWords.isEmpty()) {
						result.append(decodeAdjacentEncodedWords(adjacentWords));
						adjacentWords.clear();
						if (!isWhitespaceOnly(beforeText)) {
							result.append(beforeText);
						}
					} else {
						result.append(beforeText);
					}
				}

				EncodedWord word = new EncodedWord(parser.charset, String.valueOf(parser.encoding),
					parser.encodedText, parser.start, parser.end);

				if (adjacentWords.isEmpty() || isAdjacent(input, adjacentWords.get(adjacentWords.size() - 1), word)) {
					adjacentWords.add(word);
				} else {
					result.append(decodeAdjacentEncodedWords(adjacentWords));
					adjacentWords.clear();
					adjacentWords.add(word);
				}

				lastEnd = parser.end;
				pos = parser.end;
			}

			if (!adjacentWords.isEmpty()) {
				result.append(decodeAdjacentEncodedWords(adjacentWords));
			}
			if (lastEnd < input.length()) {
				result.append(input.substring(lastEnd));
			}

			return result.toString();
		} finally {
			returnPooledStringBuilder(result);
		}
	}

	private static String decodeAdjacentEncodedWords(List<EncodedWord> words) {
		StringBuilder result = new StringBuilder();
		for (EncodedWord word : words) {
			try {
				String decoded = decodeSingleEncodedWord(word.charset, word.encoding, word.encodedText);
				result.append(decoded);
			} catch (Exception e) {
				result.append("=?").append(word.charset).append("?")
					.append(word.encoding).append("?").append(word.encodedText).append("?=");
			}
		}
		return result.toString();
	}

	private static String decodeSingleEncodedWord(String charset, String encoding, String encodedText)
			throws UnsupportedEncodingException {
		byte[] decodedBytes;
		if ("B".equals(encoding)) {
			if (!isValidBase64(encodedText)) {
				throw new UnsupportedEncodingException("Invalid Base64 encoding: " + encodedText);
			}
			decodedBytes = Base64.getDecoder().decode(encodedText);
		} else if ("Q".equals(encoding)) {
			decodedBytes = decodeQEncoding(encodedText);
		} else {
			throw new UnsupportedEncodingException("Unsupported encoding: " + encoding);
		}
		return bytesToString(decodedBytes, charset);
	}

	private static byte[] decodeQEncoding(String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			return new byte[0];
		}

		byte[] result = new byte[encoded.length()];
		int writePos = 0;
		int length = encoded.length();

		for (int i = 0; i < length; i++) {
			char c = encoded.charAt(i);
			if (c == '_') {
				result[writePos++] = ' ';
			} else if (c == '=' && i + 2 < length) {
				char h1 = encoded.charAt(i + 1);
				char h2 = encoded.charAt(i + 2);
				int value = fastHexDecode(h1, h2);
				if (value >= 0) {
					result[writePos++] = (byte) value;
					i += 2;
				} else {
					result[writePos++] = (byte) c;
				}
			} else {
				result[writePos++] = (byte) c;
			}
		}

		if (writePos == result.length) {
			return result;
		} else {
			byte[] trimmed = new byte[writePos];
			System.arraycopy(result, 0, trimmed, 0, writePos);
			return trimmed;
		}
	}

	private static String bytesToString(byte[] bytes, String charsetName) {
		try {
			charsetName = normalizeCharsetName(charsetName);
			Charset charset = Charset.forName(charsetName);
			return new String(bytes, charset);
		} catch (UnsupportedCharsetException e) {
			try {
				return new String(bytes, StandardCharsets.UTF_8);
			} catch (Exception e2) {
				return new String(bytes, StandardCharsets.ISO_8859_1);
			}
		}
	}

	private static final Map<String, String> NORMALIZED_CHARSETS;
	static {
		NORMALIZED_CHARSETS = new TreeMap<>();
		NORMALIZED_CHARSETS.put("UTF8", "UTF-8");
		NORMALIZED_CHARSETS.put("WIN1252", "windows-1252");
		NORMALIZED_CHARSETS.put("LATIN1", "ISO-8859-1");
		NORMALIZED_CHARSETS.put("KOI8R", "KOI8-R");
		NORMALIZED_CHARSETS.put("KOI8U", "KOI8-U");
		NORMALIZED_CHARSETS.put("ISO88591", "ISO-8859-1");
		NORMALIZED_CHARSETS.put("ISO-88591", "ISO-8859-1");
		NORMALIZED_CHARSETS.put("ISO885915", "ISO-8859-15");
		NORMALIZED_CHARSETS.put("ISO-885915", "ISO-8859-15");
		NORMALIZED_CHARSETS.put("WINDOWS1252", "windows-1252");
	}

	private static String normalizeCharsetName(String charset) {
		if (charset == null) {
			return "UTF-8";
		}
		if ("UTF-8".equals(charset) || "ISO-8859-1".equals(charset) || "windows-1252".equals(charset)) {
			return charset;
		}
		String normalized = NORMALIZED_CHARSETS.get(charset.toUpperCase().trim());
		return normalized != null ? normalized : charset;
	}

	private static boolean isAdjacent(String input, EncodedWord prev, EncodedWord current) {
		if (prev.end >= current.start) {
			return false;
		}
		for (int i = prev.end; i < current.start; i++) {
			char c = input.charAt(i);
			if (!isWhitespace(c)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n';
	}

	private static boolean isWhitespaceOnly(String text) {
		if (text == null || text.isEmpty()) {
			return true;
		}
		for (int i = 0; i < text.length(); i++) {
			if (!isWhitespace(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String handleRaw8BitData(String input) {
		if (!hasNonAsciiData(input)) {
			return input;
		}
		byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
		try {
			String utf8Test = new String(bytes, StandardCharsets.UTF_8);
			if (!containsReplacementChar(utf8Test)) {
				return utf8Test;
			}
		} catch (Exception e) {
			// Fall through
		}
		try {
			return new String(bytes, "windows-1252");
		} catch (Exception e) {
			return input;
		}
	}

	private static String fallbackDecode(byte[] bytes) {
		try {
			String result = new String(bytes, StandardCharsets.UTF_8);
			if (!containsReplacementChar(result)) {
				return result;
			}
		} catch (Exception e) {
			// Continue
		}
		try {
			return new String(bytes, "windows-1252");
		} catch (Exception e) {
			return new String(bytes, StandardCharsets.ISO_8859_1);
		}
	}

	/**
	 * Decode RFC 2231 parameter values (for MIME parameters).
	 * @param paramValue parameter value (e.g., "utf-8'en'Hello%20World")
	 * @return the decoded string
	 */
	public static String decodeRFC2231Parameter(String paramValue) {
		if (paramValue == null || paramValue.isEmpty()) {
			return paramValue;
		}
		RFC2231ParseResult result = parseRFC2231Parameter(paramValue);
		if (result == null) {
			return paramValue;
		}
		try {
			// Decode percent-encoding to raw bytes (using ISO-8859-1 preserves byte values)
			String raw = URLDecoder.decode(result.encoded, StandardCharsets.ISO_8859_1);
			// Convert raw bytes to string using the specified charset
			return bytesToString(raw.getBytes(StandardCharsets.ISO_8859_1), result.charset);
		} catch (Exception e) {
			return paramValue;
		}
	}

	private static boolean containsReplacementChar(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == 0xFFFD) {
				return true;
			}
		}
		return false;
	}

	// ===== Utility methods =====

	private static boolean hasNonAsciiData(String input) {
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == 0 || c > 0x7f) {
				return true;
			}
		}
		return false;
	}

	private static boolean isValidBase64(String input) {
		if (input == null || input.isEmpty()) {
			return true;
		}
		int length = input.length();
		if (length % 4 != 0) {
			return false;
		}
		int paddingCount = 0;
		for (int i = 0; i < length; i++) {
			char c = input.charAt(i);
			if (c == '=') {
				paddingCount++;
				if (i < length - 2 || paddingCount > 2) {
					return false;
				}
			} else if (!isBase64Char(c)) {
				return false;
			} else if (paddingCount > 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean isBase64Char(char c) {
		return (c >= 'A' && c <= 'Z') ||
			   (c >= 'a' && c <= 'z') ||
			   (c >= '0' && c <= '9') ||
			   c == '+' || c == '/';
	}

	private static final byte[] HEX_DECODE_TABLE = new byte[128];
	static {
		for (int i = 0; i < 128; i++) {
			HEX_DECODE_TABLE[i] = -1;
		}
		for (int i = 0; i < 10; i++) {
			HEX_DECODE_TABLE['0' + i] = (byte) i;
		}
		for (int i = 0; i < 6; i++) {
			HEX_DECODE_TABLE['A' + i] = (byte) (i + 10);
			HEX_DECODE_TABLE['a' + i] = (byte) (i + 10);
		}
	}

	private static int fastHexDecode(char h1, char h2) {
		int v1 = (h1 < 128) ? HEX_DECODE_TABLE[h1] : -1;
		int v2 = (h2 < 128) ? HEX_DECODE_TABLE[h2] : -1;
		if (v1 < 0 || v2 < 0) {
			return -1;
		}
		return (v1 << 4) | v2;
	}

	// ===== Inner classes =====

	private static class EncodedWord {
		final String charset;
		final String encoding;
		final String encodedText;
		final int start;
		final int end;

		EncodedWord(String charset, String encoding, String encodedText, int start, int end) {
			this.charset = charset;
			this.encoding = encoding;
			this.encodedText = encodedText;
			this.start = start;
			this.end = end;
		}
	}

	private static class EncodedWordParser {
		String charset;
		char encoding;
		String encodedText;
		int start;
		int end;

		boolean findNext(String input, int startPos) {
			int pos = startPos;
			int length = input.length();

			while (pos < length - 1) {
				if (input.charAt(pos) == '=' && input.charAt(pos + 1) == '?') {
					break;
				}
				pos++;
			}

			if (pos >= length - 1) {
				return false;
			}

			this.start = pos;
			pos += 2;

			int charsetStart = pos;
			while (pos < length && input.charAt(pos) != '?') {
				pos++;
			}

			if (pos >= length) {
				return false;
			}

			this.charset = input.substring(charsetStart, pos);
			pos++;

			if (pos >= length) {
				return false;
			}

			char enc = input.charAt(pos);
			if (enc != 'B' && enc != 'b' && enc != 'Q' && enc != 'q') {
				return false;
			}
			this.encoding = Character.toUpperCase(enc);
			pos++;

			if (pos >= length || input.charAt(pos) != '?') {
				return false;
			}
			pos++;

			int encodedStart = pos;
			while (pos < length - 1) {
				if (input.charAt(pos) == '?' && input.charAt(pos + 1) == '=') {
					break;
				}
				pos++;
			}

			if (pos >= length - 1) {
				return false;
			}

			this.encodedText = input.substring(encodedStart, pos);
			pos += 2;
			this.end = pos;

			return true;
		}
	}

	private static RFC2231ParseResult parseRFC2231Parameter(String paramValue) {
		if (paramValue == null || paramValue.isEmpty()) {
			return null;
		}

		int starEqPos = paramValue.indexOf("*=");
		if (starEqPos <= 0) {
			return null;
		}

		String name = paramValue.substring(0, starEqPos);
		int pos = starEqPos + 2;

		int charsetStart = pos;
		while (pos < paramValue.length() && paramValue.charAt(pos) != '\'') {
			pos++;
		}

		if (pos >= paramValue.length()) {
			return null;
		}

		String charset = paramValue.substring(charsetStart, pos);
		pos++;

		int languageStart = pos;
		while (pos < paramValue.length() && paramValue.charAt(pos) != '\'') {
			pos++;
		}

		if (pos >= paramValue.length()) {
			return null;
		}

		String language = paramValue.substring(languageStart, pos);
		pos++;

		String encoded = paramValue.substring(pos);

		return new RFC2231ParseResult(name, charset, language, encoded);
	}

	private static class RFC2231ParseResult {
		final String name;
		final String charset;
		final String language;
		final String encoded;

		RFC2231ParseResult(String name, String charset, String language, String encoded) {
			this.name = name;
			this.charset = charset;
			this.language = language;
			this.encoded = encoded;
		}
	}

}

