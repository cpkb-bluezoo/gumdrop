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
 * LDAP (RFC 4510-4519) support: directory-backed authentication and
 * general directory queries.
 *
 * <p>The primary use is an LDAP-backed {@link
 * org.bluezoo.gumdrop.auth.Realm} authenticating against a directory
 * server (Active Directory, OpenLDAP, etc.), but {@link
 * org.bluezoo.gumdrop.ldap.client} can equally be used directly for
 * attribute lookups, group membership checks, or other directory
 * queries.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client} - asynchronous LDAP client</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.asn1} - ASN.1 BER/DER codec</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ldap.client
 * @see org.bluezoo.gumdrop.ldap.asn1
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511 - LDAP Protocol</a>
 */
package org.bluezoo.gumdrop.ldap;
