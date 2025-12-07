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

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for MIME Content-ID and RFC 5322 Message-ID header values.
 * The format is identical: {@code <id-left@id-right>}
 * @see <a href='https://www.rfc-editor.org/rfc/rfc5322#section-3.6.4'>RFC 5322</a>
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-7'>RFC 2045</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentIDParser {

	private ContentIDParser() {
		// Static utility class
	}

	/**
	 * Parses a single Content-ID or Message-ID value.
	 * @param value the header value string (with or without angle brackets)
	 * @return the parsed ContentID, or null if the value is invalid
	 */
	public static ContentID parse(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		value = value.trim();

		// Remove angle brackets if present
		int start = 0;
		int end = value.length();
		if (value.charAt(0) == '<') {
			start = 1;
		}
		if (value.charAt(end - 1) == '>') {
			end--;
		}

		if (start >= end) {
			return null;
		}

		// Find the @ separator
		String content = value.substring(start, end);
		int atIndex = content.indexOf('@');
		if (atIndex < 1 || atIndex >= content.length() - 1) {
			return null;
		}

		String localPart = content.substring(0, atIndex);
		String domain = content.substring(atIndex + 1);

		if (localPart.isEmpty() || domain.isEmpty()) {
			return null;
		}

		return new ContentID(localPart, domain);
	}

	/**
	 * Parses a list of Content-ID or Message-ID values.
	 * Values may be separated by whitespace, comments, and/or commas.
	 * @param value the header value string containing one or more IDs
	 * @return list of ContentID objects, or null if parsing fails
	 */
	public static List<ContentID> parseList(String value) {
		if (value == null || value.trim().isEmpty()) {
			return new ArrayList<>();
		}

		List<ContentID> result = new ArrayList<>();
		int pos = 0;
		int len = value.length();

		while (pos < len) {
			// Skip whitespace, comments, and commas
			pos = skipWhitespaceAndComments(value, pos);
			if (pos >= len) {
				break;
			}

			// Skip comma separators
			if (value.charAt(pos) == ',') {
				pos++;
				continue;
			}

			// Look for opening angle bracket
			if (value.charAt(pos) != '<') {
				// Skip to next < or end
				int nextAngle = value.indexOf('<', pos);
				if (nextAngle < 0) {
					break;
				}
				pos = nextAngle;
			}

			// Find closing angle bracket
			int closeAngle = value.indexOf('>', pos);
			if (closeAngle < 0) {
				return null; // Malformed
			}

			String idValue = value.substring(pos, closeAngle + 1);
			ContentID id = parse(idValue);
			if (id != null) {
				result.add(id);
			}

			pos = closeAngle + 1;
		}

		return result.isEmpty() ? null : result;
	}

	private static int skipWhitespaceAndComments(String value, int pos) {
		int len = value.length();
		while (pos < len) {
			char c = value.charAt(pos);
			if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
				pos++;
			} else if (c == '(') {
				// Skip comment
				pos = skipComment(value, pos);
			} else {
				break;
			}
		}
		return pos;
	}

	private static int skipComment(String value, int pos) {
		int len = value.length();
		if (pos >= len || value.charAt(pos) != '(') {
			return pos;
		}

		pos++; // Skip opening paren
		int depth = 1;

		while (pos < len && depth > 0) {
			char c = value.charAt(pos);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (c == '\\' && pos + 1 < len) {
				pos++; // Skip escaped character
			}
			pos++;
		}

		return pos;
	}

}

