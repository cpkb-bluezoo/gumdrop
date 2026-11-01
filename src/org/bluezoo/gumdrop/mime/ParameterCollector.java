/*
 * ParameterCollector.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the parameters of a Content-Type or Content-Disposition from the
 * parser's events, for the handlers that are given them as an object.
 *
 * <p>Parameters are kept by the usual rule: the first of a name wins, except
 * that an RFC 2231 extended value replaces a plain one. The total size of what
 * is collected is limited, since it is held.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // uses value classes that are deprecated for removal
final class ParameterCollector {

	private final List<Parameter> parameters = new ArrayList<Parameter>();
	/** Whether each of the parameters was an extended value. */
	private final List<Boolean> extended = new ArrayList<Boolean>();
	private String name;
	private final StringBuilder value = new StringBuilder();
	private boolean isExtended;
	private boolean open;
	private long size;

	void clear() {
		parameters.clear();
		extended.clear();
		open = false;
		size = 0L;
	}

	void startParameter(String name, String language) {
		this.name = name;
		value.setLength(0);
		isExtended = language != null;
		open = true;
		size += name.length();
	}

	/**
	 * Adds some of the value of the current parameter.
	 * @param text the text
	 * @param limit the most that may be collected in all, or zero or less for no limit
	 * @return false if the limit has been passed
	 */
	boolean content(String text, int limit) {
		value.append(text);
		size += text.length();
		return limit <= 0 || size <= limit;
	}

	void endParameter() {
		if (!open) {
			return;
		}
		open = false;
		int at = -1;
		for (int i = 0; i < parameters.size(); i++) {
			if (parameters.get(i).getName().equalsIgnoreCase(name)) {
				at = i;
				break;
			}
		}
		Parameter parameter = new Parameter(name, value.toString());
		if (at < 0) {
			parameters.add(parameter);
			extended.add(isExtended);
		} else if (isExtended && !extended.get(at)) {
			parameters.set(at, parameter);
			extended.set(at, true);
		}
	}

	/** Returns the parameters collected, or null if there were none, and clears the collector. */
	List<Parameter> take() {
		List<Parameter> result = parameters.isEmpty() ? null : new ArrayList<Parameter>(parameters);
		clear();
		return result;
	}

}
