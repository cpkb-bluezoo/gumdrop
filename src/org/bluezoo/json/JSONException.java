/*
 * JSONException.java
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
 * An exception thrown to indicate an anomaly during JSON parsing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSONException extends Exception {

    /**
     * Constructor for a JSON exception with no message.
     */
    public JSONException() {
    }

    /**
     * Constructor for a JSON exception with the specified message.
     * @param message the exception message
     */
    public JSONException(String message) {
        super(message);
    }

    /**
     * Constructor for a JSON exception with the specified message and
     * underlying cause.
     * @param message the exception message
     * @param cause the underlying cause of this message
     */
    public JSONException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }

}
