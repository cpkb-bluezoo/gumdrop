/*
 * ResolveCallback.java
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

import java.net.InetAddress;
import java.util.List;

/**
 * Callback interface for hostname resolution.
 *
 * <p>Used by {@link DNSResolver#resolve(String, ResolveCallback)} to
 * deliver the result of a combined A and AAAA lookup.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSResolver#resolve(String, ResolveCallback)
 */
public interface ResolveCallback {

    /**
     * Called when the hostname has been resolved to one or more addresses.
     *
     * <p>The list contains IPv6 addresses first, followed by IPv4 addresses,
     * for Happy Eyeballs readiness.
     *
     * @param addresses the resolved addresses (never empty)
     */
    void onResolved(List<InetAddress> addresses);

    /**
     * Called when hostname resolution fails entirely.
     *
     * @param error a description of the error
     */
    void onError(String error);

}
