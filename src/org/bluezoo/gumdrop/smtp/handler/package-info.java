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
 * SMTP server handler interfaces.
 *
 * <p>This package contains the staged handler interfaces used to process
 * SMTP sessions. The handler pattern provides type-safe protocol flow where
 * each interface represents a stage in the SMTP transaction.
 *
 * <h2>Handler Interfaces (what you implement)</h2>
 * <p>These interfaces define callbacks your handler receives at each protocol stage:
 * <ul>
 *   <li>{@link ClientConnected} - Entry point for new connections</li>
 *   <li>{@link HelloHandler} - Receives HELO/EHLO, TLS notifications, authenticated Principal</li>
 *   <li>{@link MailFromHandler} - Receives MAIL FROM commands</li>
 *   <li>{@link RecipientHandler} - Receives RCPT TO, DATA/BDAT</li>
 *   <li>{@link MessageDataHandler} - Receives message content and completion</li>
 * </ul>
 *
 * <h2>State Interfaces (provided by SMTPConnection)</h2>
 * <p>These interfaces are passed to your handler callbacks, allowing you to
 * accept or reject each protocol element:
 * <ul>
 *   <li>{@link ConnectedState} - Accept/reject initial connection</li>
 *   <li>{@link HelloState} - Accept/reject HELO/EHLO greeting</li>
 *   <li>{@link AuthenticateState} - Accept/reject authenticated Principal</li>
 *   <li>{@link MailFromState} - Accept/reject message sender</li>
 *   <li>{@link RecipientState} - Accept/reject recipients</li>
 *   <li>{@link MessageStartState} - Accept/reject message data phase</li>
 *   <li>{@link MessageEndState} - Accept/reject completed message</li>
 *   <li>{@link ResetState} - Handle RSET command</li>
 * </ul>
 *
 * <h2>Protocol Mechanics Handled by Connection</h2>
 * <p>The following are handled automatically by SMTPConnection, with the
 * handler receiving only policy-relevant notifications:
 * <ul>
 *   <li><b>STARTTLS</b> - Accepted automatically if TLS is configured.
 *       Handler receives {@link HelloHandler#tlsEstablished} notification.</li>
 *   <li><b>SASL Authentication</b> - Challenge/response exchange handled
 *       by connection. Handler receives {@link HelloHandler#authenticated}
 *       with the authenticated Principal.</li>
 * </ul>
 *
 * <h2>Factory Interface</h2>
 * <p>The {@link ClientConnectedFactory} creates new handler instances for
 * each incoming connection. Configure this on the SMTP server:
 *
 * <pre>{@code
 * <server class="org.bluezoo.gumdrop.smtp.SMTPServer">
 *   <property name="handlerFactory" ref="#myHandlerFactory"/>
 * </server>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp
 * @see org.bluezoo.gumdrop.smtp.SMTPConnection
 */
package org.bluezoo.gumdrop.smtp.handler;


