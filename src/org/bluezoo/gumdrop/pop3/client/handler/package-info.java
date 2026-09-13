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
 *   <li>{@link RemoteGreeting} - Entry point for new connections</li>
 *   <li>{@link CapaReplyHandler} - Receives CAPA response</li>
 *   <li>{@link UserReplyHandler} - Receives USER response</li>
 *   <li>{@link PassReplyHandler} - Receives PASS response</li>
 *   <li>{@link ApopReplyHandler} - Receives APOP response</li>
 *   <li>{@link StlsReplyHandler} - Receives STLS response</li>
 *   <li>{@link AuthReplyHandler} - Receives AUTH responses</li>
 *   <li>{@link AuthAbortHandler} - Receives AUTH abort response</li>
 *   <li>{@link StatReplyHandler} - Receives STAT response</li>
 *   <li>{@link ListReplyHandler} - Receives LIST response</li>
 *   <li>{@link RetrReplyHandler} - Receives RETR content (streamed)</li>
 *   <li>{@link DeleReplyHandler} - Receives DELE response</li>
 *   <li>{@link RsetReplyHandler} - Receives RSET response</li>
 *   <li>{@link TopReplyHandler} - Receives TOP content (streamed)</li>
 *   <li>{@link UidlReplyHandler} - Receives UIDL response</li>
 *   <li>{@link NoopReplyHandler} - Receives NOOP response</li>
 *   <li>{@link ReplyHandler} - Base reply handler interface</li>
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
 * @see org.bluezoo.gumdrop.pop3.client.Pop3ClientProtocolHandler
 */
package org.bluezoo.gumdrop.pop3.client.handler;
