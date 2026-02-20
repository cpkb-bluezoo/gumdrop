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
 * Handler interfaces for the POP3 server.
 *
 * <p>This package contains the staged handler interfaces that define the
 * POP3 protocol flow for server-side implementations (RFC 1939).
 *
 * <h2>Handler Pattern Overview</h2>
 *
 * <p>The POP3 handler pattern uses two types of interfaces:
 * <ul>
 *   <li><strong>Handler interfaces</strong> - Receive events as callbacks.
 *       These are implemented by your handler class to make policy decisions
 *       about connection acceptance, authentication, and mailbox operations.</li>
 *   <li><strong>State interfaces</strong> - Provide response operations.
 *       These are implemented by POP3ProtocolHandler and passed to
 *       your handler, constraining which responses are valid at each
 *       protocol stage.</li>
 * </ul>
 *
 * <h2>Protocol Flow</h2>
 *
 * <p>POP3 has three states (RFC 1939 Section 3):
 * <ol>
 *   <li><strong>AUTHORIZATION</strong> - Initial state, Realm handles authentication,
 *       handler makes policy decision on authenticated principal</li>
 *   <li><strong>TRANSACTION</strong> - Authenticated, can access and modify mailbox</li>
 *   <li><strong>UPDATE</strong> - After QUIT in TRANSACTION, commit deletions</li>
 * </ol>
 *
 * <h2>Handler Interfaces</h2>
 *
 * <ul>
 *   <li>{@link ClientConnected} - Entry point for new connections</li>
 *   <li>{@link AuthorizationHandler} - Receives authentication events for policy decisions</li>
 *   <li>{@link TransactionHandler} - Receives mailbox operations (status, retrieve, delete, etc.)</li>
 * </ul>
 *
 * <h2>State Interfaces</h2>
 *
 * <ul>
 *   <li>{@link ConnectedState} - Send greeting (accept/reject connection)</li>
 *   <li>{@link AuthenticateState} - Accept/reject authenticated principal</li>
 *   <li>{@link MailboxStatusState} - Respond to mailbox status request (STAT)</li>
 *   <li>{@link ListState} - Respond to message listing request (LIST)</li>
 *   <li>{@link RetrieveState} - Respond to message retrieval request (RETR)</li>
 *   <li>{@link MarkDeletedState} - Respond to mark-deleted request (DELE)</li>
 *   <li>{@link ResetState} - Respond to reset request (RSET)</li>
 *   <li>{@link TopState} - Respond to TOP command</li>
 *   <li>{@link UidlState} - Respond to unique ID listing request (UIDL)</li>
 *   <li>{@link UpdateState} - Respond to session end (QUIT in TRANSACTION)</li>
 * </ul>
 *
 * <p>Note: Authentication protocol mechanics (USER/PASS, APOP, SASL) are
 * handled internally by POP3ProtocolHandler using the configured Realm.
 * The handler only receives an authenticate event with the verified Principal,
 * allowing it to make policy decisions without dealing with authentication
 * details.
 *
 * <p>Similarly, CAPA, STLS, NOOP, and QUIT (in AUTHORIZATION state) are
 * handled automatically as they have standard responses.
 *
 * <h2>Default Implementation</h2>
 *
 * <p>A default handler implementation is provided that accepts all connections
 * and operations using the configured MailboxFactory:
 *
 * <ul>
 *   <li>{@link DefaultPOP3Handler} - Accepts all operations using the Mailbox API</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.DefaultPOP3Service} - Default service
 *       that creates {@code DefaultPOP3Handler} instances</li>
 * </ul>
 *
 * <p>For many deployments, the default service with appropriate Realm and
 * MailboxFactory configuration is sufficient. Custom handlers can override
 * specific methods to add policy logic.
 *
 * <h2>Example Implementation</h2>
 *
 * <pre>{@code
 * public class SimplePOP3Handler implements ClientConnected, 
 *                                           AuthorizationHandler,
 *                                           TransactionHandler {
 *     
 *     public void connected(ConnectedState state, Endpoint endpoint) {
 *         state.acceptConnection("POP3 server ready", this);
 *     }
 *     
 *     public void authenticate(AuthenticateState state, Principal principal,
 *                              MailboxFactory factory) {
 *         // Policy check - is this user allowed?
 *         if (isAccountEnabled(principal)) {
 *             Mailbox mailbox = factory.openMailbox(principal.getName());
 *             state.accept(mailbox, this);
 *         } else {
 *             state.reject("Account disabled", this);
 *         }
 *     }
 *     
 *     public void retrieveMessage(RetrieveState state, Mailbox mailbox,
 *                                 int messageNumber) {
 *         // ... retrieve and send message
 *     }
 *     
 *     public void markDeleted(MarkDeletedState state, Mailbox mailbox,
 *                             int messageNumber) {
 *         // ... mark message for deletion
 *     }
 *     
 *     // ... other handler methods
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.POP3ProtocolHandler
 * @see org.bluezoo.gumdrop.pop3.POP3Listener
 */
package org.bluezoo.gumdrop.pop3.handler;
