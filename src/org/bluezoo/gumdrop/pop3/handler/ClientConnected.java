/*
 * ClientConnected.java
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

package org.bluezoo.gumdrop.pop3.handler;

import org.bluezoo.gumdrop.ConnectionInfo;

/**
 * Entry point handler for new POP3 client connections.
 * 
 * <p>This interface is the starting point for the staged POP3 server handler
 * pattern. Implement this interface and register it with the POP3 server to
 * receive new client connections.
 * 
 * <p>The staged handler pattern guides implementers through the POP3 protocol
 * by providing type-safe state interfaces at each step. This makes it impossible
 * to perform out-of-order operations - you can only call methods that are valid
 * for the current protocol state (RFC 1939).
 * 
 * <p><strong>Example implementation:</strong>
 * <pre>{@code
 * public class MyPOP3Handler implements ClientConnected, AuthorizationHandler, 
 *                                       TransactionHandler {
 *     
 *     public void connected(ConnectionInfo info, ConnectedState state) {
 *         // Accept the connection with a greeting
 *         state.acceptConnection("POP3 server ready", this);
 *     }
 *     
 *     public void user(String username, UserState state) {
 *         // Accept the username and wait for password
 *         state.acceptUser(this);
 *     }
 *     
 *     public void disconnected() {
 *         // Clean up resources
 *     }
 *     
 *     // ... implement other handler methods
 * }
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectedState
 * @see AuthorizationHandler
 */
public interface ClientConnected {

    /**
     * Called when a new client connection is established.
     * 
     * <p>This is called immediately after the TCP connection is accepted and
     * any TLS handshake is completed (for implicit TLS connections). The
     * handler should evaluate whether to accept this connection and call
     * the appropriate method on the state interface.
     * 
     * <p>The connection information includes:
     * <ul>
     *   <li>Client and server socket addresses</li>
     *   <li>TLS status and certificate information (if secure)</li>
     * </ul>
     * 
     * <p>To accept the connection, call {@code state.acceptConnection()} with
     * a greeting message and the handler for the authorization state. To reject,
     * call {@code state.rejectConnection()}.
     * 
     * @param info connection information (addresses, TLS status)
     * @param state operations available for responding
     */
    void connected(ConnectionInfo info, ConnectedState state);

    /**
     * Called when the connection is closed for any reason.
     * 
     * <p>This may be called at any point in the protocol if the client
     * disconnects, the server closes the connection, or an error occurs.
     * Implementations should clean up any per-connection resources.
     */
    void disconnected();

}

