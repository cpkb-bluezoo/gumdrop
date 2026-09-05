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
 * DNS-over-HTTPS (RFC 8484) client transport.
 *
 * <p>{@link org.bluezoo.gumdrop.http.doh.DoHClientTransport} implements
 * {@link org.bluezoo.gumdrop.dns.client.DNSClientTransport} by sending
 * the raw DNS wire-format query as an HTTP POST body (content type
 * {@code application/dns-message}, RFC 8484 section 4.1) over {@link
 * org.bluezoo.gumdrop.http.client.HTTPClient}, to a configurable URI
 * template path (default {@code /dns-query}) on port 443.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.client
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8484">RFC 8484 - DNS Queries over HTTPS</a>
 */
package org.bluezoo.gumdrop.http.doh;
