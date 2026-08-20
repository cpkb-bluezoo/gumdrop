/*
 * DNSMultiQTypeCache.java
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

package org.bluezoo.gumdrop.dns.client;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide, address:port-keyed cache of which DNS servers are known
 * <em>not</em> to support RFC 10029 (DNS Multiple QTYPEs).
 *
 * <p>Modeled on {@link org.bluezoo.gumdrop.http.client.AltSvcCache}: a
 * server that has never been tried, or that supports the mechanism, has
 * no entry here at all -- {@link DNSResolver#queryBatch} always tries
 * opportunistically by default, since attaching the option costs
 * nothing when it works. Only the negative case is cached, and only
 * temporarily, so a server whose support changes (or was probed while
 * temporarily misbehaving) gets re-tried later rather than being
 * written off forever.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc10029">RFC 10029</a>
 */
final class DNSMultiQTypeCache {

    // RFC 10029 doesn't define a TTL for this kind of capability
    // discovery; an hour bounds how long a server that starts (or
    // resumes) supporting the option goes un-retried, without probing
    // constantly.
    private static final long UNSUPPORTED_TTL_MS = 60 * 60 * 1000L;

    private static final ConcurrentMap<String, Long> unsupportedUntil = new ConcurrentHashMap<>();

    private DNSMultiQTypeCache() {
    }

    /**
     * Records that {@code server} does not appear to support RFC 10029.
     *
     * @param server the server address
     */
    static void markUnsupported(InetSocketAddress server) {
        unsupportedUntil.put(key(server),
                System.currentTimeMillis() + UNSUPPORTED_TTL_MS);
    }

    /**
     * Returns true if {@code server} was recently observed not to
     * support RFC 10029, i.e. attaching {@code MQTYPE-Query} to it
     * should be skipped for now.
     *
     * @param server the server address
     * @return true if known unsupported
     */
    static boolean isKnownUnsupported(InetSocketAddress server) {
        String k = key(server);
        Long expiry = unsupportedUntil.get(k);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry.longValue()) {
            unsupportedUntil.remove(k, expiry);
            return false;
        }
        return true;
    }

    /**
     * Clears all cached entries. Intended for tests.
     */
    static void clear() {
        unsupportedUntil.clear();
    }

    private static String key(InetSocketAddress server) {
        return server.getAddress().getHostAddress() + ":" + server.getPort();
    }
}
