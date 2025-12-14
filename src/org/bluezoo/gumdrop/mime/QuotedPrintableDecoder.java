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
 *
 * <p>Performance optimizations:
 * <ul>
 *   <li>Lookup table for O(1) hex character decoding</li>
 *   <li>Array-backed buffer fast path for direct memory access</li>
 *   <li>Minimized position manipulations and backtracking</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class QuotedPrintableDecoder {

	// Hex decoding lookup table: -1 for invalid, 0-15 for valid hex digits
	private static final byte[] HEX_DECODE = new byte[256];

	static {
		// Initialize with -1 (invalid)
		for (int i = 0; i < 256; i++) {
			HEX_DECODE[i] = -1;
		}
		// Set valid hex digits
		for (int i = 0; i < 10; i++) {
			HEX_DECODE['0' + i] = (byte) i;
		}
		for (int i = 0; i < 6; i++) {
			HEX_DECODE['A' + i] = (byte) (10 + i);
			HEX_DECODE['a' + i] = (byte) (10 + i);
		}
	}

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
		return decode(src, dst, max, false);
	}

	/**
	 * Decodes Quoted-Printable encoded bytes from the input buffer into the output buffer.
	 * Handles soft line breaks and hex escape sequences as per RFC 2045.
	 * When endOfStream is false, incomplete sequences at the end (like trailing '=')
	 * are left unconsumed for the next call. When true, they are treated as literals.
	 *
	 * @param src the source buffer containing Quoted-Printable encoded data (position/limit define the range)
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

		while (srcPos < srcLimit && dstPos < dstLimit) {
			byte b = srcArray[srcPos];

			if (b != '=') {
				// Fast path: non-escape character (most common case)
				dstArray[dstPos++] = b;
				srcPos++;
				continue;
			}

			// Handle '=' escape sequence
			int remaining = srcLimit - srcPos - 1;

			if (remaining >= 2) {
				byte hex1 = srcArray[srcPos + 1];
				byte hex2 = srcArray[srcPos + 2];

				// Check for hex escape using lookup table
				int val1 = HEX_DECODE[hex1 & 0xFF];
				int val2 = HEX_DECODE[hex2 & 0xFF];

				if (val1 >= 0 && val2 >= 0) {
					// Valid hex escape =XX
					dstArray[dstPos++] = (byte) ((val1 << 4) | val2);
					srcPos += 3;
					continue;
				}

				// Check for soft line break
				if (hex1 == '\r' && hex2 == '\n') {
					// =CRLF - skip
					srcPos += 3;
					continue;
				} else if (hex1 == '\n') {
					// =LF - skip
					srcPos += 2;
					continue;
				}

				// Invalid escape - treat = as literal
				dstArray[dstPos++] = b;
				srcPos++;
			} else if (remaining == 1) {
				byte next = srcArray[srcPos + 1];

				if (next == '\n') {
					// =LF soft break
					srcPos += 2;
					continue;
				} else if (next == '\r') {
					if (endOfStream) {
						dstArray[dstPos++] = b;
						srcPos++;
					} else {
						// Might be =CRLF, wait for more data
						break;
					}
				} else {
					if (endOfStream) {
						dstArray[dstPos++] = b;
						srcPos++;
					} else {
						// Could be =XX, wait for more data
						break;
					}
				}
			} else {
				// No bytes after '='
				if (endOfStream) {
					dstArray[dstPos++] = b;
					srcPos++;
				} else {
					break;
				}
			}
		}

		// Update buffer positions
		int outputBytes = dstPos - startDstPos;
		int consumedBytes = srcPos - startSrcPos;
		src.position(srcPos - srcOffset);
		dst.position(dstPos - dstOffset);

		return new DecodeResult(outputBytes, consumedBytes);
	}

	/**
	 * Standard ByteBuffer-based decode for non-array-backed buffers.
	 */
	private static DecodeResult decodeByteBuffer(ByteBuffer src, ByteBuffer dst, int max, boolean endOfStream) {
		int startPos = src.position();
		int outputBytes = 0;

		while (src.hasRemaining() && outputBytes < max && dst.hasRemaining()) {
			byte b = src.get();
			if (b == '=') {
				// Escape sequence or soft line break
				if (src.remaining() >= 2) {
					int savedPos = src.position();
					byte hex1 = src.get();
					byte hex2 = src.get();

					// Check for hex escape sequence using lookup table
					int val1 = HEX_DECODE[hex1 & 0xFF];
					int val2 = HEX_DECODE[hex2 & 0xFF];
					if (val1 >= 0 && val2 >= 0) {
						if (outputBytes < max && dst.hasRemaining()) {
							dst.put((byte) ((val1 << 4) | val2));
							outputBytes++;
							continue;
						} else {
							src.position(savedPos - 1);
							break;
						}
					}

					// Check for soft line break
					if (hex1 == '\r' && hex2 == '\n') {
						continue;
					} else if (hex1 == '\n') {
						src.position(savedPos + 1);
						continue;
					}

					// Invalid escape - treat = as literal
					src.position(savedPos);
					if (outputBytes < max && dst.hasRemaining()) {
						dst.put(b);
						outputBytes++;
					} else {
						src.position(src.position() - 1);
						break;
					}
				} else if (src.remaining() == 1) {
					byte next = src.get();
					if (next == '\n') {
						continue;
					} else if (next == '\r') {
						if (endOfStream) {
							src.position(src.position() - 1);
							if (outputBytes < max && dst.hasRemaining()) {
								dst.put(b);
								outputBytes++;
							} else {
								src.position(src.position() - 1);
								break;
							}
						} else {
							src.position(src.position() - 2);
							break;
						}
					} else {
						if (endOfStream) {
							src.position(src.position() - 1);
							if (outputBytes < max && dst.hasRemaining()) {
								dst.put(b);
								outputBytes++;
							} else {
								src.position(src.position() - 1);
								break;
							}
						} else {
							src.position(src.position() - 2);
							break;
						}
					}
				} else {
					if (endOfStream) {
						if (outputBytes < max && dst.hasRemaining()) {
							dst.put(b);
							outputBytes++;
						} else {
							src.position(src.position() - 1);
							break;
						}
					} else {
						src.position(src.position() - 1);
						break;
					}
				}
			} else {
				if (outputBytes < max && dst.hasRemaining()) {
					dst.put(b);
					outputBytes++;
				} else {
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
	 * Converts a hex character to its numeric value using lookup table.
	 * @param c the hex character
	 * @return the numeric value (0-15), or -1 if not a valid hex character
	 */
	private static int hexCharToValue(byte c) {
		return HEX_DECODE[c & 0xFF];
	}

}

