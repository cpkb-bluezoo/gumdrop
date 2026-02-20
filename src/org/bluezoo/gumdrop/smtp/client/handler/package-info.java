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
 * SMTP client handler and state interfaces.
 *
 * <p>This package contains the callback interfaces used to handle SMTP server
 * responses and the state interfaces that constrain which operations are valid
 * at each stage of the SMTP protocol.
 *
 * <h2>Server Reply Handler Interfaces</h2>
 * <p>These interfaces define callbacks your handler receives for server responses:
 * <ul>
 *   <li>{@link ServerGreeting} - Entry point for new connections</li>
 *   <li>{@link ServerEhloReplyHandler} - Receives EHLO response</li>
 *   <li>{@link ServerHeloReplyHandler} - Receives HELO response</li>
 *   <li>{@link ServerStarttlsReplyHandler} - Receives STARTTLS response</li>
 *   <li>{@link ServerAuthReplyHandler} - Receives AUTH responses</li>
 *   <li>{@link ServerMailFromReplyHandler} - Receives MAIL FROM response</li>
 *   <li>{@link ServerRcptToReplyHandler} - Receives RCPT TO response</li>
 *   <li>{@link ServerDataReplyHandler} - Receives DATA/BDAT response</li>
 *   <li>{@link ServerMessageReplyHandler} - Receives message completion response</li>
 *   <li>{@link ServerRsetReplyHandler} - Receives RSET response</li>
 *   <li>{@link ServerReplyHandler} - Common reply handler methods</li>
 * </ul>
 *
 * <h2>Client State Interfaces</h2>
 * <p>These interfaces are provided to your handler callbacks, allowing you to
 * issue SMTP commands at the appropriate protocol stage:
 * <ul>
 *   <li>{@link ClientSession} - Base session operations (RSET, QUIT)</li>
 *   <li>{@link ClientHelloState} - Post-connect/EHLO state for starting transactions</li>
 *   <li>{@link ClientPostTls} - Post-STARTTLS state for re-issuing EHLO</li>
 *   <li>{@link ClientAuthExchange} - SASL authentication exchange</li>
 *   <li>{@link ClientEnvelope} - MAIL FROM state</li>
 *   <li>{@link ClientEnvelopeReady} - Ready to start a new transaction</li>
 *   <li>{@link ClientEnvelopeState} - RCPT TO and DATA state</li>
 *   <li>{@link ClientMessageData} - Message content streaming state</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.client
 * @see org.bluezoo.gumdrop.smtp.client.SMTPClientProtocolHandler
 */
package org.bluezoo.gumdrop.smtp.client.handler;


