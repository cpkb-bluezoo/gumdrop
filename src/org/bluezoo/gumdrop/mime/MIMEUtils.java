/*
 * MIMEUtils.java
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

/**
 * Utility methods for MIME parsing and validation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MIMEUtils {

	private MIMEUtils() {
		// Static utility class
	}

	/**
	 * Checks if a string is a valid RFC 2045 token.
	 * A token is 1 or more characters from the set:
	 * {@code !#$%&amp;'*+-.^_`{|}~0-9A-Za-z}
	 * @param s the string to check
	 * @return true if the string is a valid token
	 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-5.1'>RFC 2045</a>
	 */
	public static boolean isToken(String s) {
		if (s == null || s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			if (!isTokenChar(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks if a character is valid in an RFC 2045 token.
	 * @param c the character to check
	 * @return true if the character is valid in a token
	 */
	public static boolean isTokenChar(char c) {
		return (c >= '0' && c <= '9') ||
			   (c >= 'A' && c <= 'Z') ||
			   (c >= 'a' && c <= 'z') ||
			   c == '!' || c == '#' || c == '$' || c == '%' || c == '&' ||
			   c == '\'' || c == '*' || c == '+' || c == '-' || c == '.' ||
			   c == '^' || c == '_' || c == '`' || c == '{' || c == '|' ||
			   c == '}' || c == '~';
	}

	/**
	 * Checks if a character is a MIME tspecial (RFC 2045).
	 * These characters must be quoted in parameter values.
	 * @param c the character to check
	 * @return true if the character is a tspecial
	 */
	public static boolean isSpecial(char c) {
		return c == '(' || c == ')' || c == '<' || c == '>' || c == '@' ||
			   c == ',' || c == ';' || c == ':' || c == '\\' || c == '"' ||
			   c == '/' || c == '[' || c == ']' || c == '?' || c == '=';
	}

	/**
	 * Validates MIME boundary strings according to RFC 2046.
	 * A boundary is 1-70 characters from the set:
	 * {@code 0-9A-Za-z'()+_,-./:=?}
	 * @param boundary the boundary string to validate
	 * @return true if valid, false otherwise
	 * @see <a href='https://www.rfc-editor.org/rfc/rfc2046#section-5.1.1'>RFC 2046</a>
	 */
	public static boolean isValidBoundary(String boundary) {
		if (boundary == null || boundary.isEmpty() || boundary.length() > 70) {
			return false;
		}
		for (int i = 0; i < boundary.length(); i++) {
			if (!isBoundaryChar(boundary.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks if a character is valid in a MIME boundary.
	 * @param c the character to check
	 * @return true if valid in a boundary
	 */
	public static boolean isBoundaryChar(char c) {
		return (c >= '0' && c <= '9') ||
			   (c >= 'A' && c <= 'Z') ||
			   (c >= 'a' && c <= 'z') ||
			   c == '\'' || c == '(' || c == ')' || c == '+' ||
			   c == '_' || c == ',' || c == '-' || c == '.' ||
			   c == '/' || c == ':' || c == '=' || c == '?';
	}

}

