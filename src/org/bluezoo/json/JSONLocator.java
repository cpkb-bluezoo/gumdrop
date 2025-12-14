/*
 * JSONLocator.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jsonparser, a JSON parsing library for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jsonparser/
 *
 * jsonparser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * jsonparser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with jsonparser.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.json;

/**
 * Locator information for JSON parser.
 * This can be used to retrieve the location in the JSON stream at which
 * parsing events were reported, including errors. This information is
 * transient and only valid at the time the event was fired or the
 * exception was thrown.
 * Note that JSON is a standalone file format.
 * Both line and column numbers start at 1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JSONLocator {

    /**
     * Returns the line number currently being processed.
     * The first line is line 1.
     * @return the current line number
     */
    public int getLineNumber();

    /**
     * Returns the column number currently being processed.
     * The first column in each line is column 1.
     * @return the current column number
     */
    public int getColumnNumber();

}
