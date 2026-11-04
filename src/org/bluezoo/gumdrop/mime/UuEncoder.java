/*
 * UuEncoder.java
 * Copyright (C) 2025, 2026 Chris Burdess
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
import java.nio.charset.StandardCharsets;

/**
 * Streaming uuencode encoder. Input is pushed in chunks of any size and
 * output is produced a line at a time into the caller's buffer; at most
 * one partial line of input (under 45 bytes) is held between calls, so
 * memory use is constant whatever the size of the file.
 *
 * <p>The output is {@code begin <mode> <name>}, lines of up to 45 data
 * bytes (a length character and 60 characters), a zero length line
 * written as a backtick, and {@code end}. All lines end with LF.
 *
 * <p>Call {@link #encode} repeatedly, draining dst between calls, until
 * src is empty; after the last input call with {@code endOfStream} true
 * until it returns true.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class UuEncoder {

	/** Data bytes in a full line. */
	static final int LINE_BYTES = 45;

	/** Longest line written: length character, 60 characters and LF. */
	private static final int LINE_CHARS = 62;

	private static final byte[] TRAILER = { '`', '\n', 'e', 'n', 'd', '\n' };

	private final byte[] header;
	private int headerPos;
	private final byte[] line = new byte[LINE_BYTES];
	private int lineLen;
	private int trailerPos;
	private boolean finished;
	private byte[] scratch;

	/**
	 * Creates an encoder.
	 * @param name the file name for the begin line; it must not contain
	 * line ending or other control characters
	 * @param mode the file mode, for example {@code 0644}
	 */
	UuEncoder(String name, int mode) {
		for (int i = 0; i < name.length(); i++) {
			if (name.charAt(i) < ' ' || name.charAt(i) == 0x7f) {
				throw new IllegalArgumentException("control character in uuencode file name");
			}
		}
		String h = "begin " + Integer.toOctalString(mode & 07777) + " " + name + "\n";
		header = h.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Encodes input to output. Returns when src is empty (any input
	 * under a line is held), or when dst has too little room for the
	 * next line, in which case src may have input left.
	 *
	 * @param src the input
	 * @param dst the output
	 * @param endOfStream true when src is the last input
	 * @return true when the end line has been written
	 */
	boolean encode(ByteBuffer src, ByteBuffer dst, boolean endOfStream) {
		if (finished) {
			return true;
		}
		byte[] out;
		int op;
		int oo = 0;
		boolean direct = dst.hasArray();
		if (direct) {
			out = dst.array();
			oo = dst.arrayOffset();
			op = oo + dst.position();
		} else {
			if (scratch == null) {
				scratch = new byte[LINE_CHARS];
			}
			out = scratch;
			op = 0;
		}
		int limit = direct ? oo + dst.limit() : Integer.MAX_VALUE;

		// the begin line
		while (headerPos < header.length) {
			if (!direct) {
				dst.put(header[headerPos++]);
				if (!dst.hasRemaining() && headerPos < header.length) {
					return false;
				}
			} else if (op < limit) {
				out[op++] = header[headerPos++];
			} else {
				dst.position(op - oo);
				return false;
			}
		}

		boolean progress = true;
		while (progress) {
			progress = false;
			int room = direct ? limit - op : dst.remaining();
			if (room < LINE_CHARS) {
				break;
			}
			if (lineLen == LINE_BYTES) {
				op = writeLine(line, 0, LINE_BYTES, out, op, direct, dst);
				lineLen = 0;
				progress = true;
			} else if (lineLen > 0 && src.hasRemaining()) {
				int take = Math.min(LINE_BYTES - lineLen, src.remaining());
				src.get(line, lineLen, take);
				lineLen += take;
				progress = true;
			} else if (src.remaining() >= LINE_BYTES) {
				if (src.hasArray()) {
					op = writeLine(src.array(), src.arrayOffset() + src.position(),
							LINE_BYTES, out, op, direct, dst);
					src.position(src.position() + LINE_BYTES);
				} else {
					src.get(line, 0, LINE_BYTES);
					op = writeLine(line, 0, LINE_BYTES, out, op, direct, dst);
				}
				progress = true;
			} else if (lineLen == 0 && src.hasRemaining()) {
				lineLen = src.remaining();
				src.get(line, 0, lineLen);
				progress = true;
			}
			if (progress && !direct) {
				op = 0;
			}
		}

		if (endOfStream && !src.hasRemaining()) {
			int room = direct ? limit - op : dst.remaining();
			if (lineLen > 0 && lineLen < LINE_BYTES && room >= LINE_CHARS) {
				op = writeLine(line, 0, lineLen, out, op, direct, dst);
				lineLen = 0;
				room = direct ? limit - op : dst.remaining();
			}
			if (lineLen == 0) {
				while (trailerPos < TRAILER.length && room > 0) {
					if (direct) {
						out[op++] = TRAILER[trailerPos++];
					} else {
						dst.put(TRAILER[trailerPos++]);
					}
					room--;
				}
				finished = trailerPos == TRAILER.length;
			}
		}
		if (direct) {
			dst.position(op - oo);
		}
		return finished;
	}

	/** Encodes len (1 to 45) bytes as one line, returning the new output position. */
	private int writeLine(byte[] a, int off, int len, byte[] out, int op, boolean direct, ByteBuffer dst) {
		int start = direct ? op : 0;
		int p = start;
		out[p++] = chr(len);
		int end = off + len;
		int i = off;
		while (i + 3 <= end) {
			int b0 = a[i] & 0xff;
			int b1 = a[i + 1] & 0xff;
			int b2 = a[i + 2] & 0xff;
			out[p] = chr(b0 >> 2);
			out[p + 1] = chr(((b0 << 4) | (b1 >> 4)) & 63);
			out[p + 2] = chr(((b1 << 2) | (b2 >> 6)) & 63);
			out[p + 3] = chr(b2 & 63);
			p += 4;
			i += 3;
		}
		if (i < end) {
			int b0 = a[i] & 0xff;
			int b1 = i + 1 < end ? a[i + 1] & 0xff : 0;
			out[p] = chr(b0 >> 2);
			out[p + 1] = chr(((b0 << 4) | (b1 >> 4)) & 63);
			out[p + 2] = chr((b1 << 2) & 63);
			out[p + 3] = '`';
			p += 4;
		}
		out[p++] = '\n';
		if (direct) {
			return p;
		}
		dst.put(out, 0, p);
		return 0;
	}

	private static byte chr(int v) {
		return v == 0 ? (byte) '`' : (byte) (v + 32);
	}

}
