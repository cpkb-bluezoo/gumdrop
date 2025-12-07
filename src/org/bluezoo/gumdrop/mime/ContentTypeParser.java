/*
 * ContentTypeParser.java
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

import org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for MIME Content-Type header values.
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-5'>RFC 2045</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentTypeParser {

	private ContentTypeParser() {
		// Static utility class
	}

	/**
	 * Parses a Content-Type header value.
	 * @param value the header value string
	 * @return the parsed ContentType, or null if the value is invalid
	 */
	public static ContentType parse(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		// Find the separator between type/subtype and parameters
		int semicolonIndex = value.indexOf(';', 3);
		String typePart = semicolonIndex < 0 ? value : value.substring(0, semicolonIndex);
		String paramsPart = semicolonIndex < 0 ? "" : value.substring(semicolonIndex + 1).trim();

		// Parse type and subtype
		int slashIndex = typePart.indexOf('/', 1);
		if (slashIndex < 0) {
			return null;
		}

		String primaryType = typePart.substring(0, slashIndex).trim();
		String subType = typePart.substring(slashIndex + 1).trim();

		if (!MIMEUtils.isToken(primaryType) || !MIMEUtils.isToken(subType)) {
			return null;
		}

		// Parse parameters
		List<Parameter> parameters = parseParameterList(paramsPart);

		return new ContentType(primaryType, subType, parameters);
	}

	/**
	 * Parses a semicolon-separated list of parameters.
	 */
	static List<Parameter> parseParameterList(String paramsPart) {
		if (paramsPart == null || paramsPart.isEmpty()) {
			return null;
		}

		List<Parameter> parameters = new ArrayList<>();
		int pos = 0;
		int len = paramsPart.length();

		while (pos < len) {
			// Skip whitespace and semicolons
			while (pos < len && (paramsPart.charAt(pos) == ';' || 
								  Character.isWhitespace(paramsPart.charAt(pos)))) {
				pos++;
			}
			if (pos >= len) {
				break;
			}

			// Parse parameter name
			int equalsIndex = paramsPart.indexOf('=', pos);
			if (equalsIndex < 1) {
				// Skip malformed parameter
				int nextSemi = paramsPart.indexOf(';', pos);
				if (nextSemi >= 0) {
					pos = nextSemi;
					continue;
				} else {
					break;
				}
			}

			String name = paramsPart.substring(pos, equalsIndex).trim();
			if (!MIMEUtils.isToken(name)) {
				// Skip malformed parameter
				int nextSemi = paramsPart.indexOf(';', pos);
				if (nextSemi >= 0) {
					pos = nextSemi;
					continue;
				} else {
					break;
				}
			}

			// Parse parameter value
			pos = equalsIndex + 1;
			String paramValue;

			if (pos < len && paramsPart.charAt(pos) == '"') {
				// Quoted-string value
				pos++; // Skip opening quote
				StringBuilder sb = new StringBuilder();
				while (pos < len) {
					char c = paramsPart.charAt(pos);
					if (c == '\\' && pos + 1 < len) {
						sb.append(paramsPart.charAt(pos + 1));
						pos += 2;
					} else if (c == '"') {
						pos++; // Skip closing quote
						break;
					} else {
						sb.append(c);
						pos++;
					}
				}
				paramValue = sb.toString();
			} else {
				// Token value (ends at next semicolon or end)
				int semicolonIdx = paramsPart.indexOf(';', pos);
				if (semicolonIdx < 0) {
					semicolonIdx = len;
				}
				paramValue = paramsPart.substring(pos, semicolonIdx).trim();
				if (!MIMEUtils.isToken(paramValue)) {
					// Invalid token value, skip
					pos = semicolonIdx;
					continue;
				}
				pos = semicolonIdx;
			}

			// Decode RFC 2047 encoded words in parameter value
			paramValue = RFC2047Decoder.decodeEncodedWords(paramValue);
			parameters.add(new Parameter(name, paramValue));
		}

		return parameters.isEmpty() ? null : parameters;
	}

}

