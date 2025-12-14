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
 * Handler interfaces for the IMAP server.
 * 
 * <p>This package contains the staged handler interfaces for IMAP4rev2
 * server-side implementations (RFC 9051). Handlers make <strong>policy
 * decisions</strong> while protocol mechanics are handled automatically
 * by the server.
 * 
 * <h2>Design Principles</h2>
 * 
 * <ul>
 *   <li><strong>Handlers make policy decisions</strong> - authentication
 *       acceptance, access control, quota enforcement, mailbox permissions</li>
 *   <li><strong>Protocol details are hidden</strong> - tags, CAPABILITY,
 *       NOOP, LOGOUT, STARTTLS, LOGIN/AUTHENTICATE mechanics, IDLE, STATUS
 *       are handled automatically</li>
 *   <li><strong>Type-safe state transitions</strong> - each handler method
 *       provides a State interface with only valid operations</li>
 *   <li><strong>Context is provided</strong> - handler methods receive the
 *       {@link org.bluezoo.gumdrop.mailbox.MailboxStore} and/or
 *       {@link org.bluezoo.gumdrop.mailbox.Mailbox} needed to implement
 *       the operation</li>
 * </ul>
 * 
 * <h2>Handler Interfaces</h2>
 * 
 * <ul>
 *   <li>{@link ClientConnected} - entry point for new connections</li>
 *   <li>{@link NotAuthenticatedHandler} - authentication policy decisions</li>
 *   <li>{@link AuthenticatedHandler} - mailbox access after login</li>
 *   <li>{@link SelectedHandler} - message operations in a mailbox</li>
 * </ul>
 * 
 * <h2>Handler Flow</h2>
 * 
 * <pre>{@code
 * Connection established
 *       |
 *       v
 * ClientConnected.connected(info, ConnectedState)
 *       |
 *       +---> ConnectedState.acceptConnection() --> NotAuthenticatedHandler
 *       |
 *       +---> ConnectedState.acceptPreauth() --> AuthenticatedHandler
 *       |
 *       +---> ConnectedState.rejectConnection() --> disconnected()
 *       
 * NotAuthenticatedHandler:
 *       |
 *       +---> authenticate(principal, factory) --> AuthenticateState.accept() --> AuthenticatedHandler
 *       
 * AuthenticatedHandler:
 *       |
 *       +---> select()/examine() --> SelectState.selectOk() --> SelectedHandler
 *       |
 *       +---> create/delete/rename/list/append/quota --> stay in AuthenticatedHandler
 *       
 * SelectedHandler:
 *       |
 *       +---> store/copy/move/expunge --> stay in SelectedHandler
 *       |
 *       +---> close/unselect --> CloseState.closed() --> AuthenticatedHandler
 *       |
 *       +---> select() --> SelectState.selectOk() --> SelectedHandler (different mailbox)
 * }</pre>
 * 
 * <p>Note: Authentication mechanics (LOGIN command, AUTHENTICATE SASL) are
 * handled internally by IMAPConnection using the configured Realm. The handler
 * only receives an authenticate event with the verified Principal, allowing it
 * to make policy decisions without dealing with authentication protocol details.
 * 
 * <h2>Example Implementation</h2>
 * 
 * <pre>{@code
 * public class SimpleIMAPHandler implements ClientConnected, NotAuthenticatedHandler,
 *                                           AuthenticatedHandler, SelectedHandler {
 *     
 *     public void connected(ConnectionInfo info, ConnectedState state) {
 *         // Policy: accept or reject based on IP, rate limiting, etc.
 *         state.acceptConnection("IMAP4rev2 server ready", this);
 *     }
 *     
 *     public void authenticate(Principal principal, MailboxFactory factory,
 *                              AuthenticateState state) {
 *         // Policy: does this principal have a mailbox store?
 *         try {
 *             MailboxStore store = factory.createStore();
 *             store.open(principal.getName());
 *             state.accept(store, this);
 *         } catch (IOException e) {
 *             state.reject("Mailbox unavailable", this);
 *         }
 *     }
 *     
 *     // The MailboxStore is passed to handler methods that need it
 *     public void select(MailboxStore store, String mailboxName, SelectState state) {
 *         // Policy: access control for the mailbox
 *         Mailbox mailbox = store.getMailbox(mailboxName);
 *         if (mailbox == null) {
 *             state.mailboxNotFound("Mailbox not found", this);
 *         } else {
 *             state.selectOk(mailbox, false, ...flags..., this);
 *         }
 *     }
 *     
 *     // Selected state methods receive the current Mailbox
 *     public void expunge(Mailbox mailbox, ExpungeState state) {
 *         mailbox.expunge();
 *         state.expungeComplete(this);
 *     }
 *     
 *     public void disconnected() {
 *         // Cleanup if needed
 *     }
 * }
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected
 * @see ClientConnectedFactory
 */
package org.bluezoo.gumdrop.imap.handler;
