/*
 * MIMEVersion.java
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
 * This enum represents a value for the MIME-Version header field.
 * There is only one known value for this field.
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-4'>RFC 2045</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum MIMEVersion {

	VERSION_1_0("1.0");

	private final String value;

	private MIMEVersion(String value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return value;
	}

	/**
	 * Parses a MIME-Version header value.
	 * @param s the header value string
	 * @return the MIMEVersion, or null if not recognized
	 */
	public static MIMEVersion parse(String s) {
		if (s == null) {
			return null;
		}
		// Be lenient with whitespace
		s = s.trim();
		return "1.0".equals(s) ? VERSION_1_0 : null;
	}

}

