/*
 * ServerGreeting.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

import org.bluezoo.gumdrop.ClientHandler;

/**
 * Handler interface for receiving the initial SMTP server greeting.
 * 
 * <p>This is the entry point for SMTP client handlers. When connecting to an
 * SMTP server, the handler passed to {@code SMTPClient.connect()} must implement
 * this interface to receive the server's initial greeting and begin the session.
 * 
 * <p>After receiving the greeting, the handler should issue either EHLO (for
 * Extended SMTP) or HELO (for basic SMTP) to establish the session. The
 * {@code esmtp} parameter indicates whether the server advertised ESMTP support
 * in its greeting, which helps the handler decide which command to use.
 * 
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * public class MyMailHandler implements ServerGreeting {
 *     
 *     public void handleGreeting(ClientHelloState hello, String message, boolean esmtp) {
 *         if (esmtp) {
 *             hello.ehlo("my.hostname.com", new MyEhloHandler());
 *         } else {
 *             hello.helo("my.hostname.com", new MyHeloHandler());
 *         }
 *     }
 *     
 *     public void handleServiceUnavailable(String message) {
 *         log.error("Server unavailable: {}", message);
 *     }
 *     
 *     // ... ClientHandler methods ...
 * }
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientHelloState
 * @see ClientHandler
 */
public interface ServerGreeting extends ClientHandler {

    /**
     * Called when the server sends a successful greeting (220).
     * 
     * <p>The handler should respond by issuing either EHLO or HELO to
     * establish the session. Use the {@code esmtp} parameter to determine
     * which command is appropriate.
     * 
     * @param hello operations available to begin the session
     * @param message the greeting text (e.g., "mail.example.com ESMTP ready")
     * @param esmtp true if the server advertised ESMTP support in the greeting
     */
    void handleGreeting(ClientHelloState hello, String message, boolean esmtp);

    /**
     * Called when the server is not accepting connections.
     * 
     * <p>This is invoked for non-220 greeting responses (typically 421).
     * The connection will be closed after this callback returns.
     * 
     * @param message the server's rejection message
     */
    void handleServiceUnavailable(String message);

}

