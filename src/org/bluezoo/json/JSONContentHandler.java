/*
 * JSONContentHandler.java
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
 * This is the main interface to be implemented by an application wanting to
 * receive JSON parsing events. An application can implement this interface
 * and register the implementation with the JSONParser using the
 * setContentHandler method.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface JSONContentHandler {

    /**
     * Indicates the start of a JSON object.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void startObject() throws JSONException;

    /**
     * Indicates the end of a JSON object.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void endObject() throws JSONException;

    /**
     * Indicates the start of a JSON array.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void startArray() throws JSONException;

    /**
     * Indicates the end of a JSON array.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void endArray() throws JSONException;

    /**
     * Notifies of a JSON number.
     * @param number the number parsed: may be an Integer, Long, or Double
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void numberValue(Number number) throws JSONException;

    /**
     * Notifies of a JSON string.
     * @param value the string value, unescaped and unquoted
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void stringValue(String value) throws JSONException;

    /**
     * Notifies of a JSON boolean value.
     * @param value the boolean value
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void booleanValue(boolean value) throws JSONException;

    /**
     * Notifies of a JSON null value.
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void nullValue() throws JSONException;

    /**
     * Notifies of whitespace in the underlying stream. Note that whitespace
     * between key strings and their associated colon, if present,
     * will not be reported by this method.
     * @param whitespace the whitespace contents as a string
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void whitespace(String whitespace) throws JSONException;

    /**
     * Notifies of a key in a JSON object. This is a string followed by a
     * colon inside an object representation in the JSON source. It will
     * always be associated with the most recent previous startObject event.
     * @param key the key name
     * @throws JSONException the client may throw an exception during
     * processing
     */
    public void key(String key) throws JSONException;

    /**
     * Receive an object that can be used to determine the current location
     * during parsing. The locator allows the application to determine the
     * end position of any parsing-related event, even if the parser is not
     * reporting an error. Typically, the application will use this
     * information for reporting its own errors (such as character content
     * that does not match an application's business rules).
     * @param locator the locator object
     */
    public void setLocator(JSONLocator locator);

    /**
     * Indicates whether this handler needs to receive whitespace events.
     * By default, handlers do not need whitespace, which allows the parser
     * to skip expensive string extraction for whitespace sequences.
     * Implementations that need whitespace (e.g., for pretty-printing or
     * preserving exact formatting) should override this to return true.
     * 
     * @return true if this handler wants {@link #whitespace(String)} to be
     *         called, false otherwise (default)
     */
    default boolean needsWhitespace() {
        return false;
    }

}
