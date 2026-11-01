/*
 * BoundaryCollector.java
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

/**
 * Picks the multipart boundary out of the parameter events of a
 * Content-Type, holding nothing else. It is what the parser uses when the
 * handler does not want the Content-Type as an object.
 *
 * <p>The rule is that of {@link ParameterCollector}: the first boundary
 * parameter wins, except that an RFC 2231 extended value replaces a plain
 * one. A boundary is at most 70 characters (RFC 2046), so a value longer
 * than that is not held; the boundary is then invalid.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class BoundaryCollector {

	private static final int MAX_HELD = 71;

	private final StringBuilder value = new StringBuilder();
	private boolean current; // the parameter being read is a boundary
	private boolean currentExtended;
	private boolean overflow;
	private boolean have;
	private boolean haveExtended;
	private String boundary;

	void clear() {
		value.setLength(0);
		current = false;
		have = false;
		haveExtended = false;
		boundary = null;
	}

	void startParameter(String name, String language) {
		current = name.equalsIgnoreCase("boundary");
		currentExtended = language != null;
		overflow = false;
		value.setLength(0);
	}

	void content(String text) {
		if (!current || overflow) {
			return;
		}
		if (value.length() + text.length() > MAX_HELD) {
			overflow = true;
			value.setLength(0);
			return;
		}
		value.append(text);
	}

	void endParameter() {
		if (!current) {
			return;
		}
		current = false;
		if (!have || (currentExtended && !haveExtended)) {
			have = true;
			haveExtended = currentExtended;
			// an overlong value is kept as one that is not valid
			boundary = overflow ? "" : value.toString();
		}
	}

	/** Returns the boundary, or null if there was none. */
	String boundary() {
		return boundary;
	}

}
