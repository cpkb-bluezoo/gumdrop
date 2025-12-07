/*
 * ContentDispositionParser.java
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

import java.util.List;

/**
 * Parser for MIME Content-Disposition header values.
 * @see <a href='https://www.ietf.org/rfc/rfc2183.txt'>RFC 2183</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentDispositionParser {

	private ContentDispositionParser() {
		// Static utility class
	}

	/**
	 * Parses a Content-Disposition header value.
	 * @param value the header value string
	 * @return the parsed ContentDisposition, or null if the value is invalid
	 */
	public static ContentDisposition parse(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		// Find the separator between disposition type and parameters
		int semicolonIndex = value.indexOf(';', 3);
		String dispositionPart = semicolonIndex < 0 ? value : value.substring(0, semicolonIndex);
		String paramsPart = semicolonIndex < 0 ? "" : value.substring(semicolonIndex + 1).trim();

		String dispositionType = dispositionPart.trim();
		if (!MIMEUtils.isToken(dispositionType)) {
			return null;
		}

		// Parse parameters (reuse the implementation from ContentTypeParser)
		List<Parameter> parameters = ContentTypeParser.parseParameterList(paramsPart);

		return new ContentDisposition(dispositionType, parameters);
	}

}

