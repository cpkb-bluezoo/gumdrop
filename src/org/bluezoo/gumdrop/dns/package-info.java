/*
 * package-info.java
 * Copyright (C) 2025, 2026 Chris Burdess
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
 * DNS service: resolve locally, proxy to upstream servers, and cache
 * responses respecting TTL. {@link org.bluezoo.gumdrop.dns.DNSService}
 * owns configuration, caching, and resolution logic, overridable for
 * custom name resolution. Three transport listeners share it: {@link
 * org.bluezoo.gumdrop.dns.DNSListener} for plain UDP queries, {@link
 * org.bluezoo.gumdrop.dns.DoTListener} for DNS-over-TLS (RFC 7858), and
 * {@link org.bluezoo.gumdrop.dns.DoQListener} for DNS-over-QUIC (RFC
 * 9250, over {@link org.bluezoo.gumdrop.quic}).
 *
 * <p>{@link org.bluezoo.gumdrop.dns.DNSResourceRecord} provides factory
 * methods for the common record types (A, AAAA, CNAME, MX, NS, PTR, SOA,
 * TXT). {@link org.bluezoo.gumdrop.mdns} builds on this package's
 * message format for multicast DNS and DNS-SD.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.DNSService
 * @see org.bluezoo.gumdrop.dns.DNSMessage
 * @see org.bluezoo.gumdrop.dns.DNSResourceRecord
 * @see org.bluezoo.gumdrop.dns.client
 * @see org.bluezoo.gumdrop.mdns
 */
package org.bluezoo.gumdrop.dns;
