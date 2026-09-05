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
 * Staged handler and state interfaces for the POP3 server (RFC 1939
 * section 3's three states: AUTHORIZATION, TRANSACTION, UPDATE).
 *
 * <p>Handler interfaces are what an application implements: {@link
 * ClientConnected} (new connection), {@link AuthorizationHandler}
 * (authentication policy decision), {@link TransactionHandler} (mailbox
 * operations -- STAT, LIST, RETR, DELE, TOP, UIDL, RSET). State
 * interfaces are handed to those callbacks to accept or reject each
 * step: {@link ConnectedState}, {@link AuthenticateState}, {@link
 * MailboxStatusState}, {@link ListState}, {@link RetrieveState}, {@link
 * MarkDeletedState}, {@link ResetState}, {@link TopState}, {@link
 * UidlState}, {@link UpdateState}.
 *
 * <p>USER/PASS, APOP, and SASL authentication mechanics, and CAPA/STLS/
 * NOOP/QUIT, are handled entirely by {@code POP3ProtocolHandler} using
 * the configured {@link org.bluezoo.gumdrop.auth.Realm}; the application
 * only sees the verified {@code Principal} at {@link
 * AuthorizationHandler#authenticate}. {@link DefaultPOP3Handler} and
 * {@link org.bluezoo.gumdrop.pop3.DefaultPOP3Service} provide a ready-to-use
 * implementation backed directly by the configured {@code MailboxFactory}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.POP3ProtocolHandler
 * @see org.bluezoo.gumdrop.pop3.POP3Listener
 */
package org.bluezoo.gumdrop.pop3.handler;
