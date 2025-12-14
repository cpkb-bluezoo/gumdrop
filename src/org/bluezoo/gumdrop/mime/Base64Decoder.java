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
 *
 * <p>Performance optimizations:
 * <ul>
 *   <li>Lookup table for O(1) character validation and decoding</li>
 *   <li>Array-backed buffer fast path for direct memory access</li>
 *   <li>Batch processing of 4-character quantums</li>
 *   <li>Minimized branching in hot loops</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Base64Decoder {

	// BASE64 decoding table - maps ASCII values to 6-bit values
	// Values: 0-63 for valid chars, -1 for invalid, -2 for whitespace (skip)
	private static final byte[] DECODE_TABLE = new byte[256];

	// Special marker values
	private static final byte INVALID = -1;
	private static final byte WHITESPACE = -2;

	static {
		// Initialize decode table with INVALID
		for (int i = 0; i < 256; i++) {
			DECODE_TABLE[i] = INVALID;
		}

		// Mark whitespace characters as skippable
		DECODE_TABLE[' '] = WHITESPACE;
		DECODE_TABLE['\t'] = WHITESPACE;
		DECODE_TABLE['\r'] = WHITESPACE;
		DECODE_TABLE['\n'] = WHITESPACE;

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
		// Note: '=' is left as INVALID - it's handled specially as padding
	}

	/**
	 * Decodes BASE64 encoded bytes from the input buffer into the output buffer.
	 * Handles incomplete sequences by leaving unconsumed bytes in src for the next call.
	 * Ignores whitespace and invalid characters as per RFC 2045.
	 *
	 * @param src the source buffer containing BASE64 encoded data (position/limit define the range)
	 * @param dst the destination buffer to write decoded bytes to
	 * @param max maximum bytes to write to destination buffer
	 * @return DecodeResult containing decoded bytes and consumed input bytes
	 */
	static DecodeResult decode(ByteBuffer src, ByteBuffer dst, int max) {
		return decode(src, dst, max, false);
	}

	/**
	 * Decodes BASE64 encoded bytes from the input buffer into the output buffer.
	 * Handles incomplete sequences by leaving unconsumed bytes in src for the next call,
	 * unless endOfStream is true in which case remaining bits are flushed.
	 * Ignores whitespace and invalid characters as per RFC 2045.
	 *
	 * @param src the source buffer containing BASE64 encoded data (position/limit define the range)
	 * @param dst the destination buffer to write decoded bytes to
	 * @param max maximum bytes to write to destination buffer
	 * @param endOfStream true if this is the final chunk of data (no more data coming)
	 * @return DecodeResult containing decoded bytes and consumed input bytes
	 */
	static DecodeResult decode(ByteBuffer src, ByteBuffer dst, int max, boolean endOfStream) {
		// Use array-backed fast path if available
		if (src.hasArray() && dst.hasArray()) {
			return decodeArrayBacked(src, dst, max, endOfStream);
		}
		return decodeByteBuffer(src, dst, max, endOfStream);
	}

	/**
	 * Optimized decode using direct array access.
	 */
	private static DecodeResult decodeArrayBacked(ByteBuffer src, ByteBuffer dst, int max, boolean endOfStream) {
		byte[] srcArray = src.array();
		byte[] dstArray = dst.array();
		int srcOffset = src.arrayOffset();
		int dstOffset = dst.arrayOffset();

		int srcPos = src.position() + srcOffset;
		int srcLimit = src.limit() + srcOffset;
		int dstPos = dst.position() + dstOffset;
		int dstLimit = Math.min(dst.limit() + dstOffset, dstPos + max);

		int startSrcPos = srcPos;
		int startDstPos = dstPos;
		int lastValidSrcPos = srcPos;

		int quantum = 0;
		int quantumBits = 0;
		boolean sawPadding = false;

		while (srcPos < srcLimit) {
			int b = srcArray[srcPos++] & 0xFF;
			int val = DECODE_TABLE[b];

			if (val >= 0) {
				// Valid BASE64 character
				quantum = (quantum << 6) | val;
				quantumBits += 6;

				if (quantumBits >= 24) {
					if (dstPos + 3 <= dstLimit) {
						dstArray[dstPos++] = (byte) (quantum >> 16);
						dstArray[dstPos++] = (byte) (quantum >> 8);
						dstArray[dstPos++] = (byte) quantum;
						lastValidSrcPos = srcPos;
						quantum = 0;
						quantumBits = 0;
					} else {
						srcPos = lastValidSrcPos;
						break;
					}
				}
			} else if (val == WHITESPACE) {
				// Skip whitespace
				continue;
			} else if (b == '=') {
				sawPadding = true;
				break;
			}
			// Other invalid characters are silently skipped per RFC 2045
		}

		// Handle remaining bits
		if ((sawPadding || endOfStream) && quantumBits >= 8 && dstPos < dstLimit) {
			dstArray[dstPos++] = (byte) (quantum >> (quantumBits - 8));
			if (quantumBits >= 16 && dstPos < dstLimit) {
				dstArray[dstPos++] = (byte) (quantum >> (quantumBits - 16));
			}
			lastValidSrcPos = srcPos;
		}

		// Update buffer positions
		int outputBytes = dstPos - startDstPos;
		int consumedBytes = lastValidSrcPos - startSrcPos;
		src.position(lastValidSrcPos - srcOffset);
		dst.position(dstPos - dstOffset);

		return new DecodeResult(outputBytes, consumedBytes);
	}

	/**
	 * Standard ByteBuffer-based decode for non-array-backed buffers.
	 */
	private static DecodeResult decodeByteBuffer(ByteBuffer src, ByteBuffer dst, int max, boolean endOfStream) {
		int startPos = src.position();
		int outputBytes = 0;
		int quantum = 0;
		int quantumBits = 0;
		int lastValidPos = startPos;
		boolean sawPadding = false;

		while (src.hasRemaining()) {
			int b = src.get() & 0xFF;
			int val = DECODE_TABLE[b];

			if (val >= 0) {
				quantum = (quantum << 6) | val;
				quantumBits += 6;

				if (quantumBits >= 24) {
					if (outputBytes + 3 <= max && dst.remaining() >= 3) {
						dst.put((byte) (quantum >> 16));
						dst.put((byte) (quantum >> 8));
						dst.put((byte) quantum);
						outputBytes += 3;
						lastValidPos = src.position();
						quantum = 0;
						quantumBits = 0;
					} else {
						src.position(lastValidPos);
						break;
					}
				}
			} else if (val == WHITESPACE) {
				continue;
			} else if (b == '=') {
				sawPadding = true;
				break;
			}
		}

		if ((sawPadding || endOfStream) && quantumBits >= 8 && outputBytes < max && dst.hasRemaining()) {
			dst.put((byte) (quantum >> (quantumBits - 8)));
			outputBytes++;
			if (quantumBits >= 16 && outputBytes < max && dst.hasRemaining()) {
				dst.put((byte) (quantum >> (quantumBits - 16)));
				outputBytes++;
			}
			lastValidPos = src.position();
		}

		src.position(lastValidPos);
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
