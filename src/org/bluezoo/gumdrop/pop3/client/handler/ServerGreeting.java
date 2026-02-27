/*
 * ServerGreeting.java
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

package org.bluezoo.gumdrop.pop3.client.handler;

import org.bluezoo.gumdrop.ClientHandler;

/**
 * Handler interface for receiving the initial POP3 server greeting.
 *
 * <p>This is the entry point for POP3 client handlers. When connecting to a
 * POP3 server, the handler passed to {@code POP3Client.connect()} must
 * implement this interface to receive the server's initial greeting and
 * begin the session.
 *
 * <p>After receiving the greeting, the handler should typically issue CAPA
 * to discover server capabilities, then authenticate using USER/PASS, APOP,
 * or SASL AUTH. If the server included an APOP timestamp in the greeting,
 * it is provided separately for use with APOP authentication.
 *
 * <p><strong>Example usage:</strong>
 * <pre>{@code
 * public class MyPOP3Handler implements ServerGreeting {
 *
 *     public void handleGreeting(ClientAuthorizationState auth,
 *                                String message, String apopTimestamp) {
 *         auth.capa(new MyCapaHandler());
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
 * @see ClientAuthorizationState
 * @see ClientHandler
 */
public interface ServerGreeting extends ClientHandler {

    /**
     * Called when the server sends a successful greeting (+OK).
     *
     * <p>The handler should proceed with the POP3 session, typically by
     * issuing CAPA to discover capabilities, then authenticating.
     *
     * @param auth operations available in the AUTHORIZATION state
     * @param message the greeting text after +OK
     * @param apopTimestamp the APOP timestamp from the greeting, or null
     *                      if the server did not include one
     */
    void handleGreeting(ClientAuthorizationState auth, String message,
                        String apopTimestamp);

    /**
     * Called when the server is not accepting connections (-ERR).
     *
     * <p>The connection will be closed after this callback returns.
     *
     * @param message the server's rejection message
     */
    void handleServiceUnavailable(String message);

}
