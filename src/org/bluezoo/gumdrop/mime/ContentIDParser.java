/*
 * ContentIDParser.java
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

import org.bluezoo.gumdrop.mime.rfc5322.MessageIDParser;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for MIME Content-ID and RFC 5322 Message-ID header values.
 * The format is identical: {@code <id-left@id-right>}
 * Uses ByteBuffer and CharsetDecoder only; decoder is typically US-ASCII or UTF-8 (SMTPUTF8).
 *
 * @see <a href='https://www.rfc-editor.org/rfc/rfc5322#section-3.6.4'>RFC 5322</a>
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-7'>RFC 2045</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentIDParser {

	private ContentIDParser() {
		// Static utility class
	}

	/**
	 * Parses a single Content-ID or Message-ID value from the buffer.
	 * Uses position and limit; advances position as the value is consumed.
	 *
	 * @param value the header value bytes (position to limit); position is advanced
	 * @param decoder charset decoder for id-left and id-right (e.g. US-ASCII or UTF-8)
	 * @return the parsed ContentID, or null if the value is invalid or not exactly one msg-id
	 */
	public static ContentID parse(ByteBuffer value, CharsetDecoder decoder) {
		if (value == null || !value.hasRemaining()) {
			return null;
		}
		ByteBuffer dup = value.duplicate();
		List<ContentID> list = MessageIDParser.parseMessageIDList(dup, decoder);
		if (list == null || list.size() != 1) {
			return null;
		}
		value.position(dup.position());
		return list.get(0);
	}

	/**
	 * Parses a list of Content-ID or Message-ID values from the buffer.
	 * Values may be separated by whitespace, comments, and/or commas.
	 * Uses position and limit; advances position as values are consumed.
	 *
	 * @param value the header value bytes (position to limit); position is advanced
	 * @param decoder charset decoder for id-left and id-right (e.g. US-ASCII or UTF-8)
	 * @return list of ContentID objects, or null if parsing fails
	 */
	public static List<ContentID> parseList(ByteBuffer value, CharsetDecoder decoder) {
		return MessageIDParser.parseMessageIDList(value, decoder);
	}
}
