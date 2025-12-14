/*
 * ServerAuthReplyHandler.java
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

/**
 * Handler for AUTH command responses.
 * 
 * <p>This handler receives responses during SASL authentication, which may
 * involve multiple challenge-response rounds depending on the mechanism.
 * 
 * <p><strong>Single-round mechanisms (e.g., PLAIN):</strong>
 * <pre>{@code
 * // PLAIN sends credentials in initial response
 * byte[] credentials = buildPlainCredentials(user, pass);
 * session.auth("PLAIN", credentials, new ServerAuthReplyHandler() {
 *     public void handleAuthSuccess(ClientSession session) {
 *         // Authenticated, proceed with mailFrom()
 *     }
 *     // ... other methods
 * });
 * }</pre>
 * 
 * <p><strong>Multi-round mechanisms (e.g., LOGIN, CRAM-MD5):</strong>
 * <pre>{@code
 * session.auth("LOGIN", null, new ServerAuthReplyHandler() {
 *     private boolean sentUsername = false;
 *     
 *     public void handleChallenge(byte[] challenge, ClientAuthExchange exchange) {
 *         if (!sentUsername) {
 *             exchange.respond(username.getBytes(), this);
 *             sentUsername = true;
 *         } else {
 *             exchange.respond(password.getBytes(), this);
 *         }
 *     }
 *     // ... other methods
 * });
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientSession#auth(String, byte[], ServerAuthReplyHandler)
 * @see ClientAuthExchange
 */
public interface ServerAuthReplyHandler extends ServerReplyHandler {

    /**
     * Called when authentication succeeds (235).
     * 
     * <p>The handler can proceed with the authenticated session.
     * 
     * @param session the authenticated session
     */
    void handleAuthSuccess(ClientSession session);

    /**
     * Called when the server sends a SASL challenge (334).
     * 
     * <p>The handler should compute the appropriate response based on the
     * SASL mechanism and call {@code exchange.respond()} to continue, or
     * {@code exchange.abort()} to cancel authentication.
     * 
     * @param challenge the decoded challenge bytes (base64 decoded by connection)
     * @param exchange operations to continue the authentication exchange
     */
    void handleChallenge(byte[] challenge, ClientAuthExchange exchange);

    /**
     * Called when authentication fails (535).
     * 
     * <p>The handler can retry with different credentials, try a different
     * mechanism, or proceed without authentication.
     * 
     * @param session operations to retry or continue without auth
     */
    void handleAuthFailed(ClientSession session);

    /**
     * Called when the mechanism is not supported (504).
     * 
     * <p>The handler can retry with a different mechanism from the list
     * provided in the EHLO response.
     * 
     * @param session operations to retry with different mechanism
     */
    void handleMechanismNotSupported(ClientSession session);

    /**
     * Called when authentication temporarily fails (454).
     * 
     * <p>The handler can retry later or proceed without authentication.
     * 
     * @param session operations to retry or continue without auth
     */
    void handleTemporaryFailure(ClientSession session);

}

