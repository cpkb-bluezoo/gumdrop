/*
 * UuDecoder.java
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
 * Streaming uuencode decoder for the x-uuencode content transfer encoding
 * and for uuencoded files in Usenet articles.
 *
 * <p>Input is pushed in chunks of any size. All of a chunk is consumed
 * unless the output buffer is full or the {@code end} line has been
 * seen, so unlike {@link Base64Decoder} no input is left over for the
 * caller to carry: the state between calls (the position in the current
 * line, a partial group of characters, the {@code begin} line's mode
 * and name) is held in a few fields, and memory use is constant whatever
 * the size of the file.
 *
 * <p>The decoder is lenient. Lines before {@code begin} are skipped; CR
 * is ignored; short lines whose trailing spaces were stripped are
 * completed with zeros; characters beyond a line's length (padding, or a
 * checksum some encoders add) are ignored.
 *
 * <p>Call {@link #decode} repeatedly with the output buffer drained
 * between calls until {@link #isDone()}; when done, the input is left
 * positioned after the {@code end} line.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class UuDecoder {

	/** Longest name kept from the begin line; the rest is dropped. */
	static final int MAX_NAME_LENGTH = 255;

	private static final byte[] BEGIN = { 'b', 'e', 'g', 'i', 'n', ' ' };
	private static final byte[] END = { 'e', 'n', 'd' };

	private static final int S_BEGIN = 0;     // matching "begin "
	private static final int S_SKIP = 1;      // skipping a line that is not begin
	private static final int S_MODE = 2;      // octal mode digits
	private static final int S_NAME = 3;      // file name to end of line
	private static final int S_DATA = 4;      // data lines
	private static final int S_MAYBE_END = 5; // line start 'e': "end", or data?
	private static final int S_END_WAIT = 6;  // after the zero length line
	private static final int S_DONE = 7;

	private int state = S_BEGIN;
	private int matched;       // S_BEGIN, S_MAYBE_END, S_END_WAIT: characters matched
	private int mode;
	private int modeDigits;
	private final byte[] name = new byte[MAX_NAME_LENGTH];
	private int nameLen;
	private boolean header;

	private int col;           // S_DATA: characters of the line consumed
	private int remaining;     // S_DATA: bytes still owed by this line
	private boolean zeroLine;  // S_DATA: this is the zero length line
	private int n;             // characters in the current group
	private int acc;           // bits of the current group

	// scratch for non-array buffers, allocated on first use
	private byte[] inScratch;
	private byte[] outScratch;

	// cursors of the byte array loop
	private int rp;
	private int wp;

	/** Creates a decoder that starts at a begin line. */
	UuDecoder() {
	}

	/**
	 * Creates a decoder that starts after "begin " and a mode, as found
	 * by a scanner that has already read those.
	 */
	UuDecoder(int mode) {
		startAtName(mode);
	}

	/** Restarts the decoder at the name of a begin line whose mode is known. */
	void startAtName(int mode) {
		reset();
		this.mode = mode;
		state = S_NAME;
	}

	/** Returns the decoder to its initial state, ready for another file. */
	void reset() {
		state = S_BEGIN;
		matched = 0;
		mode = 0;
		modeDigits = 0;
		nameLen = 0;
		header = false;
		col = 0;
		remaining = 0;
		zeroLine = false;
		n = 0;
		acc = 0;
	}

	/** True once the begin line has been read in full. */
	boolean hasHeader() {
		return header;
	}

	/** The file mode from the begin line. */
	int getMode() {
		return mode;
	}

	/** The file name from the begin line. */
	String getName() {
		return new String(name, 0, nameLen, StandardCharsets.UTF_8);
	}

	/** True once the end line has been read. */
	boolean isDone() {
		return state == S_DONE;
	}

	/**
	 * Decodes input to output. Returns when src is empty, when dst has
	 * fewer than three bytes free while decoding data, or when the end
	 * line has been read. src is advanced past what was consumed, dst
	 * past what was written.
	 */
	void decode(ByteBuffer src, ByteBuffer dst) {
		if (src.hasArray() && dst.hasArray()) {
			int so = src.arrayOffset();
			int dO = dst.arrayOffset();
			run(src.array(), so + src.position(), so + src.limit(),
					dst.array(), dO + dst.position(), dO + dst.limit());
			src.position(rp - so);
			dst.position(wp - dO);
			return;
		}
		if (inScratch == null) {
			inScratch = new byte[1024];
			outScratch = new byte[768];
		}
		while (src.hasRemaining() && state != S_DONE) {
			int room = Math.min(dst.remaining(), outScratch.length);
			if (room < 3 && (state == S_DATA || state == S_MAYBE_END)) {
				return;
			}
			int take = Math.min(src.remaining(), inScratch.length);
			int start = src.position();
			src.get(inScratch, 0, take);
			run(inScratch, 0, take, outScratch, 0, Math.max(room, 0));
			src.position(start + rp);
			if (wp > 0) {
				dst.put(outScratch, 0, wp);
			}
			if (rp == 0 && wp == 0) {
				return;
			}
		}
	}

	/**
	 * Ends the input: completes a line that lacks its line feed. Returns
	 * true if the end line was seen, false if the input was cut short.
	 *
	 * @param dst room for up to three bytes per missing group
	 */
	boolean finish(ByteBuffer dst) {
		if (state == S_MAYBE_END && matched == END.length) {
			state = S_DONE; // "end" with no line feed
		} else if (state == S_DATA && col > 0) {
			ByteBuffer eol = ByteBuffer.wrap(new byte[] { '\n' });
			decode(eol, dst);
		}
		return state == S_DONE;
	}

	private void run(byte[] s, int sp, int sl, byte[] d, int dp, int dl) {
		while (sp < sl) {
			switch (state) {
			case S_DATA:
				if (dl - dp < 3) {
					rp = sp;
					wp = dp;
					return;
				}
				// fast path: whole groups from the middle of a line
				if (col > 0 && n == 0) {
					while (remaining >= 3 && sl - sp >= 4 && dl - dp >= 3) {
						int c0 = s[sp];
						int c1 = s[sp + 1];
						int c2 = s[sp + 2];
						int c3 = s[sp + 3];
						if ((c0 < 32) | (c1 < 32) | (c2 < 32) | (c3 < 32)) {
							break; // a line ending or other control character
						}
						c0 = (c0 - 32) & 63;
						c1 = (c1 - 32) & 63;
						c2 = (c2 - 32) & 63;
						c3 = (c3 - 32) & 63;
						d[dp] = (byte) ((c0 << 2) | (c1 >> 4));
						d[dp + 1] = (byte) ((c1 << 4) | (c2 >> 2));
						d[dp + 2] = (byte) ((c2 << 6) | c3);
						dp += 3;
						sp += 4;
						col += 4;
						remaining -= 3;
					}
					if (sp >= sl) {
						break;
					}
				}
				{
					byte c = s[sp++];
					if (col == 0) {
						if (c == '\n' || c == '\r') {
							continue; // blank line
						}
						if (c == 'e') {
							state = S_MAYBE_END;
							matched = 1;
							continue;
						}
						startLine(c);
					} else if (c == '\n') {
						if (remaining > 0) {
							// short line: the missing characters are zeros
							acc <<= 6;
							if (++n == 4) {
								dp = emit(d, dp);
							}
							sp--;
							continue;
						}
						col = 0;
						n = 0;
						acc = 0;
						if (zeroLine) {
							state = S_END_WAIT;
							matched = 0;
						}
					} else if (c != '\r') {
						col++;
						if (remaining > 0) {
							acc = (acc << 6) | ((c - 32) & 63);
							if (++n == 4) {
								dp = emit(d, dp);
							}
						}
					}
				}
				break;
			case S_MAYBE_END:
				if (dl - dp < 3) {
					rp = sp;
					wp = dp;
					return;
				}
				{
					byte c = s[sp++];
					if (c == '\r') {
						break;
					}
					if (matched < END.length && c == END[matched]) {
						matched++;
					} else if (matched == END.length && c == '\n') {
						state = S_DONE;
					} else {
						// it was a data line after all: replay what matched
						state = S_DATA;
						startLine((byte) 'e');
						for (int i = 1; i < matched; i++) {
							col++;
							acc = (acc << 6) | ((END[i] - 32) & 63);
							if (remaining > 0 && ++n == 4) {
								dp = emit(d, dp);
							}
						}
						sp--; // the mismatching character is data (or line end)
					}
				}
				break;
			case S_END_WAIT:
				{
					byte c = s[sp++];
					if (c == '\r') {
						break;
					}
					if (c == '\n') {
						if (matched == END.length) {
							state = S_DONE;
						} else {
							matched = 0;
						}
					} else if (matched >= 0 && matched < END.length && c == END[matched]) {
						matched++;
					} else {
						matched = -1; // some other line; skip to its end
					}
					if (matched < 0 && c == '\n') {
						matched = 0;
					}
				}
				break;
			case S_BEGIN:
				{
					byte c = s[sp++];
					if (matched == 0 && (c == '\n' || c == '\r')) {
						break;
					}
					if (c == BEGIN[matched]) {
						if (++matched == BEGIN.length) {
							state = S_MODE;
							mode = 0;
							modeDigits = 0;
						}
					} else {
						state = c == '\n' ? S_BEGIN : S_SKIP;
						matched = 0;
					}
				}
				break;
			case S_SKIP:
				if (s[sp++] == '\n') {
					state = S_BEGIN;
					matched = 0;
				}
				break;
			case S_MODE:
				{
					byte c = s[sp++];
					if (c >= '0' && c <= '7' && modeDigits < 6) {
						mode = (mode << 3) | (c - '0');
						modeDigits++;
					} else if (c == ' ' && modeDigits > 0) {
						state = S_NAME;
						nameLen = 0;
					} else {
						state = c == '\n' ? S_BEGIN : S_SKIP;
						matched = 0;
					}
				}
				break;
			case S_NAME:
				{
					byte c = s[sp++];
					if (c == '\n') {
						header = true;
						state = S_DATA;
						col = 0;
						n = 0;
						acc = 0;
					} else if (c != '\r' && nameLen < MAX_NAME_LENGTH) {
						name[nameLen++] = c;
					}
				}
				break;
			default: // S_DONE
				rp = sp;
				wp = dp;
				return;
			}
		}
		rp = sp;
		wp = dp;
	}

	/** Starts a data line with its length character. */
	private void startLine(byte c) {
		remaining = (c - 32) & 63;
		zeroLine = remaining == 0;
		col = 1;
		n = 0;
		acc = 0;
	}

	/** Emits the bytes of a complete group of four characters. */
	private int emit(byte[] d, int dp) {
		int k = remaining < 3 ? remaining : 3;
		d[dp] = (byte) (acc >> 16);
		if (k > 1) {
			d[dp + 1] = (byte) (acc >> 8);
			if (k > 2) {
				d[dp + 2] = (byte) acc;
			}
		}
		remaining -= k;
		n = 0;
		acc = 0;
		return dp + k;
	}

}
