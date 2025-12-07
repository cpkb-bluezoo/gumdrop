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
 * SASL (Simple Authentication and Security Layer) support for Gumdrop servers.
 *
 * <p>This package provides shared SASL authentication utilities used by
 * POP3, IMAP, SMTP, and potentially other protocol implementations.
 *
 * <h2>Supported Mechanisms</h2>
 * <table border="1" cellpadding="5">
 *   <caption>SASL Mechanism Support</caption>
 *   <tr><th>Mechanism</th><th>RFC</th><th>Description</th></tr>
 *   <tr><td>PLAIN</td><td>RFC 4616</td><td>Simple credentials, requires TLS</td></tr>
 *   <tr><td>LOGIN</td><td>(legacy)</td><td>Base64 username/password</td></tr>
 *   <tr><td>CRAM-MD5</td><td>RFC 2195</td><td>Challenge-response with MD5</td></tr>
 *   <tr><td>DIGEST-MD5</td><td>RFC 2831</td><td>HTTP Digest-style authentication</td></tr>
 *   <tr><td>SCRAM-SHA-256</td><td>RFC 7677</td><td>Salted challenge-response</td></tr>
 *   <tr><td>OAUTHBEARER</td><td>RFC 7628</td><td>OAuth 2.0 Bearer tokens</td></tr>
 *   <tr><td>GSSAPI</td><td>RFC 4752</td><td>Kerberos/GSS-API</td></tr>
 *   <tr><td>EXTERNAL</td><td>RFC 4422</td><td>TLS client certificate</td></tr>
 *   <tr><td>NTLM</td><td>(MS)</td><td>Windows domain authentication</td></tr>
 * </table>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Parse PLAIN credentials
 * byte[] decoded = SASLUtils.decodeBase64(credentials);
 * String[] parts = SASLUtils.parsePlainCredentials(decoded);
 * String username = parts[1];
 * String password = parts[2];
 *
 * // Generate CRAM-MD5 challenge
 * String challenge = SASLUtils.generateCramMD5Challenge(hostname);
 * String challengeB64 = SASLUtils.encodeBase64(challenge);
 *
 * // Verify CRAM-MD5 response
 * boolean valid = SASLUtils.verifyCramMD5(response, challenge, password);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4422">RFC 4422 - SASL</a>
 * @see <a href="https://www.iana.org/assignments/sasl-mechanisms/">IANA SASL Mechanisms</a>
 */
package org.bluezoo.gumdrop.sasl;

