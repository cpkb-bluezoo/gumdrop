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
 * Authentication and authorization shared by every protocol server
 * (HTTP, IMAP, POP3, SMTP, FTP).
 *
 * <p>{@link org.bluezoo.gumdrop.auth.Realm} is the contract every
 * authentication backend implements: password verification, role/group
 * membership, the challenge-response computations SASL mechanisms need
 * (CRAM-MD5, SCRAM, Digest), and token validation (OAuth, JWT, Bearer).
 * {@link org.bluezoo.gumdrop.auth.Realm#getSupportedSASLMechanisms}
 * lets a server only advertise mechanisms the configured realm can
 * actually handle. {@link org.bluezoo.gumdrop.auth.BasicRealm} is a
 * simple XML-backed realm for development and small deployments; {@link
 * org.bluezoo.gumdrop.auth.oauth.OAuthRealm} (in {@code
 * gumdrop-http.jar}) validates OAuth 2.0 access tokens via RFC 7662
 * introspection or local JWT validation; {@link
 * org.bluezoo.gumdrop.auth.ldap.LDAPRealm} (in {@code
 * gumdrop-ldap.jar}) authenticates against a directory server through
 * {@link org.bluezoo.gumdrop.ldap.client}.
 *
 * <p>{@link org.bluezoo.gumdrop.auth.SASLMechanism} enumerates the
 * supported SASL mechanisms; {@link org.bluezoo.gumdrop.auth.SASLUtils}
 * holds their shared cryptographic operations.
 *
 * <h2>SASL mechanisms supported</h2>
 *
 * <table border="1" cellpadding="5">
 *   <caption>SASL Mechanism Support</caption>
 *   <tr><th>Mechanism</th><th>RFC</th><th>Realm method</th></tr>
 *   <tr><td>PLAIN</td><td>RFC 4616</td><td>{@code passwordMatch()}</td></tr>
 *   <tr><td>LOGIN</td><td>(legacy)</td><td>{@code passwordMatch()}</td></tr>
 *   <tr><td>CRAM-MD5</td><td>RFC 2195</td><td>{@code getCramMD5Response()}</td></tr>
 *   <tr><td>DIGEST-MD5</td><td>RFC 2831</td><td>{@code getDigestHA1()}</td></tr>
 *   <tr><td>SCRAM-SHA-256</td><td>RFC 7677</td><td>{@code getScramCredentials()}</td></tr>
 *   <tr><td>OAUTHBEARER</td><td>RFC 7628</td><td>{@code validateBearerToken()}</td></tr>
 *   <tr><td>GSSAPI</td><td>RFC 4752</td><td>(Kerberos, external)</td></tr>
 *   <tr><td>EXTERNAL</td><td>RFC 4422</td><td>{@code userExists()}</td></tr>
 * </table>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see org.bluezoo.gumdrop.auth.BasicRealm
 * @see org.bluezoo.gumdrop.auth.SASLMechanism
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422 - SASL</a>
 * @see <a href="https://www.iana.org/assignments/sasl-mechanisms/">IANA SASL Mechanisms</a>
 */
package org.bluezoo.gumdrop.auth;
