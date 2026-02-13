/*
 * EmailAddressParser.java
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

import org.bluezoo.gumdrop.mime.MIMEParser;
import org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * High-performance RFC 5322 compliant email address parser.
 *
 * <p>Supports all RFC 5322 address formats including:
 * <ul>
 *   <li>Individual addresses: user@domain.com</li>
 *   <li>Named addresses: "John Doe" &lt;user@domain.com&gt;</li>
 *   <li>Groups: My Group: user1@domain.com, user2@domain.com;</li>
 *   <li>Quoted strings with escaped characters</li>
 * </ul>
 *
 * <p>This implementation prioritizes performance through:
 * <ul>
 *   <li>Single-pass parsing without backtracking</li>
 *   <li>Minimal object allocation</li>
 *   <li>No regex usage</li>
 *   <li>O(n) time complexity where n is input length</li>
 * </ul>
 *
 * <p>This class cannot be instantiated. All methods are static.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EmailAddressParser {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.mime.rfc5322.L10N");

	// Prevent instantiation
	private EmailAddressParser() {
	}

	/**
	 * Parse a list of email addresses from an RFC 5322 header value.
	 *
	 * @param value the header field value (e.g., To, Cc, Bcc field content)
	 * @return list of EmailAddress objects, or null if parsing fails
	 */
	public static List<EmailAddress> parseEmailAddressList(String value) {
		return parseEmailAddressList(value, false);
	}

	/**
	 * Parse a list of email addresses with optional SMTPUTF8 support (RFC 6532).
	 *
	 * <p>When smtputf8 is true, UTF-8 characters are permitted in email
	 * addresses (local-part, domain, and display-name) per RFC 6531/6532
	 * (Internationalized Email).
	 *
	 * @param value the header field value (e.g., To, Cc, Bcc field content)
	 * @param smtputf8 if true, allow UTF-8 characters per RFC 6531/6532
	 * @return list of EmailAddress objects, or null if parsing fails
	 */
	public static List<EmailAddress> parseEmailAddressList(ByteBuffer value, CharsetDecoder decoder) {
		if (value == null || !value.hasRemaining()) {
			return new ArrayList<>();
		}
		try {
			List<EmailAddress> addresses = new ArrayList<>();
			int limit = value.limit();
			while (value.position() < limit) {
				skipCfws(value);
				if (value.position() >= limit) {
					break;
				}
				byte b = value.get(value.position());
				if (b == ':' || b == ';') {
					skipGroup(value);
					continue;
				}
				String displayName = null;
				if (b != '<') {
					byte[] stopBytes = new byte[] { '<', ':', ',', ';' };
					displayName = RFC2047Decoder.decodeDisplayName(value, decoder, false, stopBytes);
					if (value.position() >= limit) {
						break;
					}
					b = value.get(value.position());
				}
				if (b != '<') {
					break;
				}
				value.position(value.position() + 1);
				int[] localRange = parseLocalPartRange(value);
				if (localRange == null) {
					break;
				}
				int savedLimit = value.limit();
				value.position(localRange[0]).limit(localRange[1]);
				String localPart = MIMEParser.decodeSlice(value, decoder);
				value.limit(savedLimit);
				if (value.position() >= limit || value.get(value.position()) != '@') {
					break;
				}
				value.position(value.position() + 1);
				int[] domainRange = parseDomainRange(value);
				if (domainRange == null) {
					break;
				}
				value.position(domainRange[0]).limit(domainRange[1]);
				String domain = MIMEParser.decodeSlice(value, decoder);
				value.limit(savedLimit);
				if (value.position() >= limit || value.get(value.position()) != '>') {
					break;
				}
				value.position(value.position() + 1);
				addresses.add(new EmailAddress(displayName, localPart, domain, (List<String>) null));
			}
			return addresses;
		} catch (Exception e) {
			return null;
		}
	}

	public static List<EmailAddress> parseEmailAddressList(String value, boolean smtputf8) {
		if (value == null || value.isEmpty()) {
			return new ArrayList<>();
		}
		// Trim leading/trailing whitespace
		int start = 0;
		int end = value.length();
		while (start < end && isWhitespace(value.charAt(start))) {
			start++;
		}
		while (end > start && isWhitespace(value.charAt(end - 1))) {
			end--;
		}
		if (start >= end) {
			return new ArrayList<>();
		}
		try {
			char[] input = value.toCharArray();
			int[] pos = new int[] { start };
			StringBuilder tokenBuffer = new StringBuilder(256);
			List<EmailAddress> addresses = new ArrayList<>();
			skipWhitespaceAndComments(input, end, pos);
			while (pos[0] < end) {
				EmailAddress address = parseAddress(input, end, pos, tokenBuffer, smtputf8);
				if (address != null) {
					addresses.add(address);
				}
				skipWhitespaceAndComments(input, end, pos);
				if (pos[0] < end && input[pos[0]] == ',') {
					pos[0]++;
					skipWhitespaceAndComments(input, end, pos);
				} else if (pos[0] < end) {
					// No comma and not at end - parsing error
					return null;
				}
			}
			return addresses;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Parse a single email address with full RFC 5322 syntax.
	 *
	 * <p>This method supports the full RFC 5322 address format including
	 * display names and angle brackets. For SMTP envelope addresses, use
	 * {@link #parseEnvelopeAddress(String)} instead.
	 *
	 * @param value the email address string
	 * @return the parsed EmailAddress, or null if parsing fails or value is empty
	 */
	public static EmailAddress parseEmailAddress(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			char[] input = value.toCharArray();
			int length = input.length;
			int[] pos = new int[] { 0 };
			StringBuilder tokenBuffer = new StringBuilder(256);
			skipWhitespaceAndComments(input, length, pos);
			EmailAddress address = parseIndividualAddress(input, length, pos, tokenBuffer);
			skipWhitespaceAndComments(input, length, pos);
			if (pos[0] < length) {
				return null;
			}
			return address;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Parse an SMTP envelope address (MAIL FROM or RCPT TO).
	 *
	 * <p>SMTP envelope addresses per RFC 5321 must be in the simple form
	 * {@code local-part@domain} without display names, angle brackets, or
	 * comments. This method is optimized for this use case.
	 *
	 * @param value the envelope address (e.g., "user@example.com")
	 * @return the parsed EmailAddress, or null if invalid
	 */
	public static EmailAddress parseEnvelopeAddress(String value) {
		return parseEnvelopeAddress(value, false);
	}

	/**
	 * Parse an SMTP envelope address with optional SMTPUTF8 support (RFC 6531).
	 *
	 * <p>SMTP envelope addresses per RFC 5321 must be in the simple form
	 * {@code local-part@domain} without display names, angle brackets, or
	 * comments. When smtputf8 is true, UTF-8 characters are permitted in
	 * the local-part and domain per RFC 6531 (Internationalized Email).
	 *
	 * @param value the envelope address (e.g., "user@example.com" or "用户@例え.jp")
	 * @param smtputf8 if true, allow UTF-8 characters per RFC 6531
	 * @return the parsed EmailAddress, or null if invalid
	 */
	public static EmailAddress parseEnvelopeAddress(String value, boolean smtputf8) {
		if (value == null) {
			return null;
		}
		int len = value.length();
		if (len == 0) {
			return null;
		}
		// Find the @ separator
		int atPos = -1;
		boolean inQuote = false;
		for (int i = 0; i < len; i++) {
			char c = value.charAt(i);
			if (c == '"' && (i == 0 || value.charAt(i - 1) != '\\')) {
				inQuote = !inQuote;
			} else if (c == '@' && !inQuote) {
				if (atPos >= 0) {
					return null;
				}
				atPos = i;
			}
		}
		if (atPos <= 0 || atPos >= len - 1) {
			return null;
		}
		String localPart = value.substring(0, atPos);
		String domain = value.substring(atPos + 1);
		if (!isValidLocalPart(localPart, smtputf8)) {
			return null;
		}
		if (!isValidDomain(domain, smtputf8)) {
			return null;
		}
		return new EmailAddress(null, localPart, domain, true);
	}

	// -- Envelope address validation --

	private static boolean isValidLocalPart(String localPart) {
		return isValidLocalPart(localPart, false);
	}

	private static boolean isValidLocalPart(String localPart, boolean smtputf8) {
		int len = localPart.length();
		if (len == 0 || len > 64) {
			return false;
		}
		if (localPart.charAt(0) == '"') {
			if (len < 2 || localPart.charAt(len - 1) != '"') {
				return false;
			}
			for (int i = 1; i < len - 1; i++) {
				char c = localPart.charAt(i);
				if (c == '\\' && i + 1 < len - 1) {
					i++;
				} else if (c < 32 || c == 127) {
					// In SMTPUTF8 mode, allow UTF-8 (>127), but still reject control chars
					if (!smtputf8 || c < 128) {
						return false;
					}
				}
			}
			return true;
		}
		if (localPart.charAt(0) == '.' || localPart.charAt(len - 1) == '.') {
			return false;
		}
		boolean prevDot = false;
		for (int i = 0; i < len; i++) {
			char c = localPart.charAt(i);
			if (c == '.') {
				if (prevDot) {
					return false;
				}
				prevDot = true;
			} else {
				prevDot = false;
				if (!isAtomChar(c, smtputf8)) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isValidDomain(String domain) {
		return isValidDomain(domain, false);
	}

	private static boolean isValidDomain(String domain, boolean smtputf8) {
		int len = domain.length();
		if (len == 0 || len > 255) {
			return false;
		}
		if (domain.charAt(0) == '[') {
			// Address literal - same rules apply regardless of SMTPUTF8
			if (domain.charAt(len - 1) != ']') {
				return false;
			}
			for (int i = 1; i < len - 1; i++) {
				char c = domain.charAt(i);
				if (c < 33 || c > 126 || c == '[' || c == ']' || c == '\\') {
					return false;
				}
			}
			return true;
		}
		if (domain.charAt(0) == '.' || domain.charAt(len - 1) == '.') {
			return false;
		}
		boolean prevDot = false;
		for (int i = 0; i < len; i++) {
			char c = domain.charAt(i);
			if (c == '.') {
				if (prevDot) {
					return false;
				}
				prevDot = true;
			} else {
				prevDot = false;
				// Standard ASCII domain chars
				if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
				    (c >= '0' && c <= '9') || c == '-') {
					continue;
				}
				// SMTPUTF8 allows UTF-8 in domain (U-labels per RFC 6531)
				if (smtputf8 && c > 127) {
					continue;
				}
				return false;
			}
		}
		return true;
	}

	private static boolean isAtomChar(char c) {
		return isAtomChar(c, false);
	}

	private static boolean isAtomChar(char c, boolean smtputf8) {
		if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
		    (c >= '0' && c <= '9')) {
			return true;
		}
		switch (c) {
			case '!': case '#': case '$': case '%': case '&': case '\'':
			case '*': case '+': case '-': case '/': case '=': case '?':
			case '^': case '_': case '`': case '{': case '|': case '}':
			case '~':
				return true;
			default:
				// SMTPUTF8 allows UTF-8 characters (code points > 127) per RFC 6531
				if (smtputf8 && c > 127) {
					return true;
				}
				return false;
		}
	}

	// -- RFC 5322 address list parsing --

	private static EmailAddress parseAddress(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		return parseAddress(input, length, pos, tokenBuffer, false);
	}

	private static EmailAddress parseAddress(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		skipWhitespaceAndComments(input, length, pos);

		if (pos[0] >= length) {
			return null;
		}

		int colonPos = findNextUnquotedChar(input, length, ':', pos[0]);
		int anglePos = findNextUnquotedChar(input, length, '<', pos[0]);

		if (colonPos != -1 && (anglePos == -1 || colonPos < anglePos)) {
			return parseGroup(input, length, pos, tokenBuffer, smtputf8);
		} else {
			return parseIndividualAddress(input, length, pos, tokenBuffer, smtputf8);
		}
	}

	private static GroupEmailAddress parseGroup(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		return parseGroup(input, length, pos, tokenBuffer, false);
	}

	private static GroupEmailAddress parseGroup(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		String groupName = parseDisplayName(input, length, pos, tokenBuffer, smtputf8);
		if (groupName == null || pos[0] >= length || input[pos[0]] != ':') {
			throw new IllegalArgumentException(L10N.getString("err.invalid_group_syntax"));
		}
		pos[0]++;
		skipWhitespaceAndComments(input, length, pos);
		List<EmailAddress> members = new ArrayList<>();
		while (pos[0] < length && input[pos[0]] != ';') {
			EmailAddress member = parseIndividualAddress(input, length, pos, tokenBuffer, smtputf8);
			if (member != null) {
				members.add(member);
			}
			skipWhitespaceAndComments(input, length, pos);
			if (pos[0] < length && input[pos[0]] == ',') {
				pos[0]++;
				skipWhitespaceAndComments(input, length, pos);
			} else if (pos[0] < length && input[pos[0]] != ';') {
				throw new IllegalArgumentException(L10N.getString("err.expected_comma_or_semicolon_in_group"));
			}
		}
		if (pos[0] >= length || input[pos[0]] != ';') {
			throw new IllegalArgumentException(L10N.getString("err.group_not_semicolon_terminated"));
		}
		pos[0]++;
		skipWhitespaceAndComments(input, length, pos);
		return new GroupEmailAddress(groupName, members, null);
	}

	private static EmailAddress parseIndividualAddress(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		return parseIndividualAddress(input, length, pos, tokenBuffer, false);
	}

	private static EmailAddress parseIndividualAddress(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		skipWhitespaceAndComments(input, length, pos);
		if (pos[0] >= length) {
			return null;
		}
		String displayName = null;
		String localPart;
		String domain;
		boolean isLegacyFormat = false;
		int anglePos = findNextUnquotedChar(input, length, '<', pos[0]);
		if (anglePos != -1) {
			if (anglePos > pos[0]) {
				displayName = parseDisplayName(input, length, pos, tokenBuffer, smtputf8);
				skipWhitespaceAndComments(input, length, pos);
			}
			if (pos[0] >= length || input[pos[0]] != '<') {
				throw new IllegalArgumentException(L10N.getString("err.expected_lt_after_display_name"));
			}
			pos[0]++;
			String[] addrParts = parseAddrSpec(input, length, pos, tokenBuffer, smtputf8);
			localPart = addrParts[0];
			domain = addrParts[1];
			if (pos[0] >= length || input[pos[0]] != '>') {
				throw new IllegalArgumentException(L10N.getString("err.expected_gt_after_address"));
			}
			pos[0]++;
		} else {
			String[] addrParts = parseAddrSpec(input, length, pos, tokenBuffer, smtputf8);
			localPart = addrParts[0];
			domain = addrParts[1];
			isLegacyFormat = true;
		}
		if (isLegacyFormat) {
			return new EmailAddress(displayName, localPart, domain, true);
		} else {
			return new EmailAddress(displayName, localPart, domain, (List<String>) null);
		}
	}

	private static String parseDisplayName(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		return parseDisplayName(input, length, pos, tokenBuffer, false);
	}

	private static String parseDisplayName(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		tokenBuffer.setLength(0);
		skipWhitespaceAndComments(input, length, pos);
		while (pos[0] < length) {
			char c = input[pos[0]];
			if (c == '<' || c == ':' || c == ',' || c == ';') {
				break;
			} else if (c == '"') {
				parseQuotedString(input, length, pos, tokenBuffer);
			} else if (c == '(') {
				skipComment(input, length, pos);
			} else if (isWhitespace(c)) {
				int bufLen = tokenBuffer.length();
				if (bufLen > 0 && tokenBuffer.charAt(bufLen - 1) != ' ') {
					tokenBuffer.append(' ');
				}
				pos[0]++;
			} else if (isAtom(c, smtputf8)) {
				tokenBuffer.append(c);
				pos[0]++;
			} else {
				break;
			}
		}
		String result = tokenBuffer.toString().trim();
		// Canonical form: display-name does not include surrounding quotes
		if (result.length() >= 2 && result.charAt(0) == '"' && result.charAt(result.length() - 1) == '"') {
			result = result.substring(1, result.length() - 1);
		}
		return result.isEmpty() ? null : result;
	}

	private static String[] parseAddrSpec(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		return parseAddrSpec(input, length, pos, tokenBuffer, false);
	}

	private static String[] parseAddrSpec(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		tokenBuffer.setLength(0);
		parseLocalPart(input, length, pos, tokenBuffer, smtputf8);
		String localPart = tokenBuffer.toString();
		if (pos[0] >= length || input[pos[0]] != '@') {
			throw new IllegalArgumentException(L10N.getString("err.expected_amp_in_address"));
		}
		pos[0]++;
		tokenBuffer.setLength(0);
		parseDomain(input, length, pos, tokenBuffer, smtputf8);
		String domain = tokenBuffer.toString();
		return new String[] { localPart, domain };
	}

	private static void parseLocalPart(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		parseLocalPart(input, length, pos, tokenBuffer, false);
	}

	private static void parseLocalPart(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		if (pos[0] >= length) {
			throw new IllegalArgumentException(L10N.getString("err.empty_local_part"));
		}
		if (input[pos[0]] == '"') {
			parseQuotedString(input, length, pos, tokenBuffer);
		} else {
			parseAtom(input, length, pos, tokenBuffer, smtputf8);
			while (pos[0] < length && input[pos[0]] == '.') {
				tokenBuffer.append('.');
				pos[0]++;
				parseAtom(input, length, pos, tokenBuffer, smtputf8);
			}
		}
	}

	static void parseDomain(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		parseDomain(input, length, pos, tokenBuffer, false);
	}

	static void parseDomain(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		if (pos[0] >= length) {
			throw new IllegalArgumentException(L10N.getString("err.empty_domain"));
		}
		if (input[pos[0]] == '[') {
			tokenBuffer.append('[');
			pos[0]++;
			while (pos[0] < length && input[pos[0]] != ']') {
				char c = input[pos[0]];
				if (c == '\\' && pos[0] + 1 < length) {
                    // Quoted-pair
					tokenBuffer.append(c);
					tokenBuffer.append(input[pos[0] + 1]);
					pos[0] += 2;
				} else if (isDtextChar(c)) {
					tokenBuffer.append(c);
					pos[0]++;
				} else {
                    throw new IllegalArgumentException(L10N.getString("err.invalid_char_in_domain"));
                }
			}
			if (pos[0] >= length) {
				throw new IllegalArgumentException(L10N.getString("err.unterminated_domain_literal"));
			}
			tokenBuffer.append(']');
			pos[0]++;
		} else {
			parseAtom(input, length, pos, tokenBuffer, smtputf8);
			while (pos[0] < length && input[pos[0]] == '.') {
				tokenBuffer.append('.');
				pos[0]++;
				parseAtom(input, length, pos, tokenBuffer, smtputf8);
			}
		}
	}

	static void parseAtom(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		parseAtom(input, length, pos, tokenBuffer, false);
	}

	static void parseAtom(char[] input, int length, int[] pos, StringBuilder tokenBuffer, boolean smtputf8) {
		int start = tokenBuffer.length();
		while (pos[0] < length && isAtom(input[pos[0]], smtputf8)) {
			tokenBuffer.append(input[pos[0]]);
			pos[0]++;
		}
		if (tokenBuffer.length() == start) {
			throw new IllegalArgumentException(L10N.getString("err.empty_atom"));
		}
	}

	static void parseQuotedString(char[] input, int length, int[] pos, StringBuilder tokenBuffer) {
		if (pos[0] >= length || input[pos[0]] != '"') {
			throw new IllegalArgumentException(L10N.getString("err.expected_quoted_string"));
		}
		tokenBuffer.append('"');
		pos[0]++;
		while (pos[0] < length && input[pos[0]] != '"') {
			char c = input[pos[0]];
			if (c == '\\' && pos[0] + 1 < length) {
				tokenBuffer.append(c);
				tokenBuffer.append(input[pos[0] + 1]);
				pos[0] += 2;
			} else {
				tokenBuffer.append(c);
				pos[0]++;
			}
		}
		if (pos[0] >= length) {
			throw new IllegalArgumentException(L10N.getString("err.unterminated_quoted_string"));
		}
		tokenBuffer.append('"');
		pos[0]++;
	}

	static void skipComment(char[] input, int length, int[] pos) {
		if (pos[0] >= length || input[pos[0]] != '(') {
			return;
		}
		pos[0]++;
		int depth = 1;
		while (pos[0] < length && depth > 0) {
			char c = input[pos[0]];
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (c == '\\' && pos[0] + 1 < length) {
				pos[0]++;
			}
			pos[0]++;
		}
		if (depth > 0) {
			throw new IllegalArgumentException(L10N.getString("err.unterminated_comment"));
		}
	}

	static void skipWhitespaceAndComments(char[] input, int length, int[] pos) {
		while (pos[0] < length) {
			char c = input[pos[0]];
			if (isWhitespace(c)) {
				pos[0]++;
			} else if (c == '(') {
				skipComment(input, length, pos);
			} else {
				break;
			}
		}
	}

	private static int findNextUnquotedChar(char[] input, int length, char target, int start) {
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

	static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n';
	}

	static boolean isAtom(char c) {
		return isAtom(c, false);
	}

	static boolean isAtom(char c, boolean smtputf8) {
		// SMTPUTF8 allows UTF-8 characters (code points > 127) per RFC 6531/6532
		if (smtputf8 && c > 127) {
			return true;
		}
		return c > 32 && c < 127 &&
		       c != '(' && c != ')' && c != '<' && c != '>' && c != '[' && c != ']' &&
		       c != ':' && c != ';' && c != '@' && c != '\\' && c != ',' && c != '.' &&
		       c != '"';
	}

    static boolean isDtextChar(char c) {
        // RFC 5322 dtext = %d33-90 / %d94-126 (printable ASCII except [ ] \)
        return c >= 33 && c <= 126 && c != '[' && c != ']' && c != '\\';
    }

	// ----- ByteBuffer-based parsing (Phase F) -----

	private static int indexOf(ByteBuffer buf, byte target, int from, int to) {
		for (int i = from; i < to; i++) {
			if (buf.get(i) == target) {
				return i;
			}
		}
		return -1;
	}

	/** Advances value.position() past CFWS. */
	private static void skipCfws(ByteBuffer value) {
		int limit = value.limit();
		while (value.position() < limit) {
			byte b = value.get(value.position());
			if (b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == ',') {
				value.position(value.position() + 1);
			} else if (b == '(') {
				skipCommentBytes(value);
			} else {
				break;
			}
		}
	}

	private static void skipCommentBytes(ByteBuffer value) {
		int limit = value.limit();
		if (value.position() >= limit || value.get(value.position()) != '(') {
			return;
		}
		value.position(value.position() + 1);
		int depth = 1;
		while (value.position() < limit && depth > 0) {
			int pos = value.position();
			byte b = value.get(pos);
			if (b == '\\' && pos + 1 < limit) {
				value.position(pos + 2);
				continue;
			}
			if (b == '(') {
				depth++;
			} else if (b == ')') {
				depth--;
			}
			value.position(pos + 1);
		}
	}

	/** Advances value.position() past the group (until and past next ';'). */
	private static void skipGroup(ByteBuffer value) {
		int limit = value.limit();
		while (value.position() < limit && value.get(value.position()) != ';') {
			value.position(value.position() + 1);
		}
		if (value.position() < limit) {
			value.position(value.position() + 1);
		}
	}

	private static boolean isAtext(byte b) {
		if (b <= 32 || b >= 127) {
			return false;
		}
		return b != '(' && b != ')' && b != '<' && b != '>' && b != '[' && b != ']'
			&& b != ':' && b != ';' && b != '@' && b != '\\' && b != ',' && b != '"';
	}

	/** Returns { start, end } for local-part (exclusive end), or null. Uses value.position() and value.limit(); does not advance. */
	private static int[] parseLocalPartRange(ByteBuffer value) {
		int limit = value.limit();
		int pos = value.position();
		if (pos >= limit) {
			return null;
		}
		int start = pos;
		if (value.get(pos) == '"') {
			pos++;
			while (pos < limit) {
				byte b = value.get(pos);
				if (b == '\\' && pos + 1 < limit) {
					pos += 2;
					continue;
				}
				if (b == '"') {
					pos++;
					return new int[] { start + 1, pos - 1 };
				}
				pos++;
			}
			return null;
		}
		while (pos < limit && (isAtext(value.get(pos)) || value.get(pos) == '.')) {
			pos++;
		}
		if (pos == start) {
			return null;
		}
		return new int[] { start, pos };
	}

	/** Returns { start, end } for domain (exclusive end), or null. Uses value.position() and value.limit(); does not advance. */
	private static int[] parseDomainRange(ByteBuffer value) {
		int limit = value.limit();
		int pos = value.position();
		if (pos >= limit) {
			return null;
		}
		int start = pos;
		if (value.get(pos) == '[') {
			pos++;
			while (pos < limit && value.get(pos) != ']') {
				if (value.get(pos) == '\\' && pos + 1 < limit) {
					pos += 2;
					continue;
				}
				pos++;
			}
			if (pos >= limit) {
				return null;
			}
			pos++;
			return new int[] { start, pos };
		}
		while (pos < limit && (isAtext(value.get(pos)) || value.get(pos) == '.')) {
			pos++;
		}
		return new int[] { start, pos };
	}

}
