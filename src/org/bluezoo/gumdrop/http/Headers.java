/*
 * Headers.java
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

package org.bluezoo.gumdrop.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * A collection of HTTP headers with convenience methods for header access.
 * Headers are stored in order and support case-insensitive name lookup.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Headers extends ArrayList<Header> {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an empty headers collection.
     */
    public Headers() {
        super();
    }

    /**
     * Creates a headers collection with the specified initial capacity.
     *
     * @param initialCapacity the initial capacity
     */
    public Headers(int initialCapacity) {
        super(initialCapacity);
    }

    /**
     * Creates a headers collection containing the headers from the specified collection.
     *
     * @param headers the collection of headers to copy
     */
    public Headers(Collection<? extends Header> headers) {
        super(headers);
    }

    /**
     * Returns the value of the first header with the specified name.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return the header value, or null if no header with that name exists
     */
    public String getValue(String name) {
        for (Header header : this) {
            if (name.equalsIgnoreCase(header.getName())) {
                return header.getValue();
            }
        }
        return null;
    }

    /**
     * Returns all values for headers with the specified name.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return a list of header values (may be empty, never null)
     */
    public List<String> getValues(String name) {
        List<String> values = new ArrayList<>();
        for (Header header : this) {
            if (name.equalsIgnoreCase(header.getName())) {
                String value = header.getValue();
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /**
     * Returns the first header with the specified name.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return the header, or null if no header with that name exists
     */
    public Header getHeader(String name) {
        for (Header header : this) {
            if (name.equalsIgnoreCase(header.getName())) {
                return header;
            }
        }
        return null;
    }

    /**
     * Returns all headers with the specified name.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return a list of headers (may be empty, never null)
     */
    public List<Header> getHeaders(String name) {
        List<Header> headers = new ArrayList<>();
        for (Header header : this) {
            if (name.equalsIgnoreCase(header.getName())) {
                headers.add(header);
            }
        }
        return headers;
    }

    /**
     * Returns true if a header with the specified name exists.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return true if the header exists
     */
    public boolean containsName(String name) {
        for (Header header : this) {
            if (name.equalsIgnoreCase(header.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a header with the specified name and value.
     * This is a convenience method equivalent to add(new Header(name, value)).
     *
     * @param name the header name
     * @param value the header value
     * @return true (as specified by Collection.add)
     */
    public boolean add(String name, String value) {
        return add(new Header(name, value));
    }

    /**
     * Sets a header, replacing any existing headers with the same name.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @param value the header value
     */
    public void set(String name, String value) {
        removeAll(name);
        add(new Header(name, value));
    }

    /**
     * Removes all headers with the specified name.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return true if any headers were removed
     */
    public boolean removeAll(String name) {
        boolean removed = false;
        Iterator<Header> it = iterator();
        while (it.hasNext()) {
            Header header = it.next();
            if (name.equalsIgnoreCase(header.getName())) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    /**
     * Returns a comma-separated string of all values for the specified header name.
     * This is useful for headers like Accept that may appear multiple times
     * or have comma-separated values.
     *
     * @param name the header name
     * @return comma-separated values, or null if no headers with that name exist
     */
    public String getCombinedValue(String name) {
        StringBuilder combined = null;
        for (Header header : this) {
            if (name.equalsIgnoreCase(header.getName())) {
                String value = header.getValue();
                if (value != null) {
                    if (combined == null) {
                        combined = new StringBuilder(value);
                    } else {
                        combined.append(", ").append(value);
                    }
                }
            }
        }
        return combined != null ? combined.toString() : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Convenience methods for HTTP pseudo-headers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets the :status pseudo-header for a response.
     * This is a convenience method for setting the HTTP status code.
     *
     * @param status the HTTP status
     */
    public void status(HTTPStatus status) {
        set(":status", Integer.toString(status.code));
    }

    /**
     * Returns the HTTP method from the :method pseudo-header.
     *
     * @return the HTTP method (GET, POST, etc.), or null if not present
     */
    public String getMethod() {
        return getValue(":method");
    }

    /**
     * Returns the request path from the :path pseudo-header.
     *
     * @return the request path, or null if not present
     */
    public String getPath() {
        return getValue(":path");
    }

}
