/*
 * HeaderValueBuilder.java
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


package org.bluezoo.gumdrop.mime;

import java.nio.charset.StandardCharsets;

/**
 * Builds the text of a Content-Type or Content-Disposition header value from
 * the parser's head and parameter events, for a handler that needs the value
 * as text (for example to keep it as a header). It is the streaming
 * replacement for {@code toHeaderValue()} of the value classes: ASCII token
 * values are written as they are, other ASCII values quoted, and values with
 * non-ASCII characters as RFC 2231 extended parameters.
 *
 * <p>A handler calls {@link #start(String)} from startContentType or
 * startContentDisposition, the parameter methods from the parameter events,
 * and {@link #value()} from the end event.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HeaderValueBuilder {

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private final StringBuilder text = new StringBuilder();
	private final StringBuilder value = new StringBuilder();
	private String name;

	/**
	 * Starts a value with its head, such as {@code text/plain} or {@code attachment}.
	 * @param head the head
	 */
	public void start(String head) {
		text.setLength(0);
		text.append(head);
	}

	/**
	 * Starts a parameter.
	 * @param name the parameter name
	 */
	public void startParameter(String name) {
		this.name = name;
		value.setLength(0);
	}

	/**
	 * Adds some of the value of the current parameter.
	 * @param content the text
	 */
	public void parameterContent(String content) {
		value.append(content);
	}

	/** Ends the current parameter, adding it to the value. */
	public void endParameter() {
		text.append("; ");
		String v = value.toString();
		if (isAscii(v)) {
			if (MimeUtils.isToken(v)) {
				text.append(name).append('=').append(v);
			} else {
				text.append(name).append("=\"");
				for (int i = 0; i < v.length(); i++) {
					char c = v.charAt(i);
					if (c == '\\' || c == '"') {
						text.append('\\');
					}
					text.append(c);
				}
				text.append('"');
			}
		} else {
			text.append(name).append("*=UTF-8''");
			for (byte b : v.getBytes(StandardCharsets.UTF_8)) {
				int ub = b & 0xFF;
				if (isAttrChar(ub)) {
					text.append((char) ub);
				} else {
					text.append('%').append(HEX[ub >> 4]).append(HEX[ub & 0x0F]);
				}
			}
		}
	}

	/**
	 * Returns the value built.
	 * @return the header value
	 */
	public String value() {
		return text.toString();
	}

	private static boolean isAscii(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) > 127) {
				return false;
			}
		}
		return true;
	}

	/** attr-char of RFC 2231. */
	private static boolean isAttrChar(int c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
			|| c == '!' || c == '#' || c == '$' || c == '&' || c == '+' || c == '-'
			|| c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~';
	}

}
