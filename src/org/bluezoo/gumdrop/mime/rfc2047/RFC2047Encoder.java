/*
 * RFC2047Encoder.java
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * High-performance RFC 2047 encoder for MIME header encoding.
 *
 * Implements selective encoding that groups nearby non-ASCII sequences
 * to reduce fragmentation while maintaining compatibility with
 * native message-library behavior.
 *
 * This implementation uses character-by-character processing for
 * optimal performance and precise control over encoding boundaries.
 *
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2047'>RFC 2047</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RFC2047Encoder {

	private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

	private RFC2047Encoder() {
		// Static utility class
	}

	/**
	 * Check if the header contains any non-ASCII bytes (&gt; 127).
	 * @param header the header bytes to check
	 * @return true if the header contains non-ASCII bytes
	 */
	public static boolean containsNonAscii(byte[] header) {
		return containsNonAscii(header, 0, header.length);
	}

	/**
	 * Check if the header contains any non-ASCII bytes (&gt; 127).
	 * @param header the header bytes to check
	 * @param start start index
	 * @param end end index (exclusive)
	 * @return true if the header contains non-ASCII bytes
	 */
	public static boolean containsNonAscii(byte[] header, int start, int end) {
		for (int i = start; i < end; i++) {
			if ((header[i] & 0xFF) > 127) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determines the optimal encoding ("B" for Base64 or "Q" for Quoted-Printable)
	 * based on which will produce a smaller encoded result.
	 *
	 * <p>Heuristic: Q-encoding encodes each byte that requires encoding as 3 characters (=XX),
	 * while B-encoding (Base64) expands all data by approximately 4/3 (33%).
	 * If more than ~16.7% of bytes need encoding, Base64 is more efficient.
	 *
	 * @param data the bytes to analyze
	 * @return "B" for Base64 or "Q" for Quoted-Printable
	 */
	public static String getEncodingForData(byte[] data) {
		return getEncodingForData(data, 0, data.length);
	}

	/**
	 * Determines the optimal encoding for a portion of data.
	 *
	 * @param data the bytes to analyze
	 * @param start start index (inclusive)
	 * @param end end index (exclusive)
	 * @return "B" for Base64 or "Q" for Quoted-Printable
	 */
	public static String getEncodingForData(byte[] data, int start, int end) {
		int bytesNeedingEncoding = 0;
		int totalBytes = end - start;

		if (totalBytes == 0) {
			return "B";
		}

		for (int i = start; i < end; i++) {
			int b = data[i] & 0xFF;
			if (b > 127 || b < 32 || b == '?' || b == '=' || b == '_') {
				bytesNeedingEncoding++;
			}
		}

		return (bytesNeedingEncoding * 6 < totalBytes) ? "Q" : "B";
	}

	/**
	 * Encode a header value using RFC 2047.
	 *
	 * @param headerValue raw header value bytes to encode
	 * @param charset the charset to declare in the encoded-word
	 * @return RFC 2047 encoded string with selective encoding applied
	 */
	public static String encodeHeaderValue(byte[] headerValue, Charset charset) {
		String encoding = getEncodingForData(headerValue);
		return encodeWithCharset(headerValue, charset.name(), encoding);
	}

	private static String encodeWithCharset(byte[] header, String charset, String encoding) {
		if ("Q".equals(encoding)) {
			return encodeQ(header, charset);
		} else {
			return encodeB(header, charset);
		}
	}

	/**
	 * Encode using RFC 2047 Base64 format with specified charset.
	 * Groups nearby non-ASCII sequences to reduce fragmentation.
	 *
	 * @param header raw header bytes to encode
	 * @param charset charset name for RFC 2047 header
	 * @return RFC 2047 encoded string with selective encoding applied
	 */
	public static String encodeB(byte[] header, String charset) {
		return encodeB(header, 0, header.length, charset);
	}

	/**
	 * Encode using RFC 2047 Base64 format with specified charset.
	 *
	 * @param header raw header bytes to encode
	 * @param start start index
	 * @param end end index (exclusive)
	 * @param charset charset name for RFC 2047 header
	 * @return RFC 2047 encoded string with selective encoding applied
	 */
	public static String encodeB(byte[] header, int start, int end, String charset) {
		StringBuilder result = new StringBuilder();
		int i = start;

		while (i < end) {
			int segmentStart = i;
			while (segmentStart < end && (header[segmentStart] & 0xFF) <= 127) {
				segmentStart++;
			}

			if (segmentStart > i) {
				String asciiPart = new String(header, i, segmentStart - i, ISO_8859_1);
				result.append(asciiPart);
				i = segmentStart;
			}

			if (i >= end) {
				break;
			}

			int segmentEnd = i;
			while (segmentEnd < end) {
				if ((header[segmentEnd] & 0xFF) > 127) {
					segmentEnd++;
				} else {
					char asciiChar = (char) (header[segmentEnd] & 0xFF);
					if (asciiChar == '<' || asciiChar == '>') {
						break;
					} else if (asciiChar == '"' && segmentEnd == i) {
						break;
					} else {
						segmentEnd++;
					}
				}
			}

			if (segmentEnd > i) {
				byte[] segmentBytes = new byte[segmentEnd - i];
				System.arraycopy(header, i, segmentBytes, 0, segmentEnd - i);

				int maxBase64Length = 500;
				int offset = 0;

				while (offset < segmentBytes.length) {
					int chunkSize = Math.min(maxBase64Length * 3 / 4, segmentBytes.length - offset);

					while (chunkSize > 0 && offset + chunkSize < segmentBytes.length) {
						byte b = segmentBytes[offset + chunkSize];
						if ((b & 0x80) == 0) break;
						if ((b & 0xC0) == 0xC0) break;
						chunkSize--;
					}

					if (chunkSize <= 0) chunkSize = 1;

					byte[] chunk = new byte[chunkSize];
					System.arraycopy(segmentBytes, offset, chunk, 0, chunkSize);

					String base64 = Base64.getEncoder().encodeToString(chunk);
					result.append("=?").append(charset).append("?B?").append(base64).append("?=");

					offset += chunkSize;
				}

				i = segmentEnd;
			}
		}

		return result.toString();
	}

	/**
	 * Encode using RFC 2047 Q-encoding (Quoted-Printable) format with specified charset.
	 *
	 * @param header raw header bytes to encode
	 * @param charset charset name for RFC 2047 header
	 * @return RFC 2047 encoded string with Q-encoding applied
	 */
	public static String encodeQ(byte[] header, String charset) {
		StringBuilder result = new StringBuilder();
		int i = 0;

		while (i < header.length) {
			int segmentStart = i;
			while (segmentStart < header.length && (header[segmentStart] & 0xFF) <= 127) {
				segmentStart++;
			}

			if (segmentStart > i) {
				String asciiPart = new String(header, i, segmentStart - i, ISO_8859_1);
				result.append(asciiPart);
				i = segmentStart;
			}

			if (i >= header.length) {
				break;
			}

			int segmentEnd = i;
			while (segmentEnd < header.length && (header[segmentEnd] & 0xFF) > 127) {
				segmentEnd++;
			}

			if (segmentEnd > i) {
				result.append("=?").append(charset).append("?Q?");

				for (int j = i; j < segmentEnd; j++) {
					int byteValue = header[j] & 0xFF;
					result.append("=").append(String.format("%02X", byteValue));
				}

				result.append("?=");
				i = segmentEnd;
			}
		}

		return result.toString();
	}

}

