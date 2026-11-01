/*
 * ElementParser.java
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


package org.bluezoo.gumdrop.mime.rfc5322;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.rfc2047.Rfc2047Decoder;

/**
 * Reads one element of a structured RFC 5322 header: a mailbox, the name of
 * a group, a message-id. The caller has already cut the element out of the
 * header, so what is read here is bounded by the length of one element.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // uses value classes that are deprecated for removal
final class ElementParser {

	/** A mailbox read from one element of an address list. */
	static final class Mailbox {

		final String displayName;
		final String localPart;
		final String domain;

		/** True if it was written in an obsolete syntax. */
		final boolean obsolete;

		Mailbox(String displayName, String localPart, String domain, boolean obsolete) {
			this.displayName = displayName;
			this.localPart = localPart;
			this.domain = domain;
			this.obsolete = obsolete;
		}

	}

	private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

	private ElementParser() {
	}

	/**
	 * Decodes the bytes of an element to text. Valid UTF-8 is read as UTF-8
	 * (as is all of an SMTPUTF8 message); anything else is read as
	 * windows-1252 (ISO-8859-1 in an SMTPUTF8 message).
	 * @param bytes the bytes
	 * @param length how many of them there are
	 * @param utf8Headers true in an SMTPUTF8 message
	 * @return the text
	 */
	static String decode(byte[] bytes, int length, boolean utf8Headers) {
		boolean ascii = true;
		for (int i = 0; i < length; i++) {
			if (bytes[i] < 0) {
				ascii = false;
				break;
			}
		}
		if (ascii) {
			return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
		}
		CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			return strict.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
		} catch (CharacterCodingException e) {
			Charset fallback = utf8Headers ? StandardCharsets.ISO_8859_1 : WINDOWS_1252;
			CharsetDecoder lenient = fallback.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
			try {
				CharBuffer out = lenient.decode(ByteBuffer.wrap(bytes, 0, length));
				return out.toString();
			} catch (CharacterCodingException impossible) {
				return new String(bytes, 0, length, fallback);
			}
		}
	}

	/**
	 * Reads one mailbox: {@code [display-name] <local@domain>} or
	 * {@code local@domain}, with comments allowed, or an obsolete form
	 * (a display name with dots, a source route). The local part and domain
	 * have to be valid whichever way it is read.
	 * @param text the text of the element
	 * @param utf8 true to allow UTF-8 in addresses (RFC 6532)
	 * @return the mailbox, or null if the text is not one
	 */
	static Mailbox mailbox(String text, boolean utf8) {
		text = text.trim();
		if (text.isEmpty()) {
			return null;
		}
		char[] input = text.toCharArray();
		int length = input.length;
		int[] pos = new int[] { 0 };
		try {
			EmailAddress address = EmailAddressParser.parseIndividualAddress(
				input, length, pos, new StringBuilder(64), utf8);
			if (address != null) {
				EmailAddressParser.skipWhitespaceAndComments(input, length, pos);
				if (pos[0] == length && standardLocalPart(address.getLocalPart(), utf8)
						&& validParts(address.getLocalPart(), address.getDomain(), utf8)) {
					return new Mailbox(decodeWords(address.getDisplayName()),
						address.getLocalPart(), address.getDomain(), false);
				}
				// words joined by dots: valid, but only in the obsolete syntax
				if (pos[0] == length
						&& EmailAddressParser.isValidObsoleteLocalPart(address.getLocalPart(), utf8)
						&& EmailAddressParser.isValidDomain(address.getDomain(), utf8)) {
					return new Mailbox(decodeWords(address.getDisplayName()),
						address.getLocalPart(), address.getDomain(), true);
				}
			}
		} catch (IllegalArgumentException e) {
			// not in the current syntax: try the obsolete forms
		}
		return obsoleteMailbox(text, utf8);
	}

	/**
	 * Reads the name of a group, from the text before its colon.
	 * @param text the text
	 * @param utf8 true to allow UTF-8 (RFC 6532)
	 * @return the decoded name, or null if the text is not a name
	 */
	static String groupName(String text, boolean utf8) {
		text = text.trim();
		char[] input = text.toCharArray();
		int length = input.length;
		int[] pos = new int[] { 0 };
		try {
			String name = EmailAddressParser.parseDisplayName(input, length, pos, new StringBuilder(64), utf8);
			if (name == null) {
				return null;
			}
			EmailAddressParser.skipWhitespaceAndComments(input, length, pos);
			return pos[0] == length ? decodeWords(name) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Reads the text between the angle brackets of a message-id.
	 * @param inner the text
	 * @param utf8 true to allow UTF-8 (RFC 6532)
	 * @return the local part and domain, or null if it is not a message-id
	 */
	static String[] messageID(String inner, boolean utf8) {
		int at = inner.indexOf('@');
		if (at < 0) {
			return null;
		}
		String local = inner.substring(0, at);
		String domain = inner.substring(at + 1);
		if (MessageIDParser.isValidIdLeft(local, utf8) && MessageIDParser.isValidIdRight(domain, utf8)) {
			return new String[] { local, domain };
		}
		return null;
	}

	/**
	 * Reads a message-id in an obsolete form: with or without angle brackets,
	 * comments allowed.
	 * @param text the text
	 * @return the local part and domain, or null if it is not a message-id
	 */
	static String[] obsoleteMessageID(String text) {
		ContentID id = ObsoleteParserUtils.parseObsoleteMessageID(text);
		return id == null ? null : new String[] { id.getLocalPart(), id.getDomain() };
	}

	private static String decodeWords(String name) {
		if (name == null || name.indexOf("=?") < 0) {
			return name;
		}
		return Rfc2047Decoder.decodeEncodedWords(name);
	}

	/**
	 * Returns whether a local part is in the current syntax: a dot-atom or
	 * one quoted-string, not words joined by dots.
	 */
	private static boolean standardLocalPart(String local, boolean utf8) {
		if (local.startsWith("\"")) {
			for (int i = 1; i < local.length(); i++) {
				char c = local.charAt(i);
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					return i == local.length() - 1;
				}
			}
			return false;
		}
		return true;
	}

	private static boolean validParts(String local, String domain, boolean utf8) {
		return EmailAddressParser.isValidLocalPart(local, utf8)
			&& EmailAddressParser.isValidDomain(domain, utf8);
	}

	/**
	 * The obsolete forms: a display name with characters a phrase does not
	 * allow ({@code John Q. Public <jqp@example.org>}), and a source route
	 * ({@code <@a.example,@b.example:user@c.example>}).
	 */
	private static Mailbox obsoleteMailbox(String text, boolean utf8) {
		String uncommented = stripComments(text);
		if (uncommented == null) {
			return null;
		}
		text = uncommented.trim();
		String display;
		String inner;
		int open = text.indexOf('<');
		int close = text.lastIndexOf('>');
		if (open >= 0 && close >= 0) {
			if (open >= close || !text.substring(close + 1).trim().isEmpty()) {
				return null;
			}
			display = text.substring(0, open).trim();
			inner = text.substring(open + 1, close).trim();
		} else if (open < 0 && close < 0) {
			display = "";
			inner = text;
		} else {
			return null;
		}
		if (inner.startsWith("@")) {
			// a route: skip to the colon that ends it
			int colon = inner.lastIndexOf(':');
			if (colon < 0) {
				return null;
			}
			inner = inner.substring(colon + 1).trim();
		}
		for (int i = 0; i < inner.length(); i++) {
			if (Character.isWhitespace(inner.charAt(i))) {
				return null;
			}
		}
		int at = inner.lastIndexOf('@');
		if (at < 0) {
			return null;
		}
		String local = inner.substring(0, at);
		String domain = inner.substring(at + 1);
		if (!validParts(local, domain, utf8)
				&& !(EmailAddressParser.isValidObsoleteLocalPart(local, utf8)
					&& EmailAddressParser.isValidDomain(domain, utf8))) {
			return null;
		}
		// a bare address is only obsolete if it is a route
		if (display.isEmpty() && !text.startsWith("@") && !text.startsWith("<")) {
			return null;
		}
		display = cleanName(display);
		return new Mailbox(display.isEmpty() ? null : decodeWords(display), local, domain, true);
	}

	/** A display name without its quotes and quoted-pairs. */
	private static String cleanName(String name) {
		name = name.trim();
		if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
			name = name.substring(1, name.length() - 1);
		}
		return name.replace("\\\"", "\"").replace("\\\\", "\\").trim();
	}

	/**
	 * Removes the comments from text.
	 * @return the text, or null if a comment is not closed
	 */
	private static String stripComments(String text) {
		StringBuilder out = new StringBuilder(text.length());
		int depth = 0;
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length()) {
				if (depth == 0) {
					out.append(c).append(text.charAt(i + 1));
				}
				i++;
			} else if (quoted) {
				if (c == '"') {
					quoted = false;
				}
				out.append(c);
			} else if (depth > 0) {
				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
				}
			} else if (c == '(') {
				depth = 1;
			} else {
				if (c == '"') {
					quoted = true;
				}
				out.append(c);
			}
		}
		return depth == 0 ? out.toString() : null;
	}

}
