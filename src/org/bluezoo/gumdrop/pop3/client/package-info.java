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
 * Non-blocking POP3 client for accessing remote mailboxes.
 *
 * <p>This package provides an asynchronous, event-driven POP3 client for
 * retrieving email messages, with support for STLS (explicit TLS),
 * SASL authentication, APOP, and streaming message content via ByteBuffer
 * chunks.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.POP3Client} -
 *       High-level facade for connecting to POP3 servers</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.POP3ClientProtocolHandler} -
 *       Handles POP3 protocol exchanges with transparent dot-unstuffing</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.handler.ServerGreeting} -
 *       Entry point callback interface for receiving the initial greeting</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.handler.ClientAuthorizationState} -
 *       State interface for AUTHORIZATION commands (CAPA, USER, APOP, AUTH, STLS)</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.handler.ClientTransactionState} -
 *       State interface for TRANSACTION commands (STAT, LIST, RETR, DELE, etc.)</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Non-blocking I/O using the shared SelectorLoop</li>
 *   <li>STLS support for upgrading to encrypted connections (RFC 2595)</li>
 *   <li>Implicit TLS (POP3S, port 995)</li>
 *   <li>SASL authentication (RFC 5034)</li>
 *   <li>APOP digest authentication (RFC 1939)</li>
 *   <li>Streaming message content without memory buffering</li>
 *   <li>Transparent dot-unstuffing for RETR and TOP responses</li>
 *   <li>Type-safe stateful handler pattern enforcing correct command sequences</li>
 *   <li>Async DNS resolution via the gumdrop DNSResolver</li>
 * </ul>
 *
 * <h2>Stateful Handler Pattern</h2>
 *
 * <p>The POP3 client uses a stateful handler pattern where different interfaces
 * are provided at each stage of the protocol, ensuring that only valid commands
 * can be issued at each point. This provides compile-time safety against
 * protocol violations.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * POP3Client client = new POP3Client(selectorLoop, "pop.example.com", 110);
 * client.setSSLContext(sslContext);
 * client.connect(new ServerGreeting() {
 *
 *     public void handleGreeting(ClientAuthorizationState auth,
 *                                String message, String apopTimestamp) {
 *         auth.capa(new ServerCapaReplyHandler() {
 *             public void handleCapabilities(ClientAuthorizationState auth,
 *                     boolean stls, List<String> saslMechanisms,
 *                     boolean top, boolean uidl, boolean user,
 *                     boolean pipelining, String implementation) {
 *                 if (stls) {
 *                     auth.stls(stlsHandler);
 *                 } else {
 *                     auth.user("alice", userHandler);
 *                 }
 *             }
 *             public void handleError(ClientAuthorizationState auth,
 *                                     String message) {
 *                 auth.user("alice", userHandler);
 *             }
 *             public void handleServiceClosing(String message) { }
 *         });
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
 * @see org.bluezoo.gumdrop.pop3.client.POP3Client
 * @see org.bluezoo.gumdrop.pop3
 */
package org.bluezoo.gumdrop.pop3.client;
