/*
 * ContentDisposition.java
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
 * A MIME Content-Disposition value.
 * @see <a href='https://www.ietf.org/rfc/rfc2183.txt'>RFC 2183</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentDisposition {

	private final String dispositionType;
	private final List<Parameter> parameters;
	private final Map<String, String> parameterMap;

	/**
	 * Constructor.
	 * @param dispositionType the disposition type, usually "inline" or
	 * "attachment". May not be null
	 * @param parameters optional list of parameters for the content
	 * disposition, in order. May be null
	 * @exception NullPointerException if dispositionType is null
	 */
	public ContentDisposition(String dispositionType, List<Parameter> parameters) {
		if (dispositionType == null) {
			throw new NullPointerException("dispositionType must not be null");
		}
		this.dispositionType = dispositionType;
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
	 * Returns the disposition type.
	 * This is usually "inline" (the default value, indicating that the
	 * content can be displayed in the page or window) or "attachment",
	 * indicating that it should be downloaded.
	 * For multipart/form-data, this will be "form-data".
	 * @return the non-normalized disposition type
	 */
	public String getDispositionType() {
		return dispositionType;
	}

	/**
	 * Indicates whether this content disposition matches the specified
	 * disposition type. Comparisons are case insensitive.
	 * @param dispositionType the disposition type to test
	 * @return true if this disposition matches, false otherwise
	 */
	public boolean isDispositionType(String dispositionType) {
		return this.dispositionType.equalsIgnoreCase(dispositionType);
	}

	/**
	 * Returns the parameters for this disposition, if any.
	 * @return the unmodifiable parameter list or null if there are no parameters
	 */
	public List<Parameter> getParameters() {
		return parameters;
	}

	/**
	 * Returns the first value for the specified parameter name, if any.
	 * Parameter names are case insensitive.
	 * @param name the name of the parameter
	 * @return the value of the first parameter in this content-disposition
	 * that matches the given name, or null if there is no such parameter
	 */
	public String getParameter(String name) {
		return parameterMap == null ? null : parameterMap.get(name.toLowerCase());
	}

	/**
	 * Indicates whether this content disposition contains the specified parameter.
	 * @param name the name of the parameter
	 * @return true if a parameter with this name is present, false otherwise
	 */
	public boolean hasParameter(String name) {
		return parameterMap != null && parameterMap.containsKey(name.toLowerCase());
	}

	@Override
	public int hashCode() {
		int hc = dispositionType.toLowerCase().hashCode();
		if (parameters != null) {
			hc += parameters.hashCode();
		}
		return hc;
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof ContentDisposition)) {
			return false;
		}
		ContentDisposition o = (ContentDisposition) other;
		if (!dispositionType.equalsIgnoreCase(o.dispositionType)) {
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
		StringBuilder buf = new StringBuilder(dispositionType);
		if (parameters != null) {
			for (Parameter parameter : parameters) {
				buf.append("; ");
				buf.append(parameter.toString());
			}
		}
		return buf.toString();
	}

	/**
	 * Serializes this content disposition to RFC 2183/2231 compliant header format.
	 * Parameter values containing non-ASCII characters will be encoded
	 * using RFC 2231 extended parameter syntax.
	 * @return the serialized Content-Disposition value suitable for use in a header
	 * @see <a href='https://www.ietf.org/rfc/rfc2183.txt'>RFC 2183</a>
	 * @see <a href='https://datatracker.ietf.org/doc/html/rfc2231'>RFC 2231</a>
	 */
	public String toHeaderValue() {
		StringBuilder buf = new StringBuilder(dispositionType);
		if (parameters != null) {
			for (Parameter parameter : parameters) {
				buf.append("; ");
				buf.append(parameter.toHeaderValue());
			}
		}
		return buf.toString();
	}

}
