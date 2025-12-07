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
 * High-performance parser for RFC 5322 Message-ID lists.
 * Uses single-pass character-level parsing for maximum efficiency.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIDParser {

	private final char[] input;
	private final int length;
	private int pos;
	private final StringBuilder tokenBuffer = new StringBuilder(128);
	private final ArrayList<ContentID> messageIDs = new ArrayList<>();

	private MessageIDParser(String value) {
		this.input = value.toCharArray();
		this.length = input.length;
		this.pos = 0;
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
			MessageIDParser parser = new MessageIDParser(value);
			return parser.parseMessageIDs();
		} catch (Exception e) {
			// Return null for any parsing errors
			return null;
		}
	}

	private List<ContentID> parseMessageIDs() {
		messageIDs.clear();
		skipWhitespaceCommentsAndCommas();

		while (pos < length) {
			ContentID messageID = parseMessageID();
			if (messageID != null) {
				messageIDs.add(messageID);
			}

			skipWhitespaceCommentsAndCommas();
		}

		return new ArrayList<>(messageIDs);
	}

	private ContentID parseMessageID() {
		skipWhitespaceCommentsAndCommas();

		if (pos >= length || input[pos] != '<') {
			throw new IllegalArgumentException("Expected '<' to start message ID");
		}

		pos++; // Skip '<'

		// Parse id-left (local part)
		String localPart = parseIdLeft();

		if (pos >= length || input[pos] != '@') {
			throw new IllegalArgumentException("Expected '@' in message ID");
		}

		pos++; // Skip '@'

		// Parse id-right (domain)
		String domain = parseIdRight();

		if (pos >= length || input[pos] != '>') {
			throw new IllegalArgumentException("Expected '>' to end message ID");
		}

		pos++; // Skip '>'

		return new ContentID(localPart, domain);
	}

	private String parseIdLeft() {
		tokenBuffer.setLength(0);

		// Parse dot-atom-text for id-left
		parseDotAtomText();

		return tokenBuffer.toString();
	}

	private String parseIdRight() {
		tokenBuffer.setLength(0);

		if (pos < length && input[pos] == '[') {
			// Domain literal: [dtext]
			parseDomainLiteral();
		} else {
			// Dot-atom-text
			parseDotAtomText();
		}

		return tokenBuffer.toString();
	}

	private void parseDotAtomText() {
		// Parse atom
		parseAtom();

		// Parse additional dot-atom parts
		while (pos < length && input[pos] == '.') {
			tokenBuffer.append('.');
			pos++; // Skip dot
			parseAtom();
		}
	}

	private void parseAtom() {
		int startLength = tokenBuffer.length();

		while (pos < length && isAtextChar(input[pos])) {
			tokenBuffer.append(input[pos]);
			pos++;
		}

		if (tokenBuffer.length() == startLength) {
			throw new IllegalArgumentException("Empty atom in message ID");
		}
	}

	private void parseDomainLiteral() {
		if (pos >= length || input[pos] != '[') {
			throw new IllegalArgumentException("Expected '[' for domain literal");
		}

		tokenBuffer.append('[');
		pos++; // Skip '['

		while (pos < length && input[pos] != ']') {
			char c = input[pos];
			if (c == '\\' && pos + 1 < length) {
				// Quoted-pair
				tokenBuffer.append(c).append(input[pos + 1]);
				pos += 2;
			} else if (isDtextChar(c)) {
				tokenBuffer.append(c);
				pos++;
			} else {
				throw new IllegalArgumentException("Invalid character in domain literal");
			}
		}

		if (pos >= length) {
			throw new IllegalArgumentException("Unterminated domain literal");
		}

		tokenBuffer.append(']');
		pos++; // Skip ']'
	}

	private void skipWhitespaceCommentsAndCommas() {
		while (pos < length) {
			char c = input[pos];
			if (isWhitespace(c)) {
				pos++;
			} else if (c == '(') {
				skipComment();
			} else if (c == ',') {
				// Accept comma as separator for compatibility with Outlook and other email clients
				pos++;
			} else {
				break;
			}
		}
	}

	private void skipComment() {
		if (pos >= length || input[pos] != '(') {
			return;
		}

		pos++; // Skip '('
		int depth = 1;

		while (pos < length && depth > 0) {
			char c = input[pos];

			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (c == '\\' && pos + 1 < length) {
				pos++; // Skip escaped character
			}

			pos++;
		}

		if (depth > 0) {
			throw new IllegalArgumentException("Unterminated comment");
		}
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n';
	}

	private static boolean isAtextChar(char c) {
		// RFC 5322 atext = ALPHA / DIGIT / "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "/" / "=" / "?" / "^" / "_" / "`" / "{" / "|" / "}" / "~"
		return (c >= 'a' && c <= 'z') ||
			(c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') ||
			c == '!' || c == '#' || c == '$' || c == '%' || c == '&' ||
			c == '\'' || c == '*' || c == '+' || c == '-' || c == '/' ||
			c == '=' || c == '?' || c == '^' || c == '_' || c == '`' ||
			c == '{' || c == '|' || c == '}' || c == '~';
	}

	private static boolean isDtextChar(char c) {
		// RFC 5322 dtext = %d33-90 / %d94-126 (printable ASCII except [ ] \)
		return c >= 33 && c <= 126 && c != '[' && c != ']' && c != '\\';
	}

}

