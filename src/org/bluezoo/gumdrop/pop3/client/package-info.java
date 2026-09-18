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
 * Non-blocking POP3 client for accessing remote mailboxes.
 *
 * <p>This package provides an asynchronous, event-driven POP3 client for
 * retrieving email messages, with support for STLS (explicit TLS),
 * SASL authentication, APOP, and streaming message content via ByteBuffer
 * chunks.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.Pop3Client} -
 *       High-level facade for connecting to POP3 servers</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.Pop3ClientProtocolHandler} -
 *       Handles POP3 protocol exchanges with transparent dot-unstuffing</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.RemoteGreeting} -
 *       Entry point callback interface for receiving the initial greeting</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.ClientAuthorizationState} -
 *       State interface for AUTHORIZATION commands (CAPA, USER, APOP, AUTH, STLS)</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3.client.ClientTransactionState} -
 *       State interface for TRANSACTION commands (STAT, LIST, RETR, DELE, etc.)</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Non-blocking I/O using the shared SelectorLoop</li>
 *   <li>STLS support for upgrading to encrypted connections (RFC 2595)</li>
 *   <li>Implicit TLS (POP3S, port 995)</li>
 *   <li>SASL authentication (RFC 5034)</li>
 *   <li>APOP digest authentication (RFC 1939)</li>
 *   <li>Streaming message content without memory buffering</li>
 *   <li>Transparent dot-unstuffing for RETR and TOP responses</li>
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
 * @see org.bluezoo.gumdrop.pop3.client.Pop3Client
 * @see org.bluezoo.gumdrop.pop3
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939 — POP3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2449">RFC 2449 — POP3 Extension Mechanism</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2595">RFC 2595 — STLS for POP3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5034">RFC 5034 — SASL for POP3</a>
 */
package org.bluezoo.gumdrop.pop3.client;
