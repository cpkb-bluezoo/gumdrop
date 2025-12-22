/*
 * package-info.java
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

/**
 * DNS server implementation for Gumdrop.
 *
 * <p>This package provides a full DNS server that can:
 * <ul>
 * <li>Resolve queries locally via custom implementation</li>
 * <li>Proxy queries to upstream DNS servers</li>
 * <li>Cache responses respecting TTL</li>
 * <li>Support DTLS for secure DNS</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p>The simplest way to run a DNS proxy server is to configure it in gumdroprc:
 * <pre>
 * dns = org.bluezoo.gumdrop.dns.DNSServer {
 *     port = 5353
 *     upstreamServers = "8.8.8.8 1.1.1.1"
 * }
 * </pre>
 *
 * <h2>Custom Resolution</h2>
 *
 * <p>To implement custom name resolution, subclass {@link org.bluezoo.gumdrop.dns.DNSServer}
 * and override the {@code resolve()} method:
 *
 * <pre>
 * public class InternalDNSServer extends DNSServer {
 *     &#64;Override
 *     protected DNSMessage resolve(DNSMessage query) {
 *         DNSQuestion q = query.getQuestions().get(0);
 *
 *         // Handle internal domains
 *         if (q.getName().endsWith(".internal")) {
 *             InetAddress addr = lookupInternal(q.getName());
 *             if (addr != null) {
 *                 List answers = new ArrayList();
 *                 answers.add(DNSResourceRecord.a(q.getName(), 3600, addr));
 *                 return query.createResponse(answers);
 *             }
 *             return query.createErrorResponse(DNSMessage.RCODE_NXDOMAIN);
 *         }
 *
 *         // Let everything else go to upstream
 *         return null;
 *     }
 * }
 * </pre>
 *
 * <h2>Record Types</h2>
 *
 * <p>The following record types are supported:
 * <ul>
 * <li>A - IPv4 address</li>
 * <li>AAAA - IPv6 address</li>
 * <li>CNAME - Canonical name (alias)</li>
 * <li>MX - Mail exchange</li>
 * <li>NS - Name server</li>
 * <li>PTR - Pointer (reverse DNS)</li>
 * <li>SOA - Start of authority</li>
 * <li>TXT - Text record</li>
 * </ul>
 *
 * <p>See {@link org.bluezoo.gumdrop.dns.DNSResourceRecord} for convenience
 * factory methods to create these record types.
 *
 * <h2>DTLS Support</h2>
 *
 * <p>To enable DNS over DTLS, configure SSL properties on the server:
 * <pre>
 * dns = org.bluezoo.gumdrop.dns.DNSServer {
 *     port = 853
 *     secure = true
 *     keyStore = /path/to/keystore.p12
 *     keyStorePassword = secret
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.DNSServer
 * @see org.bluezoo.gumdrop.dns.DNSMessage
 * @see org.bluezoo.gumdrop.dns.DNSResourceRecord
 */
package org.bluezoo.gumdrop.dns;

