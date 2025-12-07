/*
 * Base64Decoder.java
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
 * High-performance BASE64 decoder for Content-Transfer-Encoding processing.
 * Optimized for time efficiency with minimal memory allocation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Base64Decoder {

	// BASE64 decoding table - maps ASCII values to 6-bit values, -1 for invalid
	private static final byte[] DECODE_TABLE = new byte[128];

	static {
		// Initialize decode table with -1 (invalid)
		for (int i = 0; i < 128; i++) {
			DECODE_TABLE[i] = -1;
		}

		// Set valid BASE64 characters
		for (int i = 0; i < 26; i++) {
			DECODE_TABLE['A' + i] = (byte) i;
			DECODE_TABLE['a' + i] = (byte) (i + 26);
		}
		for (int i = 0; i < 10; i++) {
			DECODE_TABLE['0' + i] = (byte) (i + 52);
		}
		DECODE_TABLE['+'] = 62;
		DECODE_TABLE['/'] = 63;
		// Note: '=' is left as -1 (invalid) - it's handled specially as padding
	}

	/**
	 * Decodes BASE64 encoded bytes from the input buffer into the output buffer.
	 * Handles incomplete sequences by maintaining state across calls.
	 * Ignores whitespace and invalid characters as per RFC 2045.
	 *
	 * @param src the source buffer containing BASE64 encoded data (position/limit define the range)
	 * @param dst the destination buffer to write decoded bytes to
	 * @param max maximum bytes to write to destination buffer
	 * @return DecodeResult containing decoded bytes and consumed input bytes
	 */
	static DecodeResult decode(ByteBuffer src, ByteBuffer dst, int max) {
		int startPos = src.position();
		int outputBytes = 0;
		int quantum = 0;  // accumulated 24-bit quantum
		int quantumBits = 0;  // number of valid bits in quantum
		int lastValidPos = startPos; // track last position where we had a complete quantum
		boolean sawPadding = false;

		while (src.hasRemaining()) {
			byte b = src.get();
			// Skip whitespace and control characters
			if (b <= 32) {
				continue;
			}
			// Check for padding character first
			if (b == '=') {
				// Padding - end of data
				sawPadding = true;
				break;
			}
			// Check for valid BASE64 characters
			if (b >= 128 || DECODE_TABLE[b] == -1) {
				// Invalid character - skip
				continue;
			}
			// Add 6 bits to quantum
			quantum = (quantum << 6) | DECODE_TABLE[b];
			quantumBits += 6;
			// When we have 24 bits (4 BASE64 chars), output 3 bytes
			if (quantumBits >= 24) {
				if (outputBytes + 3 <= max && dst.remaining() >= 3) {
					dst.put((byte) (quantum >> 16));
					dst.put((byte) (quantum >> 8));
					dst.put((byte) quantum);
					outputBytes += 3;
					lastValidPos = src.position(); // Mark this as a safe stopping point
					quantum = 0;
					quantumBits = 0;
				} else {
					// Output buffer full - rewind to last complete quantum
					src.position(lastValidPos);
					break;
				}
			}
		}

		// Handle remaining bits if we've consumed all input OR saw padding
		if ((sawPadding || !src.hasRemaining()) && quantumBits >= 8 && outputBytes < max && dst.hasRemaining()) {
			int shift = quantumBits - 8;
			dst.put((byte) (quantum >> shift));
			outputBytes++;

			if (quantumBits >= 16 && outputBytes < max && dst.hasRemaining()) {
				shift = quantumBits - 16;
				dst.put((byte) (quantum >> shift));
				outputBytes++;
			}
			lastValidPos = src.position();
		}

		int consumedBytes = lastValidPos - startPos;
		return new DecodeResult(outputBytes, consumedBytes);
	}

	/**
	 * Estimates the maximum decoded size for a given input size.
	 * Used for buffer allocation sizing.
	 */
	static int estimateDecodedSize(int encodedSize) {
		// BASE64 encoding uses 4 chars for every 3 bytes
		// Add some padding for safety
		return (encodedSize * 3) / 4 + 4;
	}

}
