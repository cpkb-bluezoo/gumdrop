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
 * LDAP (Lightweight Directory Access Protocol) support for Gumdrop.
 *
 * <p>This package provides LDAP functionality for the Gumdrop server framework,
 * enabling integration with directory services for authentication and
 * authorization.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client} - Asynchronous LDAP client</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.asn1} - ASN.1 BER/DER codec</li>
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 *
 * <h3>LDAP Realm for Authentication</h3>
 * <p>The primary use case is implementing an LDAP-backed
 * {@link org.bluezoo.gumdrop.auth.Realm} that authenticates users against
 * a directory server (Active Directory, OpenLDAP, etc.).</p>
 *
 * <h3>Directory Lookups</h3>
 * <p>The LDAP client can also be used for general directory queries,
 * such as looking up user attributes, group memberships, or
 * organizational information.</p>
 *
 * <h2>Architecture</h2>
 *
 * <p>The LDAP implementation follows Gumdrop's asynchronous, non-blocking
 * design principles:</p>
 *
 * <ul>
 *   <li><b>Event-driven</b> - All operations use callbacks, no blocking I/O</li>
 *   <li><b>Connection pooling</b> - Efficient reuse of LDAP connections</li>
 *   <li><b>TLS support</b> - LDAPS and STARTTLS for secure connections</li>
 *   <li><b>SASL authentication</b> - Multiple bind mechanisms supported</li>
 * </ul>
 *
 * <h2>Supported RFCs</h2>
 *
 * <ul>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4510">RFC 4510</a> - LDAP Technical Specification Road Map</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511</a> - LDAP Protocol</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4512">RFC 4512</a> - LDAP Directory Information Models</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4513">RFC 4513</a> - LDAP Authentication Methods</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4514">RFC 4514</a> - LDAP String Representation of DNs</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4515">RFC 4515</a> - LDAP String Representation of Search Filters</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4516">RFC 4516</a> - LDAP Uniform Resource Locator</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4517">RFC 4517</a> - LDAP Syntaxes and Matching Rules</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4518">RFC 4518</a> - LDAP Internationalized String Preparation</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4519">RFC 4519</a> - LDAP Schema for User Applications</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc4522">RFC 4522</a> - LDAP Binary Encoding Option</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ldap.client
 * @see org.bluezoo.gumdrop.ldap.asn1
 * @see org.bluezoo.gumdrop.auth.Realm
 */
package org.bluezoo.gumdrop.ldap;


