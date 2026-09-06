/*
 * DoHTransportFactory.java
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

/**
 * SPI for creating a DNS-over-HTTPS (RFC 8484) {@link DNSClientTransport}.
 *
 * <p>The implementation ({@code org.bluezoo.gumdrop.http.doh.DoHClientTransport},
 * via a small adapter) lives in {@code gumdrop-http.jar} and is discovered
 * via {@link java.util.ServiceLoader} -- core, where {@link DNSResolver}
 * lives, cannot depend on the HTTP client stack directly, since DoH is
 * itself built on it. When no provider is on the classpath (a core-only
 * deployment without the HTTP module), {@link DNSResolver} simply skips
 * DoH in its transport preference order.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSResolver
 */
public interface DoHTransportFactory {

    /**
     * Creates a new DoH transport instance.
     *
     * @param path the RFC 8484 §4.1 URI template path to use (e.g.
     *             {@code "/dns-query"}), or null for the implementation's default
     * @return a new transport instance
     */
    DNSClientTransport createTransport(String path);

}
