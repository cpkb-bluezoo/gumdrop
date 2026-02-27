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
 * Non-blocking IMAP client for accessing remote mailboxes.
 *
 * <p>This package provides an asynchronous, event-driven IMAP client for
 * accessing email messages, with support for STARTTLS (explicit TLS),
 * implicit TLS (IMAPS), SASL authentication, tagged command tracking,
 * FETCH with literal streaming, APPEND, IDLE, and unsolicited mailbox
 * event delivery.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.IMAPClient} -
 *       High-level facade for connecting to IMAP servers</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.IMAPClientProtocolHandler} -
 *       Handles IMAP protocol exchanges with tagged command tracking and
 *       literal byte-counting</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.handler.ServerGreeting} -
 *       Entry point callback interface for receiving the initial greeting</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.handler.ClientNotAuthenticatedState} -
 *       State interface for NOT AUTHENTICATED commands</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.handler.ClientAuthenticatedState} -
 *       State interface for AUTHENTICATED commands</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.handler.ClientSelectedState} -
 *       State interface for SELECTED commands</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.MailboxInfo} -
 *       Selected mailbox metadata from SELECT/EXAMINE</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.FetchData} -
 *       Structured FETCH response data per message</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Non-blocking I/O using the shared SelectorLoop</li>
 *   <li>STARTTLS support for upgrading to encrypted connections (RFC 2595)</li>
 *   <li>Implicit TLS (IMAPS, port 993)</li>
 *   <li>SASL authentication (RFC 4959)</li>
 *   <li>LOGIN authentication</li>
 *   <li>Tagged command tracking with auto-generated tags</li>
 *   <li>Streaming FETCH body literal data via ByteBuffer chunks</li>
 *   <li>APPEND with continuation and content streaming</li>
 *   <li>IDLE for server-push mailbox notifications</li>
 *   <li>Unsolicited mailbox event delivery via MailboxEventListener</li>
 *   <li>Type-safe stateful handler pattern enforcing correct command sequences</li>
 *   <li>Async DNS resolution via the gumdrop DNSResolver</li>
 * </ul>
 *
 * <h2>Stateful Handler Pattern</h2>
 *
 * <p>The IMAP client uses a stateful handler pattern where different interfaces
 * are provided at each stage of the protocol, ensuring that only valid commands
 * can be issued at each point. This provides compile-time safety against
 * protocol violations.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * IMAPClient client = new IMAPClient(selectorLoop, "imap.example.com", 143);
 * client.setSSLContext(sslContext);
 * client.connect(new ServerGreeting() {
 *
 *     public void handleGreeting(ClientNotAuthenticatedState auth,
 *                                String greeting,
 *                                List<String> preAuthCapabilities) {
 *         auth.login("alice", "secret", new ServerLoginReplyHandler() {
 *             public void handleAuthenticated(ClientAuthenticatedState session,
 *                                             List<String> capabilities) {
 *                 session.select("INBOX", selectHandler);
 *             }
 *             public void handleAuthFailed(ClientNotAuthenticatedState auth,
 *                                          String message) {
 *                 auth.logout();
 *             }
 *             public void handleServiceClosing(String message) { }
 *         });
 *     }
 *
 *     public void handlePreAuthenticated(ClientAuthenticatedState auth,
 *                                        String greeting) {
 *         auth.select("INBOX", selectHandler);
 *     }
 *
 *     public void handleServiceUnavailable(String message) { }
 *     public void onConnected(Endpoint endpoint) { }
 *     public void onSecurityEstablished(SecurityInfo info) { }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.imap.client.IMAPClient
 * @see org.bluezoo.gumdrop.imap
 */
package org.bluezoo.gumdrop.imap.client;
