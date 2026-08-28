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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A collection of HTTP headers with convenience methods for header access.
 * Headers are stored in order and support case-insensitive name lookup.
 *
 * <p>RFC 9110 section 5.1: "Each field name ... is case-insensitive."
 * All name-based lookups in this class use case-insensitive comparison.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Headers extends ArrayList<Header> {

    private static final long serialVersionUID = 1L;

    /**
     * Lazily-built name -&gt; headers index (see issue #141), keyed by
     * lower-cased header name. Callers mutate this list directly via
     * inherited {@code ArrayList} methods (add, remove, iterator.remove,
     * etc.) that this class does not override, so rather than try to keep
     * the index precisely in sync with every mutation, it is invalidated
     * wholesale by comparing against {@link java.util.AbstractList#modCount}
     * (bumped by every structural change, including ones made through
     * inherited methods) and rebuilt in O(n) the next time it's consulted.
     * Lookups are then O(1) as long as consumers don't interleave a
     * mutation between every pair of lookups, which is the normal case
     * (headers are parsed once, then looked up repeatedly per request).
     */
    private transient Map<String,List<Header>> index;
    private transient int indexModCount = -1;

    /**
     * Counts how many times {@link #index()} has actually rebuilt the map,
     * as opposed to reusing the cached one. Public (but otherwise
     * documented as test-only, like {@code CryptoExecutor}/{@code
     * StorageExecutor}'s {@code workThreadObserver} hooks) so tests
     * outside this package -- e.g. {@code org.bluezoo.gumdrop.servlet},
     * whose {@code Request}/{@code Response} wrap a {@code Headers}
     * instance -- can verify the same thing (see issue #278) about their
     * own callers: that doing several lookups in a row - e.g. {@code
     * Stream.sendResponseHeaders}'s {@code containsName} checks - doesn't
     * interleave a mutation between each pair and so force a rebuild
     * before every single one. Production code must not read this.
     */
    public transient int indexBuildCountForTesting = 0;

    private Map<String,List<Header>> index() {
        if (index == null || indexModCount != modCount) {
            indexBuildCountForTesting++;
            Map<String,List<Header>> built = new HashMap<>();
            for (Header header : this) {
                String key = header.getName().toLowerCase(Locale.ROOT);
                List<Header> forName = built.get(key);
                if (forName == null) {
                    forName = new ArrayList<>();
                    built.put(key, forName);
                }
                forName.add(header);
            }
            index = built;
            indexModCount = modCount;
        }
        return index;
    }

    private List<Header> indexed(String name) {
        List<Header> found = index().get(name.toLowerCase(Locale.ROOT));
        return found != null ? found : Collections.emptyList();
    }

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
        List<Header> found = indexed(name);
        return found.isEmpty() ? null : found.get(0).getValue();
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
        for (Header header : indexed(name)) {
            String value = header.getValue();
            if (value != null) {
                values.add(value);
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
        List<Header> found = indexed(name);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Returns all headers with the specified name.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return a list of headers (may be empty, never null)
     */
    public List<Header> getHeaders(String name) {
        return new ArrayList<>(indexed(name));
    }

    /**
     * Returns true if a header with the specified name exists.
     * Header name matching is case-insensitive.
     *
     * @param name the header name
     * @return true if the header exists
     */
    public boolean containsName(String name) {
        return !indexed(name).isEmpty();
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
        for (Header header : indexed(name)) {
            String value = header.getValue();
            if (value != null) {
                if (combined == null) {
                    combined = new StringBuilder(value);
                } else {
                    combined.append(", ").append(value);
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
