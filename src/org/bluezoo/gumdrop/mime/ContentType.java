/*
 * ContentType.java
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A MIME Content-Type value.
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-5'>RFC 2045</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentType {

	private final String primaryType;
	private final String subType;
	private final List<Parameter> parameters;
	private final Map<String, String> parameterMap;

	/**
	 * Constructor.
	 * @param primaryType the primary component of the media type, e.g.
	 * "text", "multipart", "image", or "application". May not be null
	 * @param subType the subtype of the media type, e.g. "plain" for
	 * text/plain or "mixed" for multipart/mixed. May not be null
	 * @param parameters optional list of parameters for the content type,
	 * in order. May be null
	 * @exception NullPointerException if primaryType or subType are null
	 */
	public ContentType(String primaryType, String subType, List<Parameter> parameters) {
		if (primaryType == null || subType == null) {
			throw new NullPointerException("primaryType and subType must not be null");
		}
		this.primaryType = primaryType;
		this.subType = subType;
		if (parameters != null && !parameters.isEmpty()) {
			this.parameters = Collections.unmodifiableList(parameters);
			Map<String, String> map = new HashMap<>();
			for (Parameter parameter : parameters) {
				String key = parameter.getName().toLowerCase();
				if (!map.containsKey(key)) {
					map.put(key, parameter.getValue());
				}
			}
			this.parameterMap = Collections.unmodifiableMap(map);
		} else {
			this.parameters = null;
			this.parameterMap = null;
		}
	}

	/**
	 * Returns the primary type for this content type, e.g. "text" or
	 * "image".
	 * @return the primary type (the part of the media type before the '/')
	 */
	public String getPrimaryType() {
		return primaryType;
	}

	/**
	 * Indicates whether this content type matches the specified primary
	 * type. Comparisons are case insensitive.
	 * @param primaryType the primary media type to test
	 * @return true if this content-type matches, false otherwise
	 */
	public boolean isPrimaryType(String primaryType) {
		return this.primaryType.equalsIgnoreCase(primaryType);
	}

	/**
	 * Returns the subtype for this content type, e.g. "plain" or "jpeg".
	 * @return the subtype (the part of the media type after the '/')
	 */
	public String getSubType() {
		return subType;
	}

	/**
	 * Indicates whether this content type matches the specified subtype.
	 * Comparisons are case insensitive.
	 * @param subType the media subtype to test
	 * @return true if this content-type matches, false otherwise
	 */
	public boolean isSubType(String subType) {
		return this.subType.equalsIgnoreCase(subType);
	}

	/**
	 * Indicates whether this content type matches the specified primary
	 * type and subtype. Comparisons are case insensitive.
	 * @param primaryType the primary type to test
	 * @param subType the subtype to test
	 * @return true if this content-type matches both primary and subtype,
	 * false otherwise
	 */
	public boolean isMimeType(String primaryType, String subType) {
		return this.primaryType.equalsIgnoreCase(primaryType) &&
			   this.subType.equalsIgnoreCase(subType);
	}

	/**
	 * Indicates whether this content type matches the specified MIME type string.
	 * The string should be in "type/subtype" format.
	 * Comparisons are case insensitive.
	 * @param mimeType the MIME type to test (e.g., "text/html", "multipart/form-data")
	 * @return true if this content-type matches the given MIME type, false otherwise
	 */
	public boolean isMimeType(String mimeType) {
		if (mimeType == null) {
			return false;
		}
		int slashIndex = mimeType.indexOf('/');
		if (slashIndex < 0) {
			return false;
		}
		String type = mimeType.substring(0, slashIndex);
		String subtype = mimeType.substring(slashIndex + 1);
		return isMimeType(type, subtype);
	}

	/**
	 * Returns the parameters for this content type, if any.
	 * @return the unmodifiable parameter list or null if there are no parameters
	 */
	public List<Parameter> getParameters() {
		return parameters;
	}

	/**
	 * Returns the first value for the specified parameter name, if any.
	 * Parameter names are case insensitive.
	 * @param name the name of the parameter
	 * @return the value of the first parameter in this content-type that
	 * matches the given name, or null if there is no such parameter
	 */
	public String getParameter(String name) {
		return parameterMap == null ? null : parameterMap.get(name.toLowerCase());
	}

	/**
	 * Indicates whether this content type contains the specified parameter.
	 * @param name the name of the parameter
	 * @return true if a parameter with this name is present, false otherwise
	 */
	public boolean hasParameter(String name) {
		return parameterMap != null && parameterMap.containsKey(name.toLowerCase());
	}

	@Override
	public int hashCode() {
		int hc = primaryType.toLowerCase().hashCode() + subType.toLowerCase().hashCode();
		if (parameters != null) {
			hc += parameters.hashCode();
		}
		return hc;
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof ContentType)) {
			return false;
		}
		ContentType o = (ContentType) other;
		if (!primaryType.equalsIgnoreCase(o.primaryType) ||
			!subType.equalsIgnoreCase(o.subType)) {
			return false;
		}
		if (parameters != null) {
			return parameters.equals(o.parameters);
		} else {
			return o.parameters == null;
		}
	}

	/**
	 * Returns a human-readable string representation.
	 * For wire format serialization, use {@link #toHeaderValue()}.
	 */
	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder(primaryType);
		buf.append('/');
		buf.append(subType);
		if (parameters != null) {
			for (Parameter parameter : parameters) {
				buf.append("; ");
				buf.append(parameter.toString());
			}
		}
		return buf.toString();
	}

	/**
	 * Serializes this content type to RFC 2045/2231 compliant header format.
	 * Parameter values containing non-ASCII characters will be encoded
	 * using RFC 2231 extended parameter syntax.
	 * @return the serialized Content-Type value suitable for use in a header
	 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045'>RFC 2045</a>
	 * @see <a href='https://datatracker.ietf.org/doc/html/rfc2231'>RFC 2231</a>
	 */
	public String toHeaderValue() {
		StringBuilder buf = new StringBuilder(primaryType);
		buf.append('/');
		buf.append(subType);
		if (parameters != null) {
			for (Parameter parameter : parameters) {
				buf.append("; ");
				buf.append(parameter.toHeaderValue());
			}
		}
		return buf.toString();
	}

}
