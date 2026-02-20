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

package org.bluezoo.gumdrop.imap.handler;

import org.bluezoo.gumdrop.Endpoint;

/**
 * Entry point handler for new IMAP client connections.
 * 
 * <p>This interface is the starting point for the staged IMAP server handler
 * pattern. Implement this interface and register it with the IMAP server to
 * receive new client connections.
 * 
 * <p>The staged handler pattern guides implementers through the IMAP4rev2
 * protocol (RFC 9051) by providing type-safe state interfaces at each step.
 * This makes it impossible to perform out-of-order operations.
 * 
 * <p><strong>Example implementation:</strong>
 * <pre>{@code
 * public class MyIMAPHandler implements ClientConnected, NotAuthenticatedHandler, 
 *                                       AuthenticatedHandler, SelectedHandler {
 *     
 *     public void connected(ConnectedState state, Endpoint endpoint) {
 *         state.acceptConnection("IMAP4rev2 server ready", this);
 *     }
 *     
 *     public void authenticate(AuthenticateState state, Principal principal,
 *                              MailboxFactory factory) {
 *         try {
 *             MailboxStore store = factory.createStore();
 *             store.open(principal.getName());
 *             state.accept(store, this);
 *         } catch (IOException e) {
 *             state.reject("Mailbox unavailable", this);
 *         }
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
 * @see NotAuthenticatedHandler
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
     * <p>The endpoint provides access to:
     * <ul>
     *   <li>Client and server socket addresses via
     *       {@link Endpoint#getRemoteAddress()} and
     *       {@link Endpoint#getLocalAddress()}</li>
     *   <li>TLS status via {@link Endpoint#isSecure()} and security details
     *       via {@link Endpoint#getSecurityInfo()}</li>
     * </ul>
     * 
     * <p>To accept the connection, call {@code state.acceptConnection()} with
     * a greeting message and the handler for the not-authenticated state.
     * To reject, call {@code state.rejectConnection()}.
     * 
     * @param state operations available for responding
     * @param endpoint the transport endpoint for this connection
     */
    void connected(ConnectedState state, Endpoint endpoint);

    /**
     * Called when the connection is closed for any reason.
     * 
     * <p>This may be called at any point in the protocol if the client
     * disconnects, the server closes the connection, or an error occurs.
     * Implementations should clean up any per-connection resources.
     */
    void disconnected();

}

