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
 * Asynchronous LDAP client (RFC 4511).
 *
 * <p>Different state interfaces are provided at each stage of the
 * session, enforcing valid sequencing at compile time -- {@link
 * org.bluezoo.gumdrop.ldap.client.LDAPConnectionReady} (entry point) to
 * {@link org.bluezoo.gumdrop.ldap.client.LDAPConnected} (bind or
 * STARTTLS available) to, after STARTTLS, {@link
 * org.bluezoo.gumdrop.ldap.client.LDAPPostTLS} (bind only), finally
 * {@link org.bluezoo.gumdrop.ldap.client.LDAPSession} once bound, where
 * the full directory operation set (search, modify, add, delete,
 * compare, modify DN, extended operations) becomes available. Each
 * operation has a matching result handler interface, e.g. {@link
 * org.bluezoo.gumdrop.ldap.client.SearchResultHandler} delivers entries
 * as they arrive and a final completion callback rather than
 * accumulating the result set. {@link
 * org.bluezoo.gumdrop.ldap.client.LDAPResultCode} enumerates the RFC
 * 4511 section 4.1.9 result codes every operation's outcome is reported
 * against.
 *
 * <p>TLS is either implicit (LDAPS, port 636, via {@code setSecure}) or
 * negotiated in-band via STARTTLS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ldap.asn1
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511 - LDAP Protocol</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4513">RFC 4513 - LDAP Authentication</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4515">RFC 4515 - LDAP Search Filters</a>
 */
package org.bluezoo.gumdrop.ldap.client;
