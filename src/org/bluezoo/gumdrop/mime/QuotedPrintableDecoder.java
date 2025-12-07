/*
 * QuotedPrintableDecoder.java
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

package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;

/**
 * High-performance Quoted-Printable decoder for Content-Transfer-Encoding processing.
 * Optimized for time efficiency with minimal memory allocation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class QuotedPrintableDecoder {

	/**
	 * Decodes Quoted-Printable encoded bytes from the input buffer into the output buffer.
	 * Handles soft line breaks and hex escape sequences as per RFC 2045.
	 *
	 * @param src the source buffer containing Quoted-Printable encoded data (position/limit define the range)
	 * @param dst the destination buffer to write decoded bytes to
	 * @param max maximum bytes to write to destination buffer
	 * @return DecodeResult containing decoded bytes and consumed input bytes
	 */
	static DecodeResult decode(ByteBuffer src, ByteBuffer dst, int max) {
		int startPos = src.position();
		int outputBytes = 0;

		while (src.hasRemaining() && outputBytes < max && dst.hasRemaining()) {
			byte b = src.get();
			if (b == '=') {
				// Escape sequence or soft line break
				if (src.remaining() >= 2) {
					int savedPos = src.position();  // Save position for potential backtrack
					byte hex1 = src.get();
					byte hex2 = src.get();

					// Check for hex escape sequence
					int val1 = hexCharToValue(hex1);
					int val2 = hexCharToValue(hex2);
					if (val1 >= 0 && val2 >= 0) {
						// Valid hex escape sequence =XX
						if (outputBytes < max && dst.hasRemaining()) {
							dst.put((byte) ((val1 << 4) | val2));
							outputBytes++;
							continue;
						} else {
							// Output buffer full - backtrack
							src.position(savedPos - 1);  // Back to before the '='
							break;
						}
					}

					// Check for soft line break (= followed by CRLF or LF)
					if (hex1 == '\r' && hex2 == '\n') {
						// =CRLF - soft line break, skip
						continue;
					} else if (hex1 == '\n') {
						// =LF - soft line break, skip
						src.position(savedPos + 1); // Only consumed hex1 (which is '\n')
						continue;
					}

					// Not a valid escape sequence - backtrack and treat = as literal
					src.position(savedPos);
				}

				// = at end of input or invalid escape sequence - treat = as literal
				if (outputBytes < max && dst.hasRemaining()) {
					dst.put(b);
					outputBytes++;
				} else {
					// Output buffer full - backtrack
					src.position(src.position() - 1);
					break;
				}
			} else {
				// All other characters - preserve as-is
				if (outputBytes < max && dst.hasRemaining()) {
					dst.put(b);
					outputBytes++;
				} else {
					// Output buffer full - backtrack
					src.position(src.position() - 1);
					break;
				}
			}
		}

		int consumedBytes = src.position() - startPos;
		return new DecodeResult(outputBytes, consumedBytes);
	}

	/**
	 * Estimates the maximum decoded size for a given input size.
	 * Used for buffer allocation sizing.
	 */
	static int estimateDecodedSize(int encodedSize) {
		// Quoted-Printable can only reduce size (=XX becomes 1 byte)
		// In worst case, it stays the same size
		return encodedSize;
	}

	/**
	 * Converts a hex character to its numeric value.
	 * @param c the hex character
	 * @return the numeric value (0-15), or -1 if not a valid hex character
	 */
	private static int hexCharToValue(byte c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		} else if (c >= 'A' && c <= 'F') {
			return c - 'A' + 10;
		} else if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		return -1;
	}

}

