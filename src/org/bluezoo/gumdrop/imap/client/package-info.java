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
 * Non-blocking IMAP client for accessing remote mailboxes.
 *
 * <p>This package provides an asynchronous, event-driven IMAP client for
 * accessing email messages, with support for STARTTLS (explicit TLS),
 * implicit TLS (IMAPS), SASL authentication, tagged command tracking,
 * FETCH with literal streaming, APPEND, IDLE, and unsolicited mailbox
 * event delivery.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.ImapClient} -
 *       High-level facade for connecting to IMAP servers</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.ImapClientProtocolHandler} -
 *       Handles IMAP protocol exchanges with tagged command tracking and
 *       literal byte-counting</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.RemoteGreeting} -
 *       Entry point callback interface for receiving the initial greeting</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.ClientNotAuthenticatedState} -
 *       State interface for NOT AUTHENTICATED commands</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.ClientAuthenticatedState} -
 *       State interface for AUTHENTICATED commands</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.ClientSelectedState} -
 *       State interface for SELECTED commands</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.MailboxInfo} -
 *       Selected mailbox metadata from SELECT/EXAMINE</li>
 *   <li>{@link org.bluezoo.gumdrop.imap.client.FetchData} -
 *       Structured FETCH response data per message</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Non-blocking I/O using the shared SelectorLoop</li>
 *   <li>STARTTLS support for upgrading to encrypted connections
 *       (RFC 9051 section 6.2.1)</li>
 *   <li>Implicit TLS (IMAPS, port 993, RFC 8314 section 3.3)</li>
 *   <li>SASL authentication with initial response (RFC 9051 section 6.2.2,
 *       RFC 4959)</li>
 *   <li>LOGIN authentication</li>
 *   <li>Tagged command tracking with auto-generated tags</li>
 *   <li>Streaming FETCH body literal data via ByteBuffer chunks</li>
 *   <li>APPEND with continuation and content streaming</li>
 *   <li>IDLE for server-push mailbox notifications</li>
 *   <li>Unsolicited mailbox event delivery via MailboxEventListener</li>
 *   <li>Type-safe stateful handler pattern enforcing correct command sequences</li>
 *   <li>Async DNS resolution via the gumdrop DnsResolver</li>
 * </ul>
 *
 * <p>Different state interfaces in this package are provided at each stage
 * of the protocol, so only the commands valid at that point can be
 * issued -- compile-time safety against protocol violations.
 *
 * <h2>Server Reply Handler Interfaces</h2>
 * <p>These interfaces define callbacks your handler receives for server
 * responses:
 * <ul>
 *   <li>{@link RemoteGreeting} - Entry point for new connections</li>
 *   <li>{@link CapabilityReplyHandler} - Receives CAPABILITY response</li>
 *   <li>{@link LoginReplyHandler} - Receives LOGIN response</li>
 *   <li>{@link AuthReplyHandler} - Receives AUTHENTICATE responses</li>
 *   <li>{@link AuthAbortHandler} - Receives AUTH abort response</li>
 *   <li>{@link StarttlsReplyHandler} - Receives STARTTLS response</li>
 *   <li>{@link SelectReplyHandler} - Receives SELECT/EXAMINE response</li>
 *   <li>{@link ListReplyHandler} - Receives LIST/LSUB responses</li>
 *   <li>{@link StatusReplyHandler} - Receives STATUS response</li>
 *   <li>{@link MailboxReplyHandler} - Receives mailbox management responses</li>
 *   <li>{@link NamespaceReplyHandler} - Receives NAMESPACE response</li>
 *   <li>{@link AppendReplyHandler} - Receives APPEND response</li>
 *   <li>{@link IdleEventHandler} - Receives IDLE events</li>
 *   <li>{@link CloseReplyHandler} - Receives CLOSE/UNSELECT response</li>
 *   <li>{@link ExpungeReplyHandler} - Receives EXPUNGE response</li>
 *   <li>{@link SearchReplyHandler} - Receives SEARCH response</li>
 *   <li>{@link FetchReplyHandler} - Receives FETCH response</li>
 *   <li>{@link StoreReplyHandler} - Receives STORE response</li>
 *   <li>{@link CopyReplyHandler} - Receives COPY/MOVE response</li>
 *   <li>{@link NoopReplyHandler} - Receives NOOP response</li>
 *   <li>{@link ReplyHandler} - Base reply handler interface</li>
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
 * @see org.bluezoo.gumdrop.imap.client.ImapClient
 * @see org.bluezoo.gumdrop.imap
 */
package org.bluezoo.gumdrop.imap.client;
