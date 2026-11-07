/*
 * UuScanner.java
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

/**
 * Finds uuencoded files inline in a stream of text, as they appear in
 * Usenet articles and old mail, and decodes them as they arrive. The text
 * around a file is passed through unchanged; the file is decoded with a
 * {@link UuDecoder}.
 *
 * <p>A file starts at a line {@code begin <octal mode> <name>} and ends at
 * the {@code end} line. Lines that merely resemble a begin line are text.
 * Between calls the scanner holds at most the start of a begin line (under
 * 16 bytes) and the decoder's few fields, so memory use is constant.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class UuScanner {

	/**
	 * Receives the text and the files found in it. The buffers passed to
	 * the handler are valid only during the call.
	 */
	public interface Handler {

		/**
		 * Text outside any file, in order, in pieces of any size.
		 *
		 * @param data the text, line endings as received
		 */
		void text(ByteBuffer data);

		/**
		 * A file starts.
		 *
		 * @param mode the Unix permission bits from the begin line
		 * @param name the file name from the begin line
		 */
		void begin(int mode, String name);

		/**
		 * Decoded bytes of the current file, in pieces of any size.
		 *
		 * @param data the decoded bytes
		 */
		void content(ByteBuffer data);

		/**
		 * The file ends.
		 *
		 * @param complete false if the input ended before the end line
		 */
		void end(boolean complete);
	}

	private static final byte[] BEGIN = { 'b', 'e', 'g', 'i', 'n', ' ' };
	private static final int OUT_SIZE = 4096;

	private static final int TEXT = 0;     // inside a text line
	private static final int BOL = 1;      // at the start of a line, matching a begin line
	private static final int BLOCK = 2;    // inside a file

	private final Handler handler;
	private final UuDecoder decoder = new UuDecoder();
	private final byte[] hold = new byte[16];
	private int holdLen;
	private int mode;
	private int digits;
	private int state = BOL;
	private boolean begun;
	private ByteBuffer out;

	/**
	 * Creates a scanner.
	 *
	 * @param handler the receiver of text and files
	 */
	public UuScanner(Handler handler) {
		this.handler = handler;
	}

	/**
	 * Scans the next piece of input. The input may be split anywhere, even
	 * in the middle of a begin line; all of {@code src} is consumed.
	 *
	 * @param src the input
	 */
	public void receive(ByteBuffer src) {
		while (src.hasRemaining()) {
			if (state == BLOCK) {
				block(src);
			} else if (state == TEXT) {
				text(src);
			} else {
				boundary(src);
			}
		}
	}

	/**
	 * Ends the input. A file still open is ended with
	 * {@link Handler#end(boolean) end(false)}, and text held back because
	 * it might have been the start of a begin line is passed on.
	 */
	public void finish() {
		if (state == BLOCK) {
			ByteBuffer room = outBuffer();
			decoder.finish(room);
			flush();
			if (decoder.isDone()) {
				handler.end(true);
			} else {
				handler.end(false);
			}
		} else if (holdLen > 0) {
			handler.text(ByteBuffer.wrap(hold, 0, holdLen));
		}
		holdLen = 0;
		state = BOL;
	}

	/** Passes text through to the end of its line. */
	private void text(ByteBuffer src) {
		int start = src.position();
		int limit = src.limit();
		int p = start;
		while (p < limit) {
			if (src.get(p++) == '\n') {
				state = BOL;
				break;
			}
		}
		ByteBuffer piece = src.duplicate();
		piece.limit(p);
		handler.text(piece);
		src.position(p);
	}

	/** At the start of a line: reads as much of a begin line as can be told apart from text. */
	private void boundary(ByteBuffer src) {
		while (src.hasRemaining()) {
			byte c = src.get(src.position());
			if (holdLen < BEGIN.length) {
				if (c == BEGIN[holdLen]) {
					hold[holdLen++] = c;
					src.get();
					continue;
				}
				notBegin();
				return; // c is read as text, or as a line start
			}
			if (c >= '0' && c <= '7' && digits < 6) {
				mode = (mode << 3) | (c - '0');
				digits++;
				hold[holdLen++] = c;
				src.get();
			} else if (c == ' ' && digits > 0) {
				src.get();
				holdLen = 0;
				decoder.startAtName(mode);
				begun = false;
				state = BLOCK;
				return;
			} else {
				notBegin();
				return;
			}
		}
	}

	/** What was held is not a begin line: it is text. */
	private void notBegin() {
		if (holdLen > 0) {
			handler.text(ByteBuffer.wrap(hold, 0, holdLen));
		}
		holdLen = 0;
		mode = 0;
		digits = 0;
		state = TEXT;
	}

	/** Decodes file data until the end line, then returns to text. */
	private void block(ByteBuffer src) {
		ByteBuffer o = outBuffer();
		while (src.hasRemaining() && !decoder.isDone()) {
			int before = src.position();
			decoder.decode(src, o);
			if (decoder.hasHeader() && !begun) {
				begun = true;
				handler.begin(decoder.getMode(), decoder.getName());
			}
			flush();
			if (src.position() == before && !decoder.isDone()) {
				break;
			}
		}
		if (decoder.isDone()) {
			handler.end(true);
			decoder.reset();
			mode = 0;
			digits = 0;
			state = BOL;
		}
	}

	private ByteBuffer outBuffer() {
		if (out == null) {
			out = ByteBuffer.allocate(OUT_SIZE);
		}
		return out;
	}

	private void flush() {
		if (out != null && out.position() > 0) {
			out.flip();
			if (!begun && decoder.hasHeader()) {
				begun = true;
				handler.begin(decoder.getMode(), decoder.getName());
			}
			handler.content(out);
			out.clear();
		}
	}

}
