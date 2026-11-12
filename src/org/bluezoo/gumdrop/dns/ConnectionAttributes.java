/*
 * DefaultDnsQueryContext.java
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

package org.bluezoo.gumdrop.dns;

import java.util.HashMap;
import java.util.Map;

/**
 * The attributes handlers keep for one connection, shared by the
 * {@link DnsQueryContext}s of all the queries that arrive on it. Used only
 * on the connection's selector loop, so it needs no locking. Closed with the
 * connection, which releases everything in it and refuses anything further,
 * so that a handler still holding an old context cannot bring state back.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ConnectionAttributes {

    private final Map<DnsQueryContext.Key<?>, Object> values =
            new HashMap<DnsQueryContext.Key<?>, Object>();
    private boolean closed;

    @SuppressWarnings("unchecked")
    <T> T get(DnsQueryContext.Key<T> key) {
        return (T) values.get(key);
    }

    <T> void set(DnsQueryContext.Key<T> key, T value) {
        if (closed) {
            return;
        }
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    /** Releases everything, and keeps nothing more. */
    void close() {
        closed = true;
        values.clear();
    }
}
