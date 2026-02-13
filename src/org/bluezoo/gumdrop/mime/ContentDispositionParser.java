/*
 * ContentDispositionParser.java
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
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Parser for MIME Content-Disposition header values.
 * @see <a href='https://www.ietf.org/rfc/rfc2183.txt'>RFC 2183</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentDispositionParser {

	private ContentDispositionParser() {
		// Static utility class
	}

	/**
	 * Parses a Content-Disposition header value from bytes, decoding only the particles required.
	 *
	 * @param value the header value bytes (position to limit)
	 * @param decoder charset decoder for decoding slices (e.g. ISO-8859-1); will be reset per use
	 * @return the parsed ContentDisposition, or null if the value is invalid
	 */
	public static ContentDisposition parse(ByteBuffer value, CharsetDecoder decoder) {
		if (value == null || !value.hasRemaining()) {
			return null;
		}
		int start = value.position();
		int end = value.limit();
		int len = end - start;
		if (len < 3) {
			return null;
		}
		value.position(start + 3);
		int semicolonIndex = MIMEParser.indexOf(value, (byte) ';');
		int typeEnd = semicolonIndex < 0 ? end : semicolonIndex;
		value.position(start);
		value.limit(typeEnd);
		String dispositionType = MIMEParser.decodeSlice(value, decoder);
		value.limit(end);
		if (dispositionType == null || !MIMEUtils.isToken(dispositionType)) {
			value.position(start);
			return null;
		}
		int paramsStart = semicolonIndex < 0 ? end : semicolonIndex + 1;
		value.position(paramsStart);
		value.limit(end);
		List<Parameter> parameters = ContentTypeParser.parseParameterList(value, decoder);
		value.position(end);
		return new ContentDisposition(dispositionType, parameters);
	}

	/**
	 * Parses a Content-Disposition header value from a string (convenience).
	 *
	 * @param value the header value string
	 * @return the parsed ContentDisposition, or null if the value is invalid
	 */
	public static ContentDisposition parse(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		ByteBuffer buf = ByteBuffer.wrap(value.getBytes(StandardCharsets.ISO_8859_1));
		CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);
		return parse(buf, decoder);
	}

}

