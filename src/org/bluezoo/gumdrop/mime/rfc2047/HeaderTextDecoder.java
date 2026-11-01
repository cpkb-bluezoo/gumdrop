/*
 * HeaderTextDecoder.java
 * Copyright (C) 2026 Chris Burdess
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.mime.MimeParseException;

/**
 * Decodes the text of an unstructured header as its unfolded bytes arrive,
 * in any chunking, and hands the decoded text on in pieces.
 *
 * <p>RFC 2047 encoded words are decoded, white space between two adjacent
 * encoded words is dropped, leading and trailing white space is dropped, and
 * raw 8-bit bytes are read as UTF-8 where they form valid sequences and as
 * windows-1252 where they do not (ISO-8859-1 if the decoder is made for
 * SMTPUTF8 messages).
 *
 * <p>The decoder holds only a bounded amount: a possible encoded word (at
 * most {@link #MAX_ENCODED_WORD} bytes, the RFC's 75 with room for sloppy
 * writers), the white space that might sit between two encoded words (at
 * most {@link #MAX_HELD_WHITESPACE} bytes) and an unfinished UTF-8 sequence
 * (at most 3 bytes). A header of any length passes through it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2047">RFC 2047</a>
 */
public final class HeaderTextDecoder {

	/** The longest possible encoded word held while it is recognised. A longer candidate is text. */
	public static final int MAX_ENCODED_WORD = 256;

	/** The most white space held back to see whether an encoded word follows it. More is emitted at once. */
	public static final int MAX_HELD_WHITESPACE = 256;

	/** Text is handed on in pieces of about this many characters. */
	private static final int CHUNK = 1024;

	/** Receives decoded text. */
	public interface Sink {

		/**
		 * Some decoded text. A piece has no particular size.
		 * @param text the text
		 * @exception MimeParseException if the receiver wishes to cancel the parse
		 */
		void text(String text) throws MimeParseException;

	}

	/** windows-1252 for bytes 0x80 to 0xFF; bytes it does not define are passed through. */
	private static final char[] WINDOWS_1252 = new char[256];

	/** Printable ASCII with no special treatment here: not white space, '=' or DEL. */
	private static final boolean[] PLAIN = new boolean[256];

	static {
		Charset cp1252 = Charset.forName("windows-1252");
		for (int b = 0x80; b < 256; b++) {
			char c = new String(new byte[] { (byte) b }, cp1252).charAt(0);
			WINDOWS_1252[b] = (c == '\ufffd') ? (char) b : c;
		}
		for (int b = 0x21; b < 0x7f; b++) {
			PLAIN[b] = b != '=';
		}
	}

	private final boolean smtpUtf8;
	private final byte[] held = new byte[MAX_ENCODED_WORD]; // a possible encoded word, from its '='
	private int heldLen;
	private boolean recognising; // inside a possible encoded word
	private int wordQuestions; // '?' seen after the opening "=?"
	private final byte[] whitespace = new byte[MAX_HELD_WHITESPACE]; // not yet decided: dropped or emitted
	private int whitespaceLen;
	private boolean started; // something other than white space has been emitted or is pending
	private boolean afterWord; // the last thing emitted was an encoded word
	private final byte[] carry = new byte[4]; // the start of a UTF-8 sequence
	private int carryLen;
	private int carryNeed;
	private final StringBuilder out = new StringBuilder(CHUNK * 2);
	private final CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
		.onMalformedInput(CodingErrorAction.REPORT)
		.onUnmappableCharacter(CodingErrorAction.REPORT);

	/**
	 * Creates a decoder.
	 * @param smtpUtf8 if true, 8-bit text that is not UTF-8 is read as
	 * ISO-8859-1 and not as windows-1252
	 */
	public HeaderTextDecoder(boolean smtpUtf8) {
		this.smtpUtf8 = smtpUtf8;
	}

	/** Readies the decoder for another header. */
	public void reset() {
		recognising = false;
		heldLen = 0;
		wordQuestions = 0;
		whitespaceLen = 0;
		started = false;
		afterWord = false;
		carryLen = 0;
		carryNeed = 0;
		out.setLength(0);
	}

	/**
	 * Decodes the bytes from the buffer's position to its limit, handing
	 * decoded text to the sink as it is ready. What might still change (a
	 * possible encoded word, white space) is kept for the next call. The
	 * buffer's position is moved to its limit.
	 * @param bytes the unfolded bytes of the header
	 * @param sink the receiver of the decoded text
	 * @exception MimeParseException if the sink throws it
	 */
	public void feed(ByteBuffer bytes, Sink sink) throws MimeParseException {
		while (bytes.hasRemaining()) {
			byte b = bytes.get();
			if (!recognising && carryLen == 0 && PLAIN[b & 0xff]) {
				// Ordinary printable ASCII is the bulk of any header
				settleWhitespace();
				started = true;
				afterWord = false;
				out.append((char) b);
			} else {
				decodeByte(b);
			}
			if (out.length() >= CHUNK) {
				flush(sink);
			}
		}
		flush(sink);
	}

	/**
	 * Ends the header: what is still held is text (leading and trailing
	 * white space is dropped, so trailing white space is not emitted).
	 * @param sink the receiver of the decoded text
	 * @exception MimeParseException if the sink throws it
	 */
	public void finish(Sink sink) throws MimeParseException {
		// reading the held bytes again may open a new possible word
		while (recognising) {
			failWord();
		}
		flushCarry();
		whitespaceLen = 0;
		flush(sink);
	}

