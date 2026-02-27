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
 * POP3 client handler and state interfaces.
 *
 * <p>This package contains the callback interfaces used to handle POP3
 * server responses and the state interfaces that constrain which operations
 * are valid at each stage of the POP3 protocol.
 *
 * <h2>Server Reply Handler Interfaces</h2>
 * <p>These interfaces define callbacks your handler receives for server
 * responses:
 * <ul>
 *   <li>{@link ServerGreeting} - Entry point for new connections</li>
 *   <li>{@link ServerCapaReplyHandler} - Receives CAPA response</li>
 *   <li>{@link ServerUserReplyHandler} - Receives USER response</li>
 *   <li>{@link ServerPassReplyHandler} - Receives PASS response</li>
 *   <li>{@link ServerApopReplyHandler} - Receives APOP response</li>
 *   <li>{@link ServerStlsReplyHandler} - Receives STLS response</li>
 *   <li>{@link ServerAuthReplyHandler} - Receives AUTH responses</li>
 *   <li>{@link ServerAuthAbortHandler} - Receives AUTH abort response</li>
 *   <li>{@link ServerStatReplyHandler} - Receives STAT response</li>
 *   <li>{@link ServerListReplyHandler} - Receives LIST response</li>
 *   <li>{@link ServerRetrReplyHandler} - Receives RETR content (streamed)</li>
 *   <li>{@link ServerDeleReplyHandler} - Receives DELE response</li>
 *   <li>{@link ServerRsetReplyHandler} - Receives RSET response</li>
 *   <li>{@link ServerTopReplyHandler} - Receives TOP content (streamed)</li>
 *   <li>{@link ServerUidlReplyHandler} - Receives UIDL response</li>
 *   <li>{@link ServerNoopReplyHandler} - Receives NOOP response</li>
 *   <li>{@link ServerReplyHandler} - Base reply handler interface</li>
 * </ul>
 *
 * <h2>Client State Interfaces</h2>
 * <p>These interfaces are provided to your handler callbacks, allowing you
 * to issue POP3 commands at the appropriate protocol stage:
 * <ul>
 *   <li>{@link ClientAuthorizationState} - AUTHORIZATION state
 *       (CAPA, USER, APOP, AUTH, STLS, QUIT)</li>
 *   <li>{@link ClientPasswordState} - After USER accepted (PASS, QUIT)</li>
 *   <li>{@link ClientPostStls} - After STLS succeeds
 *       (CAPA, USER, APOP, AUTH, QUIT)</li>
 *   <li>{@link ClientTransactionState} - TRANSACTION state
 *       (STAT, LIST, RETR, DELE, RSET, TOP, UIDL, NOOP, QUIT)</li>
 *   <li>{@link ClientAuthExchange} - SASL authentication exchange</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.client
 * @see org.bluezoo.gumdrop.pop3.client.POP3ClientProtocolHandler
 */
package org.bluezoo.gumdrop.pop3.client.handler;
