/*
 * Parameter.java
 * Copyright (C) 2005, 2013 Chris Burdess
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

import java.nio.charset.StandardCharsets;

/**
 * A parameter in a structured MIME header value such as Content-Type or
 * Content-Disposition. It consists of a name and a value.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Parameter {

	private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

	private final String name;
	private final String value;

	/**
	 * Constructor for a new parameter.
	 * @param name the name of the parameter
	 * @param value the parameter value
	 * @exception NullPointerException if name or value are null
	 */
	public Parameter(String name, String value) {
		if (name == null || value == null) {
			throw new NullPointerException("name and value must not be null");
		}
		this.name = name;
		this.value = value;
	}

	/**
	 * Returns the name of this parameter. This should be compared case
	 * insensitively with other parameter names.
	 * @return the parameter name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the fully decoded value of this parameter.
	 * @return the parameter value
	 */
	public String getValue() {
		return value;
	}

	@Override
	public int hashCode() {
		return name.toLowerCase().hashCode() + value.hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof Parameter)) {
			return false;
		}
		Parameter o = (Parameter) other;
		return name.equalsIgnoreCase(o.name) && value.equals(o.value);
	}

	/**
	 * Returns a human-readable string representation.
	 * For wire format serialization, use {@link #toHeaderValue()}.
	 */
	@Override
	public String toString() {
		return name + "=" + value;
	}

	/**
	 * Serializes this parameter to RFC 2231 compliant header format.
	 * <ul>
	 * <li>ASCII token values: {@code name=value}</li>
	 * <li>ASCII values with specials: {@code name="value"} with escaping</li>
	 * <li>Non-ASCII values: {@code name*=UTF-8''percent-encoded}</li>
	 * </ul>
	 * @return the serialized parameter suitable for inclusion in a header
	 * @see <a href='https://datatracker.ietf.org/doc/html/rfc2231'>RFC 2231</a>
	 */
	public String toHeaderValue() {
		if (isAscii(value)) {
			// ASCII value - use traditional format
			if (MIMEUtils.isToken(value)) {
				return name + "=" + value;
			} else {
				return name + "=\"" + escapeQuotedString(value) + "\"";
			}
		} else {
			// Non-ASCII value - use RFC 2231 extended format
			return name + "*=UTF-8''" + percentEncode(value);
		}
	}

	/**
	 * Checks if a string contains only ASCII characters (0-127).
	 */
	private static boolean isAscii(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) > 127) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Escapes a string for use in a quoted-string.
	 * Backslash and double-quote need to be escaped.
	 */
	private static String escapeQuotedString(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' || c == '"') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/**
	 * Percent-encodes a string as UTF-8 bytes per RFC 2231.
	 * Safe characters (alphanumerics and some specials) are not encoded.
	 */
	private static String percentEncode(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		StringBuilder sb = new StringBuilder(bytes.length * 3);
		for (byte b : bytes) {
			int ub = b & 0xFF;
			if (isAttrChar(ub)) {
				sb.append((char) ub);
			} else {
				sb.append('%');
				sb.append(HEX_DIGITS[(ub >> 4) & 0x0F]);
				sb.append(HEX_DIGITS[ub & 0x0F]);
			}
		}
		return sb.toString();
	}

	/**
	 * Checks if a byte is an attr-char per RFC 2231.
	 * attr-char = ALPHA / DIGIT / "!" / "#" / "$" / "&" / "+" / "-" / "." /
	 *             "^" / "_" / "`" / "|" / "~"
	 */
	private static boolean isAttrChar(int c) {
		return (c >= 'A' && c <= 'Z') ||
			   (c >= 'a' && c <= 'z') ||
			   (c >= '0' && c <= '9') ||
			   c == '!' || c == '#' || c == '$' || c == '&' || c == '+' ||
			   c == '-' || c == '.' || c == '^' || c == '_' || c == '`' ||
			   c == '|' || c == '~';
	}

}
