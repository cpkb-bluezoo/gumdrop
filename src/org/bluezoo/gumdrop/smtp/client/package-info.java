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
 * Non-blocking SMTP client for sending outbound email.
 *
 * <p>This package provides an asynchronous, event-driven SMTP client for
 * sending email messages, with support for STARTTLS, SASL authentication,
 * and transparent BDAT (CHUNKING) support.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.smtp.client.SMTPClient} - Creates client
 *       connections to remote SMTP servers</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.client.SMTPClientConnection} -
 *       Handles SMTP protocol exchanges with automatic dot-stuffing or BDAT</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.client.handler.ServerGreeting} -
 *       Entry point callback interface for receiving the initial greeting</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.client.handler.ClientHelloState} -
 *       State interface after EHLO/HELO for envelope commands</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelopeState} -
 *       State interface during envelope (MAIL FROM, RCPT TO, DATA)</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp.client.handler.ClientMessageData} -
 *       Interface for streaming message content</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Non-blocking I/O using the shared SelectorLoop</li>
 *   <li>STARTTLS support for upgrading to encrypted connections</li>
 *   <li>SASL authentication (PLAIN, LOGIN, CRAM-MD5, XOAUTH2)</li>
 *   <li>Streaming message content without memory buffering</li>
 *   <li>Automatic dot-stuffing for DATA or transparent BDAT when available</li>
 *   <li>Type-safe stateful handler pattern enforcing correct command sequences</li>
 * </ul>
 *
 * <h2>Stateful Handler Pattern</h2>
 *
 * <p>The SMTP client uses a stateful handler pattern where different interfaces
 * are provided at each stage of the protocol, ensuring that only valid commands
 * can be issued at each point. This provides compile-time safety against
 * protocol violations.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create client for target server
 * SMTPClient client = new SMTPClient(selectorLoop, "mail.example.com", 25);
 *
 * // Connect with handler
 * client.connect(new ServerGreeting() {
 *
 *     public void onConnected(ConnectionInfo info) {
 *         // TCP connected, waiting for greeting
 *     }
 *
 *     public void handleGreeting(ServerGreeting greeting) {
 *         // Server greeting received, send EHLO
 *         greeting.ehlo("myhost.example.org", new ServerHelloReplyHandler() {
 *             public void handleEhloOk(EHLOCapabilities caps, ClientEnvelopeReady envelope) {
 *                 // EHLO succeeded, start transaction
 *                 envelope.mailFrom(sender, new ServerMailReplyHandler() {
 *                     public void handleMailOk(ClientEnvelopeState state) {
 *                         state.rcptTo(recipient, rcptHandler);
 *                     }
 *                     // ... error handlers
 *                 });
 *             }
 *             // ... error handlers
 *         });
 *     }
 *
 *     public void handleServiceNotAvailable() {
 *         // Server not accepting connections
 *     }
 *
 *     public void onDisconnected() {
 *         // Connection closed
 *     }
 *
 *     public void onError(Exception e) {
 *         // Handle error
 *     }
 *
 *     public void onTLSStarted(TLSInfo info) {
 *         // TLS handshake complete
 *     }
 * });
 * }</pre>
 *
 * <h2>STARTTLS</h2>
 *
 * <p>To upgrade to TLS, use the {@code starttls()} method on {@code ClientHelloState}
 * after receiving EHLO capabilities. When the server accepts and TLS handshake
 * completes, the handler receives a new {@code ClientHelloState} and must
 * re-issue EHLO per RFC requirements.
 *
 * <h2>BDAT (CHUNKING)</h2>
 *
 * <p>If the server advertises CHUNKING support in EHLO, the client automatically
 * uses BDAT for message transmission instead of DATA. This is transparent to the
 * handler - the same {@code ClientMessageData} interface is used in both cases.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.client.SMTPClient
 * @see org.bluezoo.gumdrop.smtp
 */
package org.bluezoo.gumdrop.smtp.client;
