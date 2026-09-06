/*
 * DNSServerCapabilityCache.java
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide, address-keyed cache of which encrypted DNS transports a
 * server is known to support.
 *
 * <p>Three tiers, modeled on {@link DNSMultiQTypeCache} and {@link
 * org.bluezoo.gumdrop.http.client.AltSvcCache}:
 * <ul>
 * <li>A permanent, seeded table of well-known public resolvers (Google,
 *     Cloudflare, Quad9) known in advance to support DoQ, DoT, and DoH
 *     -- so {@link DNSResolver#useSystemResolvers()}'s plaintext
 *     fallback to these addresses prefers an encrypted transport
 *     instead of landing on plain UDP.</li>
 * <li>A runtime-learned positive cache: capabilities discovered for an
 *     otherwise-unknown server via RFC 9462 Discovery of Designated
 *     Resolvers (DDR, see {@link DNSResolver}'s DDR support). Kept
 *     without expiry -- a resolver that stops supporting what it
 *     advertised will surface through the negative cache below the
 *     next time that transport actually fails.</li>
 * <li>A temporary, runtime-discovered negative cache: a transport that
 *     failed to open, or whose connection later failed, for a
 *     particular server. Only the negative case is cached, and only
 *     for a bounded time, so a server whose support changes (or that
 *     failed transiently) is retried later rather than written off
 *     forever.</li>
 * </ul>
 *
 * <p>Deliberately not attempted: speculative probing of an arbitrary,
 * otherwise-unknown server (e.g. a typical ISP or router resolver from
 * {@code /etc/resolv.conf}) by actually opening a DoQ/DoT/DoH
 * connection to it. Most such servers support none of them, and unlike
 * an EDNS0 option opportunistically attached to a query that is being
 * sent anyway (as in {@link DNSMultiQTypeCache}), a real connection
 * attempt costs a real connection timeout when it fails -- trying it by
 * default for every configured server would add that latency to the
 * common case. DDR sidesteps this: it is itself a single plain DNS
 * query to a server already being talked to in plaintext, so it costs
 * one extra round trip rather than a whole connection attempt.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSResolver
 */
final class DNSServerCapabilityCache {

    // RFC 9250/7858/8484 don't define a TTL for this kind of capability
    // discovery; an hour bounds how long a server that starts (or
    // resumes) supporting a transport goes un-retried, without probing
    // constantly -- same rationale and value as DNSMultiQTypeCache.
    private static final long UNSUPPORTED_TTL_MS = 60 * 60 * 1000L;

    private static final Map<String, DNSServerCapabilities> WELL_KNOWN = wellKnownResolvers();

    private static final ConcurrentMap<String, DNSServerCapabilities> learned = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, Long> unsupportedUntil = new ConcurrentHashMap<>();

    private DNSServerCapabilityCache() {
    }

    /**
     * Returns what's known about {@code server}'s encrypted transport
     * support: the seeded well-known table takes priority, then
     * anything learned at runtime via DDR, else {@link
     * DNSServerCapabilities#UNKNOWN}.
     */
    static DNSServerCapabilities get(InetSocketAddress server) {
        String k = addressKey(server);
        DNSServerCapabilities known = WELL_KNOWN.get(k);
        if (known != null) {
            return known;
        }
        DNSServerCapabilities discovered = learned.get(k);
        return discovered != null ? discovered : DNSServerCapabilities.UNKNOWN;
    }

    /**
     * Records capabilities discovered at runtime via RFC 9462 DDR for
     * an otherwise-unknown server. A no-op for a server already in the
     * permanent well-known table, which takes priority regardless.
     */
    static void learn(InetSocketAddress server, DNSServerCapabilities capabilities) {
        String k = addressKey(server);
        if (!WELL_KNOWN.containsKey(k)) {
            learned.put(k, capabilities);
        }
    }

    /**
     * Returns true if {@code transport} was recently observed not to
     * work against {@code server}, i.e. it should be skipped for now.
     */
    static boolean isKnownUnsupported(InetSocketAddress server, DNSTransportType transport) {
        String k = key(server, transport);
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
     * Records that {@code transport} does not appear to work against
     * {@code server} (its transport failed to open, or its connection
     * failed shortly after).
     */
    static void markUnsupported(InetSocketAddress server, DNSTransportType transport) {
        unsupportedUntil.put(key(server, transport),
                System.currentTimeMillis() + UNSUPPORTED_TTL_MS);
    }

    /**
     * Clears runtime-discovered state (both DDR-learned capabilities
     * and the negative cache). Intended for tests; the permanent
     * well-known table is never affected.
     */
    static void clear() {
        learned.clear();
        unsupportedUntil.clear();
    }

    private static String addressKey(InetSocketAddress server) {
        return server.getAddress().getHostAddress();
    }

    private static String key(InetSocketAddress server, DNSTransportType transport) {
        return addressKey(server) + "|" + transport;
    }

    // RFC 8484 §4.1: all three providers below use the conventional
    // "/dns-query" path, so it isn't varied per entry here. Also used by
    // DNSResolver's DDR discovery as the RFC 9461 §5 default when a
    // "dohpath" SvcParam is absent.
    static final String DOH_PATH = "/dns-query";

    private static Map<String, DNSServerCapabilities> wellKnownResolvers() {
        Map<String, DNSServerCapabilities> m = new HashMap<>();
        DNSServerCapabilities allSupported =
                DNSServerCapabilities.of(true, 0, true, 0, DOH_PATH, 0);

        // Google Public DNS: DoQ/DoT on port 853, DoH at dns.google.
        m.put("8.8.8.8", allSupported);
        m.put("8.8.4.4", allSupported);
        m.put("2001:4860:4860::8888", allSupported);
        m.put("2001:4860:4860::8844", allSupported);

        // Cloudflare DNS: DoQ/DoT on port 853, DoH at cloudflare-dns.com.
        m.put("1.1.1.1", allSupported);
        m.put("1.0.0.1", allSupported);
        m.put("2606:4700:4700::1111", allSupported);
        m.put("2606:4700:4700::1001", allSupported);

        // Quad9: DoQ/DoT on port 853, DoH at dns.quad9.net.
        m.put("9.9.9.9", allSupported);
        m.put("149.112.112.112", allSupported);
        m.put("2620:fe::fe", allSupported);
        m.put("2620:fe::9", allSupported);

        return Collections.unmodifiableMap(m);
    }
}
