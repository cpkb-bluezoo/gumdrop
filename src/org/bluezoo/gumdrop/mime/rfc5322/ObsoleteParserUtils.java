/*
 * ObsoleteParserUtils.java
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
import org.bluezoo.gumdrop.mime.MIMEParser;
import org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for parsing obsolete email formats from legacy RFCs.
 *
 * This class consolidates parsing logic for obsolete syntax patterns that were
 * valid in older RFCs but are considered obsolete in RFC 5322:
 *
 * - Obsolete email address formats (RFC 5322 section 4.5)
 * - Obsolete message-ID formats
 *
 * The parsers attempt to extract valid information from these obsolete formats
 * and convert them to modern objects.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ObsoleteParserUtils {

	// ===========================================
	// OBSOLETE ADDRESS PARSING METHODS
	// ===========================================

	/**
	 * Attempt to parse obsolete address syntax from a ByteBuffer (comma-separated segments).
	 * Decodes each segment with RFC 2047 and parses; avoids building a full-value String.
	 *
	 * @param value the address header value bytes (position to limit); not consumed
	 * @param decoder charset decoder for segment decoding
	 * @return List of EmailAddress objects if obsolete syntax was successfully parsed, null otherwise
	 */
	public static List<EmailAddress> parseObsoleteAddressList(ByteBuffer value, CharsetDecoder decoder) {
		if (value == null || !value.hasRemaining()) {
			return null;
		}
		try {
			List<EmailAddress> addresses = new ArrayList<>();
			int limit = value.limit();
			while (value.position() < limit) {
				int comma = MIMEParser.indexOf(value, (byte) ',');
				int end = comma >= 0 ? comma : limit;
				if (end > value.position()) {
					ByteBuffer segment = value.duplicate();
					segment.limit(end);
					String part = RFC2047Decoder.decodeUnstructuredHeaderValue(segment, decoder, true, false);
					if (!part.trim().isEmpty()) {
						EmailAddress addr = parseObsoleteAddress(part);
						if (addr != null) {
							addresses.add(addr);
						}
					}
				}
				value.position(comma >= 0 ? comma + 1 : limit);
			}
			return addresses.isEmpty() ? null : addresses;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Parse a single address that may contain obsolete syntax.
	 */
	private static EmailAddress parseObsoleteAddress(String addressText) {
		addressText = addressText.trim();

		// Check for source routing: @domain1,@domain2:user@domain3
		if (addressText.contains(":") && addressText.startsWith("@")) {
			return parseSourceRoutedAddress(addressText);
		}

		// Check for other obsolete patterns here...
		// For now, try basic fallback parsing
		return parseBasicObsoleteAddress(addressText);
	}

	/**
	 * Parse source-routed addresses: @domain1,@domain2:user@domain3
	 * Extract the final destination address and ignore the routing information.
	 */
	private static EmailAddress parseSourceRoutedAddress(String addressText) {
		int colonIndex = addressText.lastIndexOf(':');
		if (colonIndex == -1 || colonIndex == addressText.length() - 1) {
			return null;
		}

		// Extract the final destination address after the last colon
		String destinationAddress = addressText.substring(colonIndex + 1).trim();

		// Validate that it looks like a reasonable email address
		if (destinationAddress.contains("@") && !destinationAddress.contains(" ")) {
			// Try to parse as a basic address
			return parseBasicEmailAddress(destinationAddress);
		}

		return null;
	}

	/**
	 * Parse basic obsolete address formats that don't follow strict RFC 5322 rules
	 * but can still be reasonably interpreted as email addresses.
	 */
	private static EmailAddress parseBasicObsoleteAddress(String addressText) {
		// Handle display name variations
		if (addressText.contains("<") && addressText.contains(">")) {
			return parseDisplayNameAddress(addressText);
		}

		// Handle bare email address
		if (addressText.contains("@")) {
			return parseBasicEmailAddress(addressText);
		}

		return null;
	}

	/**
	 * Parse address with display name in obsolete format.
	 */
	private static EmailAddress parseDisplayNameAddress(String addressText) {
		int openAngle = addressText.indexOf('<');
		int closeAngle = addressText.lastIndexOf('>');

		if (openAngle == -1 || closeAngle == -1 || closeAngle <= openAngle) {
			return null;
		}

		String displayName = addressText.substring(0, openAngle).trim();
		String emailAddress = addressText.substring(openAngle + 1, closeAngle).trim();

		// Clean up display name (remove quotes if present)
		displayName = cleanDisplayName(displayName);

		// Validate email address
		if (!emailAddress.contains("@")) {
			return null;
		}

		// Split email address into local part and domain
		int atIndex = emailAddress.lastIndexOf('@');
		if (atIndex <= 0 || atIndex >= emailAddress.length() - 1) {
			return null;
		}

		String localPart = emailAddress.substring(0, atIndex);
		String domain = emailAddress.substring(atIndex + 1);

		return new EmailAddress(displayName.isEmpty() ? null : displayName, localPart, domain, false);
	}

	/**
	 * Parse a basic email address (user@domain format).
	 */
	private static EmailAddress parseBasicEmailAddress(String emailAddress) {
		emailAddress = emailAddress.trim();

		// Basic validation
		int atIndex = emailAddress.lastIndexOf('@');
		if (atIndex <= 0 || atIndex >= emailAddress.length() - 1) {
			return null;
		}

		String localPart = emailAddress.substring(0, atIndex);
		String domain = emailAddress.substring(atIndex + 1);

		// Additional validation could be added here for obsolete domain formats

		return new EmailAddress(null, localPart, domain, false);
	}

	/**
	 * Clean up display name by removing outer quotes and handling obsolete quoting.
	 */
	private static String cleanDisplayName(String displayName) {
		displayName = displayName.trim();

		// Remove outer quotes
		if (displayName.startsWith("\"") && displayName.endsWith("\"") && displayName.length() >= 2) {
			displayName = displayName.substring(1, displayName.length() - 1);
		}

		// Handle obsolete escaped characters or linear whitespace
		// This is a simplified approach - full RFC implementation would be more complex
		displayName = displayName.replace("\\\"", "\"").replace("\\\\", "\\");

		return displayName.trim();
	}

	// ===========================================
	// OBSOLETE MESSAGE-ID PARSING METHODS
	// ===========================================

	/**
	 * Attempt to parse obsolete message-ID syntax from a ByteBuffer (whitespace/comma-separated segments).
	 * Decodes each segment with the given decoder; avoids building a full-value String.
	 *
	 * @param value the message-ID header value bytes (position to limit); not consumed
	 * @param decoder charset decoder for segment decoding
	 * @return List of ContentID objects if obsolete syntax was successfully parsed, null otherwise
	 */
	public static List<ContentID> parseObsoleteMessageIDList(ByteBuffer value, CharsetDecoder decoder) {
		if (value == null || !value.hasRemaining()) {
			return null;
		}
		try {
			List<ContentID> messageIDs = new ArrayList<>();
			int limit = value.limit();
			int segmentStart = value.position();
			int pos = segmentStart;
			while (pos <= limit) {
				byte b = pos < limit ? value.get(pos) : (byte) ' ';
				boolean isSep = b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == ',';
				if (isSep || pos == limit) {
					if (pos > segmentStart) {
						int savedLimit = value.limit();
						value.position(segmentStart).limit(pos);
						String part = MIMEParser.decodeSlice(value, decoder);
						value.limit(savedLimit);
						if (!part.trim().isEmpty()) {
							ContentID id = parseObsoleteMessageID(part);
							if (id != null) {
								messageIDs.add(id);
							}
						}
					}
					segmentStart = pos + 1;
				}
				pos++;
			}
			value.position(limit);
			return messageIDs.isEmpty() ? null : messageIDs;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Parse a single message-ID that may contain obsolete syntax.
	 */
	private static ContentID parseObsoleteMessageID(String idText) {
		idText = idText.trim();

		// Remove comments if present: (comment)msg@domain.com(comment)
		idText = removeComments(idText);

		// Check if it already has angle brackets (partial compliance)
		if (idText.startsWith("<") && idText.endsWith(">")) {
			String bareId = idText.substring(1, idText.length() - 1);
			return parseBasicMessageID(bareId);
		}

		// Handle bare message-ID without angle brackets (obsolete syntax)
		return parseBasicMessageID(idText);
	}

	/**
	 * Remove RFC 822 style comments from message-ID text.
	 * Comments are enclosed in parentheses: (comment text)
	 */
	private static String removeComments(String text) {
		StringBuilder result = new StringBuilder();
		int depth = 0;

		for (char c : text.toCharArray()) {
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (depth == 0) {
				result.append(c);
			}
		}

		return result.toString().trim();
	}

	/**
	 * Parse a basic message-ID in user@domain format.
	 * This handles the core message-ID without angle brackets or comments.
	 */
	private static ContentID parseBasicMessageID(String messageId) {
		messageId = messageId.trim();

		// Basic validation: must contain @ symbol
		int atIndex = messageId.lastIndexOf('@');
		if (atIndex <= 0 || atIndex >= messageId.length() - 1) {
			return null;
		}

		String localPart = messageId.substring(0, atIndex);
		String domainPart = messageId.substring(atIndex + 1);

		// Validate local part (basic check - RFC allows more complex rules)
		if (localPart.isEmpty() || !isValidLocalPart(localPart)) {
			return null;
		}

		// Validate domain part (basic check)
		if (domainPart.isEmpty() || !isValidDomainPart(domainPart)) {
			return null;
		}

		// Construct the ContentID
		return new ContentID(localPart, domainPart);
	}

	/**
	 * Basic validation for message-ID local part.
	 * This is simplified - full RFC rules are more complex.
	 */
	private static boolean isValidLocalPart(String localPart) {
		// Must not be empty
		if (localPart.isEmpty()) {
			return false;
		}

		// Check for obviously invalid characters (basic validation)
		for (char c : localPart.toCharArray()) {
			// Allow alphanumeric, dots, hyphens, underscores and some special chars
			if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_'
				&& c != '+' && c != '=' && c != '#' && c != '$' && c != '%') {
				return false;
			}
		}

		return true;
	}

	/**
	 * Basic validation for message-ID domain part.
	 */
	private static boolean isValidDomainPart(String domainPart) {
		// Must not be empty
		if (domainPart.isEmpty()) {
			return false;
		}

		// Must contain at least one dot (basic domain validation)
		if (!domainPart.contains(".")) {
			return false;
		}

		// Check for obviously invalid characters
		for (char c : domainPart.toCharArray()) {
			if (!Character.isLetterOrDigit(c) && c != '.' && c != '-') {
				return false;
			}
		}

		// Must not start or end with dot or hyphen
		if (domainPart.startsWith(".") || domainPart.endsWith(".")
			|| domainPart.startsWith("-") || domainPart.endsWith("-")) {
			return false;
		}

		return true;
	}

}

