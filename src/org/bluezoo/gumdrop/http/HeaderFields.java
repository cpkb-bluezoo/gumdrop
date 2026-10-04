/*
 * HeaderFields.java
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
import java.util.Iterator;
import java.util.List;

/**
 * Static helpers for looking up and editing HTTP header fields held in a
 * plain {@code List<Header>}. Names are matched case-insensitively.
 *
 * <p>RFC 9110 section 5.1: "Each field name ... is case-insensitive."
 * All name-based lookups in this class use case-insensitive comparison.
 * Lookups are linear scans; header lists are short, and callers that need
 * several answers take them in one pass over the list.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HeaderFields {

    private HeaderFields() {
    }

    /**
     * Returns the valid fields a {@link HeaderCollector} has gathered so
     * far, in the order delivered. The returned list is the collector's own
     * and keeps growing as more fields are delivered.
     *
     * @param collector the collector
     * @return the collected fields
     */
    public static List<Header> collected(HeaderCollector collector) {
        return collector.headers();
    }

    /**
     * Strips {@link HttpVersion#isHttp1FramingHeader HTTP/1 framing headers}
     * from a multiplexed-protocol header list before dispatch or sending.
     * Pseudo-header fields (names starting with a colon) are left alone.
     *
     * <p>A {@code Content-Length} is a legal field in an HTTP/2 or HTTP/3
     * message (RFC 9113 section 8.1.1, RFC 9114 section 4.1.2) provided it
     * matches the DATA bytes. A caller whose messages carry one that is
     * checked against the body keeps it by passing true.
     *
     * @param fields the header list, edited in place; may be null
     * @param keepContentLength true to leave {@code Content-Length} in place,
     *        false to strip it with the other framing fields
     */
    public static void stripHttp1FramingHeaders(List<Header> fields,
            boolean keepContentLength) {
        if (fields == null) {
            return;
        }
        Iterator<Header> it = fields.iterator();
        while (it.hasNext()) {
            Header header = it.next();
            if (header.getName().startsWith(":")) {
                continue;
            }
            if (keepContentLength && "content-length".equalsIgnoreCase(header.getName())) {
                continue;
            }
            if (HttpVersion.isHttp1FramingHeader(header.getName(), header.getValue())) {
                it.remove();
            }
        }
    }

    /**
     * Returns the value of the first header with the specified name.
     *
     * @param fields the header list
     * @param name the header name
     * @return the header value, or null if no header with that name exists
     */
    public static String getValue(List<Header> fields, String name) {
        Header header = getHeader(fields, name);
        return header == null ? null : header.getValue();
    }

    /**
     * Returns all non-null values for headers with the specified name.
     *
     * @param fields the header list
     * @param name the header name
     * @return a list of header values (may be empty, never null)
     */
    public static List<String> getValues(List<Header> fields, String name) {
        List<String> values = new ArrayList<String>();
        for (Header header : fields) {
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
     *
     * @param fields the header list
     * @param name the header name
     * @return the header, or null if no header with that name exists
     */
    public static Header getHeader(List<Header> fields, String name) {
        for (Header header : fields) {
            if (name.equalsIgnoreCase(header.getName())) {
                return header;
            }
        }
        return null;
    }

    /**
     * Returns all headers with the specified name.
     *
     * @param fields the header list
     * @param name the header name
     * @return a new list of headers (may be empty, never null)
     */
    public static List<Header> getHeaders(List<Header> fields, String name) {
        List<Header> found = new ArrayList<Header>();
        for (Header header : fields) {
            if (name.equalsIgnoreCase(header.getName())) {
                found.add(header);
            }
        }
        return found;
    }

    /**
     * Returns true if a header with the specified name exists.
     *
     * @param fields the header list
     * @param name the header name
     * @return true if the header exists
     */
    public static boolean containsName(List<Header> fields, String name) {
        return getHeader(fields, name) != null;
    }

    /**
     * Appends a header with the specified name and value.
     *
     * @param fields the header list
     * @param name the header name
     * @param value the header value
     * @return true (as specified by Collection.add)
     */
    public static boolean add(List<Header> fields, String name, String value) {
        return fields.add(new Header(name, value));
    }

    /**
     * Sets a header, replacing any existing headers with the same name.
     *
     * @param fields the header list
     * @param name the header name
     * @param value the header value
     */
    public static void set(List<Header> fields, String name, String value) {
        removeAll(fields, name);
        fields.add(new Header(name, value));
    }

    /**
     * Removes all headers with the specified name.
     *
     * @param fields the header list
     * @param name the header name
     * @return true if any headers were removed
     */
    public static boolean removeAll(List<Header> fields, String name) {
        boolean removed = false;
        Iterator<Header> it = fields.iterator();
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
     * Returns a comma-separated string of all values for the specified
     * header name. This is useful for headers like Accept that may appear
     * multiple times or have comma-separated values.
     *
     * @param fields the header list
     * @param name the header name
     * @return comma-separated values, or null if no headers with that name
     *      exist
     */
    public static String getCombinedValue(List<Header> fields, String name) {
        StringBuilder combined = null;
        for (Header header : fields) {
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

}
