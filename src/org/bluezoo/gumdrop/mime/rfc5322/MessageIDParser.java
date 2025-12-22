/*
 * MessageIDParser.java
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

package org.bluezoo.gumdrop.mime.rfc5322;

import org.bluezoo.gumdrop.mime.ContentID;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for RFC 5322 Message-ID lists.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIDParser {

    // Prevent instantiation
	private MessageIDParser() {
	}

	/**
	 * Parse a list of Message-IDs from an RFC 5322 header field value.
	 *
	 * Supports the msg-id format: [CFWS] "&lt;" id-left "@" id-right "&gt;" [CFWS]
	 * where multiple message IDs are separated by whitespace, comments, and/or commas.
	 *
	 * Note: While RFC 5322 specifies CFWS (whitespace/comments) as separators,
	 * this implementation also accepts commas for compatibility with major email
	 * clients like Microsoft Outlook that use comma-separated message-id lists.
	 *
	 * Examples:
	 * - "&lt;12345@example.com&gt;"
	 * - "&lt;msg1@domain.com&gt; &lt;msg2@domain.com&gt;"
	 * - "&lt;msg1@domain.com&gt;,&lt;msg2@domain.com&gt;"
	 * - "&lt;msg1@domain.com&gt; &lt;msg2@domain.com&gt;,&lt;msg3@domain.com&gt;"
	 * - "(comment) &lt;id@host.com&gt; (another comment)"
	 *
	 * @param value the header field value (e.g., References, In-Reply-To field content)
	 * @return list of ContentID objects, or null if parsing fails due to syntax errors
	 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5322#section-3.6.4">RFC 5322 Section 3.6.4</a>
	 */
	public static List<ContentID> parseMessageIDList(String value) {
		if (value == null || value.trim().isEmpty()) {
			return new ArrayList<>();
		}
		try {
			char[] input = value.toCharArray();
            int[] pos = new int[] { 0 };
			return parseMessageIDs(input, input.length, pos);
		} catch (Exception e) {
			// Return null for any parsing errors
			return null;
		}
	}

	static List<ContentID> parseMessageIDs(char[] input, int end, int[] pos) {
		ArrayList<ContentID> messageIDs = new ArrayList<>();
        StringBuilder tokenBuffer = new StringBuilder();
		skipWhitespaceCommentsAndCommas(input, end, pos);
		while (pos[0] < end) {
			ContentID messageID = parseMessageID(input, end, pos, tokenBuffer);
			if (messageID != null) {
				messageIDs.add(messageID);
			}
			skipWhitespaceCommentsAndCommas(input, end, pos);
		}
		return new ArrayList<>(messageIDs);
	}

	static ContentID parseMessageID(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		if (pos[0] >= length || input[pos[0]] != '<') {
			throw new IllegalArgumentException(); // will be caught at top level
		}

		pos[0]++; // Skip '<'

		// Parse id-left (local part)
		String localPart = parseIdLeft(input, length, pos, tokenBuffer);

		if (pos[0] >= length || input[pos[0]] != '@') {
			throw new IllegalArgumentException(); // will be caught at top level
		}

		pos[0]++; // Skip '@'

		// Parse id-right (domain)
		String domain = parseIdRight(input, length, pos, tokenBuffer);

		if (pos[0] >= length || input[pos[0]] != '>') {
			throw new IllegalArgumentException(); // will be caught at top level
		}

		pos[0]++; // Skip '>'

		return new ContentID(localPart, domain);
	}

	static String parseIdLeft(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		tokenBuffer.setLength(0);
		// Parse dot-atom-text for id-left
		parseDotAtomText(input, length, pos, tokenBuffer);
		return tokenBuffer.toString();
	}

	static String parseIdRight(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		tokenBuffer.setLength(0);
		if (pos[0] < length && input[pos[0]] == '[') {
			// Domain literal: [dtext]
			EmailAddressParser.parseDomain(input, length, pos, tokenBuffer);
		} else {
			// Dot-atom-text
			parseDotAtomText(input, length, pos, tokenBuffer);
		}
		return tokenBuffer.toString();
	}

	static void parseDotAtomText(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		// Parse atom
		EmailAddressParser.parseAtom(input, length, pos, tokenBuffer);
		// Parse additional dot-atom parts
		while (pos[0] < length && input[pos[0]] == '.') {
			tokenBuffer.append('.');
			pos[0]++; // Skip dot
			EmailAddressParser.parseAtom(input, length, pos, tokenBuffer);
		}
	}

	static void skipWhitespaceCommentsAndCommas(char[] input, int length, int[] pos) {
		while (pos[0] < length) {
			char c = input[pos[0]];
			if (EmailAddressParser.isWhitespace(c)) {
				pos[0]++;
			} else if (c == '(') {
				EmailAddressParser.skipComment(input, length, pos);
			} else if (c == ',') {
				// Accept comma as separator for compatibility with Outlook and other email clients
				pos[0]++;
			} else {
				break;
			}
		}
	}

}
