/*
 * ByteDecoder.java
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

package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes bytes in a named charset to text in any chunking, holding between
 * calls only the start of a character that has not yet arrived in full
 * (at most a few bytes). A byte sequence that is not valid in the charset
 * becomes U+FFFD. A charset that is not known is read as UTF-8.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ByteDecoder {

	private final CharsetDecoder decoder;
	private byte[] carry = new byte[8];
	private int carryLen;
	private final CharBuffer chars = CharBuffer.allocate(256);

	/**
	 * Creates a decoder for the named charset.
	 * @param charsetName the charset name, such as {@code UTF-8}
	 */
	ByteDecoder(String charsetName) {
		Charset charset;
		try {
			charset = Charset.forName(charsetName);
		} catch (RuntimeException e) {
			// not a charset we know, or not a charset name
			charset = StandardCharsets.UTF_8;
		}
		decoder = charset.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);
	}

	/**
	 * Decodes some bytes, appending the text to out.
	 * @param bytes the bytes
	 * @param from the start of the bytes to decode
	 * @param to the end of the bytes to decode
	 * @param out the receiver of the text
	 */
	void feed(byte[] bytes, int from, int to, StringBuilder out) {
		ByteBuffer in;
		if (carryLen > 0) {
			byte[] joined = new byte[carryLen + to - from];
			System.arraycopy(carry, 0, joined, 0, carryLen);
			System.arraycopy(bytes, from, joined, carryLen, to - from);
			in = ByteBuffer.wrap(joined);
			carryLen = 0;
		} else {
			in = ByteBuffer.wrap(bytes, from, to - from);
		}
		decode(in, false, out);
		int left = in.remaining();
		if (left > 0) {
			if (left > carry.length) {
				carry = new byte[left];
			}
			in.get(carry, 0, left);
			carryLen = left;
		}
	}

	/**
	 * Ends the text: a character that did not arrive in full is invalid.
	 * @param out the receiver of the text
	 */
	void finish(StringBuilder out) {
		ByteBuffer in = ByteBuffer.wrap(carry, 0, carryLen);
		carryLen = 0;
		decode(in, true, out);
		while (true) {
			chars.clear();
			CoderResult r = decoder.flush(chars);
			chars.flip();
			out.append(chars);
			if (!r.isOverflow()) {
				break;
			}
		}
		decoder.reset();
	}

	private void decode(ByteBuffer in, boolean endOfInput, StringBuilder out) {
		while (true) {
			chars.clear();
			CoderResult r = decoder.decode(in, chars, endOfInput);
			chars.flip();
			out.append(chars);
			if (!r.isOverflow()) {
				return;
			}
		}
	}

}
