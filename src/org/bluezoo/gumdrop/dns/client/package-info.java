/*
 * package-info.java
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

/**
 * Asynchronous, callback-based DNS resolver with a pluggable transport.
 *
 * <p>{@link org.bluezoo.gumdrop.dns.client.DNSResolver} is the client
 * applications use, consulting {@code /etc/hosts} ({@link
 * org.bluezoo.gumdrop.dns.client.HostsFile}) and {@code
 * /etc/resolv.conf} ({@link org.bluezoo.gumdrop.dns.client.ResolvConf})
 * before querying, and caching responses by TTL across query types
 * ({@link org.bluezoo.gumdrop.dns.client.DNSMultiQTypeCache}). The wire
 * transport is pluggable via {@link
 * org.bluezoo.gumdrop.dns.client.DNSClientTransport}: {@link
 * org.bluezoo.gumdrop.dns.client.UDPDNSClientTransport} (plain UDP, the
 * default, falling back to TCP on truncation), {@link
 * org.bluezoo.gumdrop.dns.client.TCPDNSClientTransport} (RFC 7766, with
 * {@link org.bluezoo.gumdrop.dns.client.TCPDNSConnectionPool} for
 * connection reuse), and {@link
 * org.bluezoo.gumdrop.dns.client.DoQClientTransport} (DNS-over-QUIC, RFC
 * 9250, over {@link org.bluezoo.gumdrop.quic}, pooled by {@link
 * org.bluezoo.gumdrop.dns.client.DoQConnectionPool}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.client.DNSResolver
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9250">RFC 9250 - DNS over QUIC</a>
 */
package org.bluezoo.gumdrop.dns.client;
