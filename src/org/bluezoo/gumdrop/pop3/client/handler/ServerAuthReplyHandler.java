/*
 * ServerAuthReplyHandler.java
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

/**
 * Handler for AUTH command responses during SASL authentication.
 *
 * <p>This handler receives responses during SASL authentication, which
 * may involve multiple challenge-response rounds depending on the
 * mechanism.
 *
 * <p><strong>Single-round mechanisms (e.g., PLAIN):</strong>
 * <pre>{@code
 * byte[] credentials = buildPlainCredentials(user, pass);
 * auth.auth("PLAIN", credentials, new ServerAuthReplyHandler() {
 *     public void handleAuthSuccess(ClientTransactionState transaction) {
 *         transaction.stat(statHandler);
 *     }
 *     // ...
 * });
 * }</pre>
 *
 * <p><strong>Multi-round mechanisms (e.g., LOGIN):</strong>
 * <pre>{@code
 * auth.auth("LOGIN", null, new ServerAuthReplyHandler() {
 *     private boolean sentUsername = false;
 *
 *     public void handleChallenge(byte[] challenge,
 *                                 ClientAuthExchange exchange) {
 *         if (!sentUsername) {
 *             exchange.respond(username.getBytes(), this);
 *             sentUsername = true;
 *         } else {
 *             exchange.respond(password.getBytes(), this);
 *         }
 *     }
 *     // ...
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthorizationState#auth
 * @see ClientAuthExchange
 */
public interface ServerAuthReplyHandler extends ServerReplyHandler {

    /**
     * Called when authentication succeeds (+OK).
     *
     * @param transaction operations available in the TRANSACTION state
     */
    void handleAuthSuccess(ClientTransactionState transaction);

    /**
     * Called when the server sends a SASL challenge ("+ " continuation).
     *
     * <p>The handler should compute the appropriate response based on
     * the SASL mechanism and call {@code exchange.respond()} to continue,
     * or {@code exchange.abort()} to cancel.
     *
     * @param challenge the decoded challenge bytes (base64 decoded)
     * @param exchange operations to continue the exchange
     */
    void handleChallenge(byte[] challenge, ClientAuthExchange exchange);

    /**
     * Called when authentication fails (-ERR).
     *
     * <p>The handler can retry with different credentials or try a
     * different mechanism.
     *
     * @param auth operations to retry in the AUTHORIZATION state
     * @param message the server's error message
     */
    void handleAuthFailed(ClientAuthorizationState auth, String message);

}