	private void decodeByte(byte b) {
		if (recognising) {
			wordByte(b);
			return;
		}
		int v = b & 0xff;
		if (b == ' ' || b == '\t') {
			flushCarry();
			if (started) {
				if (whitespaceLen >= MAX_HELD_WHITESPACE) {
					settleWhitespace();
					afterWord = false;
				}
				whitespace[whitespaceLen++] = b;
			}
		} else if (b == '=') {
			flushCarry();
			recognising = true;
			held[0] = b;
			heldLen = 1;
			wordQuestions = 0;
		} else if (v >= 0x80) {
			highByte(b);
		} else {
			flushCarry();
			settleWhitespace();
			started = true;
			afterWord = false;
			out.append((char) v);
		}
	}

	/** A byte of a possible encoded word. */
	private void wordByte(byte b) {
		// held is full: it is not an encoded word, whatever follows
		if (heldLen == MAX_ENCODED_WORD) {
			failWord();
			decodeByte(b);
			return;
		}
		held[heldLen++] = b;
		int n = heldLen;
		// "=?" is the start; anything else is just an equals sign
		if (n == 2 && b != '?') {
			failWord();
			return;
		}
		// The end: the '?' that closes the word, then '='. (The text of a
		// Q-encoded word may well start with '='.)
		boolean closing = b == '=' && held[n - 2] == '?' && wordQuestions >= 3;
		if (b == '?' && n > 2) {
			wordQuestions++;
		}
		if (closing) {
			String decoded = decodeHeld();
			if (decoded != null) {
				recognising = false;
				heldLen = 0;
				// white space between two encoded words is not part of the text
				if (afterWord) {
					whitespaceLen = 0;
				} else {
					settleWhitespace();
				}
				started = true;
				afterWord = true;
				out.append(decoded);
			} else {
				failWord();
			}
		}
	}

	/**
	 * held is "=?charset?E?text?=": its decoded text, or null if it is not an
	 * encoded word after all.
	 */
	private String decodeHeld() {
		String inner = new String(held, 2, heldLen - 4, StandardCharsets.ISO_8859_1);
		int q1 = inner.indexOf('?');
		if (q1 <= 0) {
			return null;
		}
		int q2 = inner.indexOf('?', q1 + 1);
		if (q2 != q1 + 2) {
			return null;
		}
		char e = Character.toUpperCase(inner.charAt(q1 + 1));
		if (e != 'B' && e != 'Q') {
			return null;
		}
		return Rfc2047Decoder.decodeWord(inner.substring(0, q1), e, inner.substring(q2 + 1));
	}

	/**
	 * What was held was not an encoded word: its first byte is text, and the
	 * rest is read again, since it may hold the start of one.
	 */
	private void failWord() {
		byte[] again = new byte[heldLen];
		System.arraycopy(held, 0, again, 0, heldLen);
		heldLen = 0;
		recognising = false;
		settleWhitespace();
		started = true;
		afterWord = false;
		out.append((char) (again[0] & 0xff));
		for (int i = 1; i < again.length; i++) {
			decodeByte(again[i]);
		}
	}

	/** Emits the held white space: it is not leading, trailing or between two encoded words. */
	private void settleWhitespace() {
		for (int i = 0; i < whitespaceLen; i++) {
			out.append((char) whitespace[i]);
		}
		whitespaceLen = 0;
	}

	/**
	 * A byte of 0x80 or more outside an encoded word: part of a UTF-8
	 * sequence if it can be, otherwise the fallback charset's character.
	 */
	private void highByte(byte b) {
		int v = b & 0xff;
		if (carryLen == 0) {
			int need;
			if (v >= 0xc2 && v <= 0xdf) {
				need = 2;
			} else if (v >= 0xe0 && v <= 0xef) {
				need = 3;
			} else if (v >= 0xf0 && v <= 0xf4) {
				need = 4;
			} else {
				fallback(b);
				return;
			}
			carry[0] = b;
			carryLen = 1;
			carryNeed = need;
			return;
		}
		if ((v & 0xc0) != 0x80) {
			// not a continuation: what was held is not a sequence
			flushCarry();
			highByte(b);
			return;
		}
		carry[carryLen++] = b;
		if (carryLen == carryNeed) {
			utf8.reset();
			try {
				CharBuffer chars = utf8.decode(ByteBuffer.wrap(carry, 0, carryLen));
				carryLen = 0;
				settleWhitespace();
				started = true;
				afterWord = false;
				out.append(chars);
			} catch (CharacterCodingException e) {
				flushCarry();
			}
		}
	}

	private void fallback(byte b) {
		settleWhitespace();
		started = true;
		afterWord = false;
		out.append(smtpUtf8 ? (char) (b & 0xff) : WINDOWS_1252[b & 0xff]);
	}

	/** An unfinished UTF-8 sequence is not one: its bytes are fallback text. */
	private void flushCarry() {
		int n = carryLen;
		carryLen = 0;
		for (int i = 0; i < n; i++) {
			fallback(carry[i]);
		}
	}

	private void flush(Sink sink) throws MimeParseException {
		if (out.length() > 0) {
			String text = out.toString();
			out.setLength(0);
			sink.text(text);
		}
	}

}
