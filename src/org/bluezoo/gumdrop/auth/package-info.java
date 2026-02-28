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
 * Authentication and authorization framework for Gumdrop servers.
 *
 * <p>This package provides the identity and access management (IAM) infrastructure
 * used across all Gumdrop server protocols including HTTP, IMAP, POP3, SMTP, and FTP.
 *
 * <h2>Core Components</h2>
 *
 * <h3>Realm Interface</h3>
 * <p>The {@link org.bluezoo.gumdrop.auth.Realm} interface defines the contract for
 * authentication backends. Realm implementations handle:</p>
 * <ul>
 *   <li>Password verification ({@link org.bluezoo.gumdrop.auth.Realm#passwordMatch})</li>
 *   <li>Role/group membership ({@link org.bluezoo.gumdrop.auth.Realm#isUserInRole})</li>
 *   <li>Challenge-response computations (CRAM-MD5, SCRAM, Digest)</li>
 *   <li>Token validation (OAuth, JWT, Bearer)</li>
 *   <li>SASL mechanism capability declaration</li>
 * </ul>
 *
 * <h3>BasicRealm</h3>
 * <p>{@link org.bluezoo.gumdrop.auth.BasicRealm} is a simple XML-based realm
 * implementation for development and small deployments. It stores passwords
 * in plaintext and supports all authentication mechanisms.</p>
 *
 * <h3>OAuthRealm</h3>
 * <p>{@link org.bluezoo.gumdrop.auth.OAuthRealm} validates OAuth 2.0 access tokens
 * using RFC 7662 token introspection. It supports Bearer token authentication
 * and configurable scope-to-role mapping for authorization decisions.</p>
 *
 * <h3>SASL Support</h3>
 * <p>The package provides comprehensive SASL (Simple Authentication and Security Layer)
 * support through:</p>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.auth.SASLMechanism} - Enumeration of supported mechanisms</li>
 *   <li>{@link org.bluezoo.gumdrop.auth.SASLUtils} - Cryptographic utilities for SASL</li>
 * </ul>
 *
 * <h2>Supported Authentication Mechanisms</h2>
 *
 * <table border="1" cellpadding="5">
 *   <caption>SASL Mechanism Support</caption>
 *   <tr><th>Mechanism</th><th>RFC</th><th>Description</th><th>Realm Method</th></tr>
 *   <tr><td>PLAIN</td><td>RFC 4616</td><td>Simple credentials (requires TLS)</td><td>{@code passwordMatch()}</td></tr>
 *   <tr><td>LOGIN</td><td>(legacy)</td><td>Base64 username/password</td><td>{@code passwordMatch()}</td></tr>
 *   <tr><td>CRAM-MD5</td><td>RFC 2195</td><td>Challenge-response with HMAC-MD5</td><td>{@code getCramMD5Response()}</td></tr>
 *   <tr><td>DIGEST-MD5</td><td>RFC 2831</td><td>HTTP Digest-style authentication</td><td>{@code getDigestHA1()}</td></tr>
 *   <tr><td>SCRAM-SHA-256</td><td>RFC 7677</td><td>Salted challenge-response</td><td>{@code getScramCredentials()}</td></tr>
 *   <tr><td>OAUTHBEARER</td><td>RFC 7628</td><td>OAuth 2.0 Bearer tokens</td><td>{@code validateBearerToken()}</td></tr>
 *   <tr><td>GSSAPI</td><td>RFC 4752</td><td>Kerberos/GSS-API</td><td>(external)</td></tr>
 *   <tr><td>EXTERNAL</td><td>RFC 4422</td><td>TLS client certificate</td><td>{@code userExists()}</td></tr>
 * </table>
 *
 * <h2>Realm Capability Discovery</h2>
 *
 * <p>Servers should query {@link org.bluezoo.gumdrop.auth.Realm#getSupportedSASLMechanisms()}
 * to determine which authentication mechanisms to advertise. This ensures that servers
 * only offer mechanisms the configured realm can actually handle.</p>
 *
 * <pre>{@code
 * Realm realm = server.getRealm();
 * Set<SASLMechanism> supported = realm.getSupportedSASLMechanisms();
 *
 * // Build capability response
 * StringBuilder auth = new StringBuilder("AUTH");
 * for (SASLMechanism mech : supported) {
 *     if (!mech.requiresTLS() || connection.isSecure()) {
 *         auth.append(" ").append(mech.getMechanismName());
 *     }
 * }
 * }</pre>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <realm id="myRealm" class="org.bluezoo.gumdrop.auth.BasicRealm"
 *        href="users.xml"/>
 *
 * <server class="org.bluezoo.gumdrop.imap.IMAPListener"
 *         port="993" secure="true"
 *         realm="#myRealm"/>
 * }</pre>
 *
 * <h2>Implementing Custom Realms</h2>
 *
 * <p>To integrate with external identity providers (LDAP, database, OAuth provider),
 * implement the {@link org.bluezoo.gumdrop.auth.Realm} interface:</p>
 *
 * <pre>{@code
 * public class LDAPRealm implements Realm {
 *     // LDAP can only do bind authentication
 *     private static final Set<SASLMechanism> SUPPORTED =
 *         Collections.unmodifiableSet(EnumSet.of(
 *             SASLMechanism.PLAIN,
 *             SASLMechanism.LOGIN
 *         ));
 *
 *     public Set<SASLMechanism> getSupportedSASLMechanisms() {
 *         return SUPPORTED;
 *     }
 *
 *     public boolean passwordMatch(String username, String password) {
 *         // Perform LDAP bind
 *         return ldapContext.bind(username, password);
 *     }
 *
 *     // Challenge-response methods throw UnsupportedOperationException
 *     // because LDAP stores hashed passwords
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see org.bluezoo.gumdrop.auth.BasicRealm
 * @see org.bluezoo.gumdrop.auth.SASLMechanism
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422 - SASL</a>
 * @see <a href="https://www.iana.org/assignments/sasl-mechanisms/">IANA SASL Mechanisms</a>
 */
package org.bluezoo.gumdrop.auth;

