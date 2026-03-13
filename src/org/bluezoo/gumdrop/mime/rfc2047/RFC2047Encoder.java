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
 * <p>Implements selective encoding that groups nearby non-ASCII sequences
 * to reduce fragmentation while maintaining compatibility with
 * native message-library behaviour.
 *
 * <p>RFC 2047 §2 requires that each encoded-word be no longer than
 * 75 characters. This encoder splits segments accordingly.
 *
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2047#section-2'>RFC 2047 §2</a>
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2047#section-5'>RFC 2047 §5</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RFC2047Encoder {

	/** RFC 2047 §2 — an encoded-word MUST NOT be more than 75 characters long. */
	static final int MAX_ENCODED_WORD_LENGTH = 75;

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
	 * <p>RFC 2047 §2: each encoded-word is limited to 75 characters total.
	 * The overhead is {@code =?charset?B?...?=}, so the maximum Base64
	 * payload length per word is {@code 75 - overhead}. The raw byte
	 * chunk size is derived from that via standard Base64 sizing.
	 *
	 * @param header raw header bytes to encode
	 * @param start start index
	 * @param end end index (exclusive)
	 * @param charset charset name for RFC 2047 header
	 * @return RFC 2047 encoded string with selective encoding applied
	 */
	public static String encodeB(byte[] header, int start, int end, String charset) {
		// RFC 2047 §2 overhead: =?charset?B?...?=
		int overhead = 2 + charset.length() + 3 + 2; // "=?" + charset + "?B?" + "?="
		int maxBase64Chars = MAX_ENCODED_WORD_LENGTH - overhead;
		int maxRawBytes = (maxBase64Chars / 4) * 3; // Base64: 4 chars per 3 bytes
		if (maxRawBytes < 1) {
			maxRawBytes = 1;
		}

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

				int offset = 0;

				while (offset < segmentBytes.length) {
					int chunkSize = Math.min(maxRawBytes, segmentBytes.length - offset);

					// Avoid splitting in the middle of a UTF-8 multi-byte sequence
					while (chunkSize > 0 && offset + chunkSize < segmentBytes.length) {
						byte b = segmentBytes[offset + chunkSize];
						if ((b & 0x80) == 0) break;   // ASCII — safe
						if ((b & 0xC0) == 0xC0) break; // UTF-8 lead byte — safe
						chunkSize--;
					}

					if (chunkSize <= 0) {
						chunkSize = 1;
					}

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
	 * <p>RFC 2047 §2: each encoded-word is limited to 75 characters total.
	 * Each Q-encoded non-ASCII byte requires 3 characters ({@code =XX}),
	 * so the maximum number of encoded bytes per word is
	 * {@code (75 - overhead) / 3}.
	 *
	 * @param header raw header bytes to encode
	 * @param charset charset name for RFC 2047 header
	 * @return RFC 2047 encoded string with Q-encoding applied
	 */
	public static String encodeQ(byte[] header, String charset) {
		// RFC 2047 §2 overhead: =?charset?Q?...?=
		int overhead = 2 + charset.length() + 3 + 2; // "=?" + charset + "?Q?" + "?="
		int maxEncodedChars = MAX_ENCODED_WORD_LENGTH - overhead;

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
				int j = i;
				while (j < segmentEnd) {
					result.append("=?").append(charset).append("?Q?");
					int charsUsed = 0;
					while (j < segmentEnd && charsUsed + 3 <= maxEncodedChars) {
						int byteValue = header[j] & 0xFF;
						result.append("=").append(String.format("%02X", byteValue));
						charsUsed += 3;
						j++;
					}
					result.append("?=");
				}
				i = segmentEnd;
			}
		}

		return result.toString();
	}

}

