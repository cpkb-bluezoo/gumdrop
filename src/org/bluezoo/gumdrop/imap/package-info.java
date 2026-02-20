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
 * IMAP4rev2 server implementation for Gumdrop.
 *
 * <p>This package provides a complete IMAP server implementation following
 * RFC 9051 (IMAP4rev2), with support for essential extensions.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.imap.IMAPService} - Abstract base for
 *       IMAP application services; owns configuration and creates
 *       per-connection handlers</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.IMAPListener} - TCP transport listener for IMAP connections</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.IMAPProtocolHandler} - Endpoint handler for IMAP protocol logic</li>
 * </ul>
 *
 * <h2>Supported RFCs</h2>
 * <table border="1" cellpadding="5">
 *   <caption>IMAP RFC Support</caption>
 *   <tr><th>RFC</th><th>Title</th><th>Status</th></tr>
 *   <tr><td><a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051</a></td>
 *       <td>IMAP4rev2</td><td>Core implementation</td></tr>
 *   <tr><td><a href="https://www.rfc-editor.org/rfc/rfc2177">RFC 2177</a></td>
 *       <td>IDLE</td><td>Supported</td></tr>
 *   <tr><td><a href="https://www.rfc-editor.org/rfc/rfc2342">RFC 2342</a></td>
 *       <td>NAMESPACE</td><td>Supported</td></tr>
 *   <tr><td><a href="https://www.rfc-editor.org/rfc/rfc6851">RFC 6851</a></td>
 *       <td>MOVE</td><td>Supported</td></tr>
 *   <tr><td><a href="https://www.rfc-editor.org/rfc/rfc9208">RFC 9208</a></td>
 *       <td>QUOTA</td><td>Supported</td></tr>
 *   <tr><td><a href="https://www.rfc-editor.org/rfc/rfc7888">RFC 7888</a></td>
 *       <td>LITERAL+/LITERAL-</td><td>TODO</td></tr>
 *   <tr><td><a href="https://www.rfc-editor.org/rfc/rfc7162">RFC 7162</a></td>
 *       <td>CONDSTORE/QRESYNC</td><td>TODO</td></tr>
 * </table>
 *
 * <h2>Protocol States</h2>
 * <ul>
 *   <li><b>NOT_AUTHENTICATED</b> - Initial state, must authenticate</li>
 *   <li><b>AUTHENTICATED</b> - Logged in, can list/select mailboxes</li>
 *   <li><b>SELECTED</b> - Mailbox selected, can access messages</li>
 *   <li><b>LOGOUT</b> - Connection closing</li>
 * </ul>
 *
 * <h2>Security Features</h2>
 * <ul>
 *   <li>IMAPS (implicit TLS on port 993)</li>
 *   <li>STARTTLS for connection upgrade</li>
 *   <li>SASL authentication mechanisms via {@link org.bluezoo.gumdrop.auth}</li>
 *   <li>LOGINDISABLED until TLS established</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * IMAPListener imap = new IMAPListener();
 * imap.setPort(143);
 * imap.setRealm(myRealm);
 * imap.setMailboxFactory(new MboxMailboxFactory(new File("/var/mail")));
 * 
 * // Optional: Enable implicit TLS
 * IMAPListener imaps = new IMAPListener();
 * imaps.setPort(993);
 * imaps.setSecure(true);
 * imaps.setSSLContext(sslContext);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051 - IMAP4rev2</a>
 * @see org.bluezoo.gumdrop.mailbox
 * @see org.bluezoo.gumdrop.auth
 */
package org.bluezoo.gumdrop.imap;

