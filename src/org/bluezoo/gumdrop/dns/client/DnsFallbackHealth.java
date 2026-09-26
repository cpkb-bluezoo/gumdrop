/*
 * DnsFallbackHealth.java
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Process-wide record of how the well-known public IPv6 nameservers have
 * behaved, so {@link DnsResolver} can prefer them where the host reaches
 * the public IPv6 internet without stalling where it does not.
 *
 * <p>Three separate facts are kept, each for a bounded time because a
 * long-running process can move between networks:
 * <ul>
 * <li><b>No route</b>: the host has no path to public IPv6 at all.</li>
 * <li><b>Address bad</b>: one nameserver's address timed out. Other IPv6
 *     addresses stay eligible.</li>
 * <li><b>IPv4 preferred</b>: IPv4 has answered ahead of IPv6, so queries
 *     stop giving IPv6 a head start until the preference expires.</li>
 * </ul>
 *
 * <p>All methods take the current time so tests can control it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DnsFallbackHealth {

    /** How long the host is assumed to lack public IPv6 after a no-route signal. */
    static final long NO_ROUTE_MS = 5 * 60 * 1000L;

    /** How long one IPv6 nameserver address is skipped after it timed out. */
    static final long ADDRESS_BAD_MS = 5 * 60 * 1000L;

    /** How long IPv6 loses its head start after IPv4 answered first. */
    static final long IPV4_WIN_MS = 2 * 60 * 1000L;

    /** As {@link #IPV4_WIN_MS}, once several providers' IPv6 addresses have failed. */
    static final long BLACKHOLE_MS = 10 * 60 * 1000L;

    /** How often the interfaces are re-examined for a global IPv6 address. */
    static final long GLOBAL_CHECK_INTERVAL_MS = 60 * 1000L;

    private static long noRouteUntil;
    private static long ipv4PreferredUntil;
    private static long nextGlobalCheck;
    private static int rotation;
    private static final Map<String, Long> badUntil = new HashMap<String, Long>();
    private static final Map<String, Integer> badProvider = new HashMap<String, Integer>();

    private DnsFallbackHealth() {
    }

    static synchronized boolean isNoRoute(long now) {
        return now < noRouteUntil;
    }

    static synchronized void markNoRoute(long now) {
        noRouteUntil = now + NO_ROUTE_MS;
    }

    /** Whether the interfaces are due to be re-examined for a global IPv6 address. */
    static synchronized boolean globalCheckDue(long now) {
        if (now < nextGlobalCheck) {
            return false;
        }
        nextGlobalCheck = now + GLOBAL_CHECK_INTERVAL_MS;
        return true;
    }

    static synchronized boolean isBad(String address, long now) {
        Long until = badUntil.get(address);
        if (until == null) {
            return false;
        }
        if (now >= until.longValue()) {
            badUntil.remove(address);
            badProvider.remove(address);
            return false;
        }
        return true;
    }

    static synchronized void markBad(String address, int provider, long now) {
        badUntil.put(address, Long.valueOf(now + ADDRESS_BAD_MS));
        badProvider.put(address, Integer.valueOf(provider));
    }

    /** Returns how many distinct providers currently have a bad IPv6 address. */
    static synchronized int badProviderCount(long now) {
        Set<Integer> providers = new HashSet<Integer>();
        Iterator<Map.Entry<String, Long>> it = badUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now >= entry.getValue().longValue()) {
                badProvider.remove(entry.getKey());
                it.remove();
            } else {
                providers.add(badProvider.get(entry.getKey()));
            }
        }
        return providers.size();
    }

    static synchronized boolean isIpv4Preferred(long now) {
        return now < ipv4PreferredUntil;
    }

    static synchronized void preferIpv4(long now, long durationMs) {
        ipv4PreferredUntil = Math.max(ipv4PreferredUntil, now + durationMs);
    }

    /** Returns which provider's IPv6 address is probed first once the head start is offered again. */
    static synchronized int rotation() {
        return rotation;
    }

    static synchronized void advanceRotation() {
        rotation++;
    }

    /** An IPv6 nameserver answered: the host reaches public IPv6. */
    static synchronized void ipv6Answered(String address) {
        noRouteUntil = 0;
        ipv4PreferredUntil = 0;
        badUntil.remove(address);
        badProvider.remove(address);
    }

    static synchronized void reset() {
        noRouteUntil = 0;
        ipv4PreferredUntil = 0;
        nextGlobalCheck = 0;
        rotation = 0;
        badUntil.clear();
        badProvider.clear();
    }
}
