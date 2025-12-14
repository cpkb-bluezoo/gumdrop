/*
 * JSONDefaultHandler.java
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
 * Default implementation for the JSONContentHandler interface.
 * This implementation does nothing. It is simply intented to be a
 * convenient class to subclass if you only want to implement a subset of
 * the JSONContentHandler methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSONDefaultHandler implements JSONContentHandler {

    @Override
    public void startObject() throws JSONException {
    }

    @Override
    public void endObject() throws JSONException {
    }

    @Override
    public void startArray() throws JSONException {
    }

    @Override
    public void endArray() throws JSONException {
    }

    @Override
    public void numberValue(Number number) throws JSONException {
    }

    @Override
    public void stringValue(String value) throws JSONException {
    }

    @Override
    public void booleanValue(boolean value) throws JSONException {
    }

    @Override
    public void nullValue() throws JSONException {
    }

    @Override
    public void whitespace(String whitespace) throws JSONException {
    }

    @Override
    public void key(String key) throws JSONException {
    }

    @Override
    public void setLocator(JSONLocator locator) {
    }

}
