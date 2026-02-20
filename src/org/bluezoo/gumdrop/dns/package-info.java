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
 * DNS service implementation for Gumdrop.
 *
 * <p>This package provides a DNS service that can:
 * <ul>
 * <li>Resolve queries locally via custom implementation</li>
 * <li>Proxy queries to upstream DNS servers</li>
 * <li>Cache responses respecting TTL</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.dns.DNSService} - Application service
 *       owning configuration, caching, and query resolution logic</li>
 *   <li>{@link org.bluezoo.gumdrop.dns.DNSListener} - UDP transport
 *       listener for standard DNS queries</li>
 *   <li>{@link org.bluezoo.gumdrop.dns.DoTListener} - TCP/TLS
 *       transport listener for DNS-over-TLS (RFC 7858)</li>
 *   <li>{@link org.bluezoo.gumdrop.dns.DoQListener} - QUIC
 *       transport listener for DNS-over-QUIC (RFC 9250)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p>The simplest way to run a DNS proxy service:
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.dns.DNSService">
 *   <property name="upstream-servers">8.8.8.8 1.1.1.1</property>
 *   <listener class="org.bluezoo.gumdrop.dns.DNSListener"
 *           port="5353"/>
 * </service>
 * }</pre>
 *
 * <h2>Custom Resolution</h2>
 *
 * <p>To implement custom name resolution, subclass
 * {@link org.bluezoo.gumdrop.dns.DNSService} and override the
 * {@code resolve()} method:
 *
 * <pre>{@code
 * public class InternalDNSService extends DNSService {
 *     @Override
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
 * }</pre>
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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.DNSService
 * @see org.bluezoo.gumdrop.dns.DNSMessage
 * @see org.bluezoo.gumdrop.dns.DNSResourceRecord
 */
package org.bluezoo.gumdrop.dns;
