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
import org.bluezoo.gumdrop.mime.MIMEParser;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
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
	 * @param value the header field value bytes (position to limit); position is advanced
	 * @param decoder charset decoder for id-left and id-right segments (reset per use)
	 * @return list of ContentID objects, or null if parsing fails due to syntax errors
	 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5322#section-3.6.4">RFC 5322 Section 3.6.4</a>
	 */
	public static List<ContentID> parseMessageIDList(ByteBuffer value, CharsetDecoder decoder) {
		if (value == null || !value.hasRemaining()) {
			return new ArrayList<>();
		}
		try {
			List<ContentID> messageIDs = new ArrayList<>();
			int limit = value.limit();
			while (value.position() < limit) {
				skipCfws(value);
				if (value.position() >= limit) {
					break;
				}
				if (value.get(value.position()) != '<') {
					break;
				}
				value.position(value.position() + 1);
				int startLocal = value.position();
				if (!advanceUntilAt(value)) {
					break;
				}
				int atIndex = value.position();
				int savedLimit = value.limit();
				value.position(startLocal).limit(atIndex);
				String localPart = MIMEParser.decodeSlice(value, decoder);
				value.limit(savedLimit);
				value.position(atIndex + 1);
				int startDomain = value.position();
				if (!advanceUntilGt(value)) {
					break;
				}
				int gtIndex = value.position();
				value.position(startDomain).limit(gtIndex);
				String domain = MIMEParser.decodeSlice(value, decoder);
				value.limit(savedLimit);
				value.position(gtIndex + 1);
				boolean smtputf8 = isSmtpUtf8(decoder);
				if (!isValidIdLeft(localPart, smtputf8) || !isValidIdRight(domain, smtputf8)) {
					break;
				}
				messageIDs.add(new ContentID(localPart, domain));
			}
			return messageIDs;
		} catch (Exception e) {
			return null;
		}
	}

	/** Advance position until '@'. Returns false if EOF before '@'. */
	private static boolean advanceUntilAt(ByteBuffer value) {
		int limit = value.limit();
		while (value.position() < limit) {
			if (value.get(value.position()) == '@') {
				return true;
			}
			value.position(value.position() + 1);
		}
		return false;
	}

	/** Advance position until '>', skipping domain-literal [...] content. Returns false if EOF before '>'. */
	private static boolean advanceUntilGt(ByteBuffer value) {
		int limit = value.limit();
		while (value.position() < limit) {
			byte b = value.get(value.position());
			if (b == '[') {
				value.position(value.position() + 1);
				while (value.position() < limit) {
					byte c = value.get(value.position());
					if (c == '\\' && value.position() + 1 < limit) {
						value.position(value.position() + 2);
						continue;
					}
					if (c == ']') {
						value.position(value.position() + 1);
						break;
					}
					value.position(value.position() + 1);
				}
				continue;
			}
			if (b == '>') {
				return true;
			}
			value.position(value.position() + 1);
		}
		return false;
	}

	/** True when decoder is UTF-8 (SMTPUTF8 mode): allow non-ASCII in tokens. */
	private static boolean isSmtpUtf8(CharsetDecoder decoder) {
		return decoder != null && decoder.charset().equals(StandardCharsets.UTF_8);
	}

	/** Validate id-left (dot-atom): atext or '.'; no leading/trailing dot; at least one atext. */
	private static boolean isValidIdLeft(String s, boolean smtputf8) {
		if (s == null || s.isEmpty()) {
			return false;
		}
		boolean needAtext = true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '.') {
				if (needAtext) {
					return false;
				}
				needAtext = true;
			} else if (isAtext(c, smtputf8)) {
				needAtext = false;
			} else {
				return false;
			}
		}
		return !needAtext;
	}

	/** Validate id-right: empty, or dot-atom, or domain-literal [...]. */
	private static boolean isValidIdRight(String s, boolean smtputf8) {
		if (s == null) {
			return false;
		}
		if (s.isEmpty()) {
			return true;
		}
		if (s.charAt(0) == '[') {
			if (s.length() < 2 || s.charAt(s.length() - 1) != ']') {
				return false;
			}
			for (int i = 1; i < s.length() - 1; i++) {
				char c = s.charAt(i);
				if (c == '\\' && i + 1 < s.length() - 1) {
					i++;
					continue;
				}
				if (!isDtext(c, smtputf8)) {
					return false;
				}
			}
			return true;
		}
		boolean needAtext = true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '.') {
				if (needAtext) {
					return false;
				}
				needAtext = true;
			} else if (isAtext(c, smtputf8)) {
				needAtext = false;
			} else {
				return false;
			}
		}
		return !needAtext;
	}

	private static boolean isAtext(char c, boolean smtputf8) {
		if (smtputf8 && c > 127) {
			return true;
		}
		if (c <= 32 || c >= 127) {
			return false;
		}
		return c != '(' && c != ')' && c != '<' && c != '>' && c != '[' && c != ']'
			&& c != ':' && c != ';' && c != '@' && c != '\\' && c != ',' && c != '"';
	}

	private static boolean isDtext(char c, boolean smtputf8) {
		if (smtputf8 && c > 127) {
			return true;
		}
		if (c <= 32 || c >= 127) {
			return false;
		}
		return c != '[' && c != ']' && c != '\\';
	}

	/** Advances value.position() past CFWS (whitespace, commas, comments). */
	private static void skipCfws(ByteBuffer value) {
		int limit = value.limit();
		while (value.position() < limit) {
			byte b = value.get(value.position());
			if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
				value.position(value.position() + 1);
			} else if (b == ',') {
				value.position(value.position() + 1);
			} else if (b == '(') {
				skipComment(value);
			} else {
				break;
			}
		}
	}

	private static void skipComment(ByteBuffer value) {
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
				value.position(pos + 1);
			} else if (b == ')') {
				depth--;
				value.position(pos + 1);
			} else {
				value.position(pos + 1);
			}
		}
	}

}
