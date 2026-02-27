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
 * IMAP client handler and state interfaces.
 *
 * <p>This package contains the callback interfaces used to handle IMAP
 * server responses and the state interfaces that constrain which operations
 * are valid at each stage of the IMAP protocol.
 *
 * <h2>Server Reply Handler Interfaces</h2>
 * <p>These interfaces define callbacks your handler receives for server
 * responses:
 * <ul>
 *   <li>{@link ServerGreeting} - Entry point for new connections</li>
 *   <li>{@link ServerCapabilityReplyHandler} - Receives CAPABILITY response</li>
 *   <li>{@link ServerLoginReplyHandler} - Receives LOGIN response</li>
 *   <li>{@link ServerAuthReplyHandler} - Receives AUTHENTICATE responses</li>
 *   <li>{@link ServerAuthAbortHandler} - Receives AUTH abort response</li>
 *   <li>{@link ServerStarttlsReplyHandler} - Receives STARTTLS response</li>
 *   <li>{@link ServerSelectReplyHandler} - Receives SELECT/EXAMINE response</li>
 *   <li>{@link ServerListReplyHandler} - Receives LIST/LSUB responses</li>
 *   <li>{@link ServerStatusReplyHandler} - Receives STATUS response</li>
 *   <li>{@link ServerMailboxReplyHandler} - Receives mailbox management responses</li>
 *   <li>{@link ServerNamespaceReplyHandler} - Receives NAMESPACE response</li>
 *   <li>{@link ServerAppendReplyHandler} - Receives APPEND response</li>
 *   <li>{@link ServerIdleEventHandler} - Receives IDLE events</li>
 *   <li>{@link ServerCloseReplyHandler} - Receives CLOSE/UNSELECT response</li>
 *   <li>{@link ServerExpungeReplyHandler} - Receives EXPUNGE response</li>
 *   <li>{@link ServerSearchReplyHandler} - Receives SEARCH response</li>
 *   <li>{@link ServerFetchReplyHandler} - Receives FETCH response</li>
 *   <li>{@link ServerStoreReplyHandler} - Receives STORE response</li>
 *   <li>{@link ServerCopyReplyHandler} - Receives COPY/MOVE response</li>
 *   <li>{@link ServerNoopReplyHandler} - Receives NOOP response</li>
 *   <li>{@link ServerReplyHandler} - Base reply handler interface</li>
 * </ul>
 *
 * <h2>Client State Interfaces</h2>
 * <p>These interfaces are provided to your handler callbacks, allowing you
 * to issue IMAP commands at the appropriate protocol stage:
 * <ul>
 *   <li>{@link ClientNotAuthenticatedState} - NOT AUTHENTICATED state
 *       (CAPABILITY, LOGIN, AUTHENTICATE, STARTTLS, LOGOUT)</li>
 *   <li>{@link ClientPostStarttls} - After STARTTLS succeeds
 *       (CAPABILITY, LOGIN, AUTHENTICATE, LOGOUT)</li>
 *   <li>{@link ClientAuthExchange} - SASL authentication exchange</li>
 *   <li>{@link ClientAuthenticatedState} - AUTHENTICATED state
 *       (SELECT, EXAMINE, CREATE, DELETE, RENAME, SUBSCRIBE, UNSUBSCRIBE,
 *       LIST, LSUB, STATUS, NAMESPACE, APPEND, IDLE, NOOP, LOGOUT)</li>
 *   <li>{@link ClientSelectedState} - SELECTED state
 *       (all authenticated operations plus CLOSE, UNSELECT, EXPUNGE,
 *       SEARCH, FETCH, STORE, COPY, MOVE)</li>
 *   <li>{@link ClientIdleState} - During IDLE (DONE)</li>
 *   <li>{@link ClientAppendState} - During APPEND data transfer</li>
 * </ul>
 *
 * <h2>Event Listener</h2>
 * <ul>
 *   <li>{@link MailboxEventListener} - Unsolicited mailbox event listener</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.imap.client
 * @see org.bluezoo.gumdrop.imap.client.IMAPClientProtocolHandler
 */
package org.bluezoo.gumdrop.imap.client.handler;
