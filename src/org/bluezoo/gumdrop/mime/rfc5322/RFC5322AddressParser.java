/*
 * RFC5322AddressParser.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * High-performance RFC 5322 compliant email address list parser.
 *
 * Supports all RFC 5322 address formats including:
 * - Individual addresses: user@domain.com
 * - Named addresses: "John Doe" &lt;user@domain.com&gt;
 * - Groups: My Group: user1@domain.com, user2@domain.com;
 * - Comments: John (Work) &lt;user@domain.com&gt;
 * - Quoted strings with escaped characters
 * - Nested comments
 *
 * This implementation prioritizes performance through:
 * - Single-pass parsing without backtracking
 * - Minimal object allocation
 * - No regex usage
 * - Efficient character-level processing
 * - O(n) time complexity where n is input length
 *
 * Note that the class instance used is not thread safe, so we will only
 * access it via the public static method.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RFC5322AddressParser {

	private final char[] input;
	private final int length;
	private int pos;

	// Reusable buffers to minimize allocation
	private final StringBuilder tokenBuffer = new StringBuilder(256);
	private final ArrayList<String> commentBuffer = new ArrayList<>(4);
	private final ArrayList<EmailAddress> addressBuffer = new ArrayList<>(16);

	private RFC5322AddressParser(String value) {
		this.input = value.toCharArray();
		this.length = input.length;
		this.pos = 0;
	}

	/**
	 * Parse a list of email addresses from an RFC 5322 header value.
	 *
	 * @param value the header field value (e.g., To, Cc, Bcc field content)
	 * @return list of EmailAddress objects, or null if parsing fails
	 */
	public static List<EmailAddress> parseEmailAddressList(String value) {
		if (value == null || value.trim().isEmpty()) {
			return new ArrayList<>();
		}

		try {
			RFC5322AddressParser parser = new RFC5322AddressParser(value);
			return parser.parseAddressList();
		} catch (Exception e) {
			// Return null for any parsing errors
			return null;
		}
	}

	private List<EmailAddress> parseAddressList() {
		addressBuffer.clear();
		skipWhitespaceAndComments();

		while (pos < length) {
			EmailAddress address = parseAddress();
			if (address != null) {
				addressBuffer.add(address);
			}

			skipWhitespaceAndComments();

			// Check for comma separator
			if (pos < length && input[pos] == ',') {
				pos++;
				skipWhitespaceAndComments();
			} else if (pos < length) {
				// If we're not at the end and don't have a comma, it's an error
				throw new IllegalArgumentException("Expected comma separator");
			}
		}

		return new ArrayList<>(addressBuffer);
	}

	private EmailAddress parseAddress() {
		skipWhitespaceAndComments();

		if (pos >= length) {
			return null;
		}

		// Check if this might be a group (look for colon before angle bracket or end)
		int colonPos = findNextUnquotedChar(':', pos);
		int anglePos = findNextUnquotedChar('<', pos);

		if (colonPos != -1 && (anglePos == -1 || colonPos < anglePos)) {
			return parseGroup();
		} else {
			return parseIndividualAddress();
		}
	}

	private GroupEmailAddress parseGroup() {
		commentBuffer.clear();

		// Parse group name
		String groupName = parseDisplayName();
		if (groupName == null || pos >= length || input[pos] != ':') {
			throw new IllegalArgumentException("Invalid group syntax");
		}

		pos++; // Skip colon
		skipWhitespaceAndComments();

		// Parse group members
		ArrayList<EmailAddress> members = new ArrayList<>();

		while (pos < length && input[pos] != ';') {
			EmailAddress member = parseIndividualAddress();
			if (member != null) {
				members.add(member);
			}

			skipWhitespaceAndComments();

			if (pos < length && input[pos] == ',') {
				pos++;
				skipWhitespaceAndComments();
			} else if (pos < length && input[pos] != ';') {
				throw new IllegalArgumentException("Expected comma or semicolon in group");
			}
		}

		if (pos >= length || input[pos] != ';') {
			throw new IllegalArgumentException("Group not terminated with semicolon");
		}

		pos++; // Skip semicolon

		// Parse any trailing comments
		List<String> trailingComments = parseComments();
		if (!trailingComments.isEmpty()) {
			commentBuffer.addAll(trailingComments);
		}

		List<String> comments = commentBuffer.isEmpty() ? null : new ArrayList<>(commentBuffer);
		return new GroupEmailAddress(groupName, members, comments);
	}

	private EmailAddress parseIndividualAddress() {
		commentBuffer.clear();

		// Parse any leading comments
		List<String> leadingComments = parseComments();
		commentBuffer.addAll(leadingComments);

		skipWhitespaceAndComments();

		if (pos >= length) {
			return null;
		}

		String displayName = null;
		String localPart;
		String domain;
		boolean isLegacyFormat = false;

		// Check if we have an angle bracket (indicating display name + address)
		int anglePos = findNextUnquotedChar('<', pos);

		if (anglePos != -1) {
			// Format: [display-name] <addr-spec> - NOT legacy format
			if (anglePos > pos) {
				displayName = parseDisplayName();
				skipWhitespaceAndComments();
			}

			if (pos >= length || input[pos] != '<') {
				throw new IllegalArgumentException("Expected '<' after display name");
			}

			pos++; // Skip '<'
			String[] addrParts = parseAddrSpec();
			localPart = addrParts[0];
			domain = addrParts[1];

			if (pos >= length || input[pos] != '>') {
				throw new IllegalArgumentException("Expected '>' after address");
			}

			pos++; // Skip '>'
		} else {
			// Format: addr-spec only - this IS legacy format
			String[] addrParts = parseAddrSpec();
			localPart = addrParts[0];
			domain = addrParts[1];
			isLegacyFormat = true;
		}

		// Use appropriate constructor based on address format
		if (isLegacyFormat) {
			// Legacy format with no angle brackets - use simpleAddress=true
			// Comments cannot exist on legacy format addresses, so ignore any parsed comments
			return new EmailAddress(displayName, localPart, domain, true);
		} else {
			// Standard format with angle brackets - use constructor with comments
			// Parse any trailing comments
			List<String> trailingComments = parseComments();
			commentBuffer.addAll(trailingComments);

			List<String> comments = commentBuffer.isEmpty() ? null : new ArrayList<>(commentBuffer);
			return new EmailAddress(displayName, localPart, domain, comments);
		}
	}

	private String parseDisplayName() {
		tokenBuffer.setLength(0);
		skipWhitespaceAndComments();

		while (pos < length) {
			char c = input[pos];

			if (c == '<' || c == ':' || c == ',' || c == ';') {
				break;
			} else if (c == '"') {
				// Quoted string
				parseQuotedString();
			} else if (c == '(') {
				// Comment - parse but don't include in display name
				parseComment();
			} else if (isWhitespace(c)) {
				if (tokenBuffer.length() > 0 && tokenBuffer.charAt(tokenBuffer.length() - 1) != ' ') {
					tokenBuffer.append(' ');
				}
				pos++;
			} else if (isAtom(c)) {
				tokenBuffer.append(c);
				pos++;
			} else {
				break;
			}
		}

		String result = tokenBuffer.toString().trim();
		return result.isEmpty() ? null : result;
	}

	private String[] parseAddrSpec() {
		tokenBuffer.setLength(0);

		// Parse local part
		parseLocalPart();
		String localPart = tokenBuffer.toString();

		if (pos >= length || input[pos] != '@') {
			throw new IllegalArgumentException("Expected '@' in address");
		}

		pos++; // Skip '@'
		tokenBuffer.setLength(0);

		// Parse domain
		parseDomain();
		String domain = tokenBuffer.toString();

		return new String[] { localPart, domain };
	}

	private void parseLocalPart() {
		if (pos >= length) {
			throw new IllegalArgumentException("Empty local part");
		}

		if (input[pos] == '"') {
			// Quoted local part
			parseQuotedString();
		} else {
			// Dot-atom local part
			parseAtom();

			while (pos < length && input[pos] == '.') {
				tokenBuffer.append('.');
				pos++;
				parseAtom();
			}
		}
	}

	private void parseDomain() {
		if (pos >= length) {
			throw new IllegalArgumentException("Empty domain");
		}

		if (input[pos] == '[') {
			// Domain literal
			tokenBuffer.append('[');
			pos++;

			while (pos < length && input[pos] != ']') {
				char c = input[pos];
				if (c == '\\' && pos + 1 < length) {
					tokenBuffer.append(c).append(input[pos + 1]);
					pos += 2;
				} else {
					tokenBuffer.append(c);
					pos++;
				}
			}

			if (pos >= length) {
				throw new IllegalArgumentException("Unterminated domain literal");
			}

			tokenBuffer.append(']');
			pos++;
		} else {
			// Dot-atom domain
			parseAtom();

			while (pos < length && input[pos] == '.') {
				tokenBuffer.append('.');
				pos++;
				parseAtom();
			}
		}
	}

	private void parseAtom() {
		int start = tokenBuffer.length();

		while (pos < length && isAtom(input[pos])) {
			tokenBuffer.append(input[pos]);
			pos++;
		}

		if (tokenBuffer.length() == start) {
			throw new IllegalArgumentException("Empty atom");
		}
	}

	private void parseQuotedString() {
		if (pos >= length || input[pos] != '"') {
			throw new IllegalArgumentException("Expected quoted string");
		}

		tokenBuffer.append('"');
		pos++; // Skip opening quote

		while (pos < length && input[pos] != '"') {
			char c = input[pos];
			if (c == '\\' && pos + 1 < length) {
				tokenBuffer.append(c).append(input[pos + 1]);
				pos += 2;
			} else {
				tokenBuffer.append(c);
				pos++;
			}
		}

		if (pos >= length) {
			throw new IllegalArgumentException("Unterminated quoted string");
		}

		tokenBuffer.append('"');
		pos++; // Skip closing quote
	}

	private List<String> parseComments() {
		List<String> comments = new ArrayList<>();

		while (pos < length && (isWhitespace(input[pos]) || input[pos] == '(')) {
			if (input[pos] == '(') {
				String comment = parseComment();
				if (comment != null && !comment.trim().isEmpty()) {
					comments.add(comment.trim());
				}
			} else {
				pos++;
			}
		}

		return comments;
	}

	private String parseComment() {
		if (pos >= length || input[pos] != '(') {
			return null;
		}

		StringBuilder comment = new StringBuilder();
		pos++; // Skip opening paren
		int depth = 1;

		while (pos < length && depth > 0) {
			char c = input[pos];

			if (c == '(') {
				depth++;
				comment.append(c);
			} else if (c == ')') {
				depth--;
				if (depth > 0) {
					comment.append(c);
				}
			} else if (c == '\\' && pos + 1 < length) {
				comment.append(input[pos + 1]);
				pos++; // Skip escaped character
			} else {
				comment.append(c);
			}

			pos++;
		}

		if (depth > 0) {
			throw new IllegalArgumentException("Unterminated comment");
		}

		return comment.toString();
	}

	private void skipWhitespaceAndComments() {
		while (pos < length) {
			char c = input[pos];
			if (isWhitespace(c)) {
				pos++;
			} else if (c == '(') {
				parseComment(); // Parse and discard
			} else {
				break;
			}
		}
	}

	private int findNextUnquotedChar(char target, int start) {
		boolean inQuotes = false;
		int commentDepth = 0;

		for (int i = start; i < length; i++) {
			char c = input[i];

			if (c == '"' && commentDepth == 0) {
				if (i == 0 || input[i - 1] != '\\') {
					inQuotes = !inQuotes;
				}
			} else if (c == '(' && !inQuotes) {
				commentDepth++;
			} else if (c == ')' && !inQuotes) {
				commentDepth--;
			} else if (c == target && !inQuotes && commentDepth == 0) {
				return i;
			}
		}

		return -1;
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n';
	}

	private static boolean isAtom(char c) {
		return c > 32 && c < 127 &&
			   c != '(' && c != ')' && c != '<' && c != '>' && c != '[' && c != ']' &&
			   c != ':' && c != ';' && c != '@' && c != '\\' && c != ',' && c != '.' &&
			   c != '"' && !isWhitespace(c);
	}

}

