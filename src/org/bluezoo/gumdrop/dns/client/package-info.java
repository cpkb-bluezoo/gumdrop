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
 * Asynchronous DNS client for Gumdrop.
 *
 * <p>This package provides a non-blocking DNS resolver with pluggable
 * transport support:
 * <ul>
 * <li>{@link org.bluezoo.gumdrop.dns.client.DNSResolver} - Main resolver
 *     with callback-based query API</li>
 * <li>{@link org.bluezoo.gumdrop.dns.client.DNSClientTransport} - Transport
 *     interface for pluggable wire protocols</li>
 * <li>{@link org.bluezoo.gumdrop.dns.client.UDPDNSClientTransport} - Plain
 *     UDP transport (default)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.client.DNSResolver
 */
package org.bluezoo.gumdrop.dns.client;
