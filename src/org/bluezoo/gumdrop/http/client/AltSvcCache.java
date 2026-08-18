/*
 * AltSvcCache.java
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

package org.bluezoo.gumdrop.http.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide, host:port-keyed cache of h3 support discovered via Alt-Svc
 * response headers (RFC 7838).
 *
 * <p>Unlike DNS HTTPS-record discovery ({@link org.bluezoo.gumdrop.dns.DNSType#HTTPS}),
 * Alt-Svc is only visible after already connecting once (it's a response
 * header, not something a client can look up in advance). This cache lets
 * that discovery benefit later, separate connection attempts -- by
 * {@code HTTPClient} or {@code WebSocketClient}, to the same host:port --
 * rather than being wasted on the single connection that happened to see it.
 *
 * <p>Used as the second discovery tier: DNS HTTPS records are checked
 * first, and this cache only consulted in their absence.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AltSvcCache {

    private static final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    private AltSvcCache() {
    }

    /**
     * Records that {@code host:port} advertised h3 support at
     * {@code h3Host:h3Port}, valid for {@code maxAgeSeconds}.
     *
     * @param host the origin host
     * @param port the origin port
     * @param h3Host the advertised h3 host, or {@code null} for same-origin
     * @param h3Port the advertised h3 port
     * @param maxAgeSeconds how long this entry remains valid
     */
    public static void put(String host, int port, String h3Host, int h3Port,
                            long maxAgeSeconds) {
        long expiry = System.currentTimeMillis() + Math.max(0, maxAgeSeconds) * 1000L;
        cache.put(key(host, port), new Entry(h3Host, h3Port, expiry));
    }

    /**
     * Returns the cached h3 entry for {@code host:port}, or {@code null}
     * if absent or expired.
     *
     * @param host the origin host
     * @param port the origin port
     * @return the cached entry, or {@code null}
     */
    public static Entry get(String host, int port) {
        Entry entry = cache.get(key(host, port));
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(key(host, port), entry);
            return null;
        }
        return entry;
    }

    /**
     * Clears all cached entries. Intended for tests.
     */
    public static void clear() {
        cache.clear();
    }

    private static String key(String host, int port) {
        return host.toLowerCase() + ":" + port;
    }

    /**
     * A cached h3 alt-endpoint.
     */
    public static final class Entry {
        private final String h3Host;
        private final int h3Port;
        private final long expiryTime;

        Entry(String h3Host, int h3Port, long expiryTime) {
            this.h3Host = h3Host;
            this.h3Port = h3Port;
            this.expiryTime = expiryTime;
        }

        /**
         * Returns the advertised h3 host, or {@code null} for same-origin.
         *
         * @return the h3 host, or {@code null}
         */
        public String getH3Host() {
            return h3Host;
        }

        /**
         * Returns the advertised h3 port.
         *
         * @return the h3 port
         */
        public int getH3Port() {
            return h3Port;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }
    }
}
