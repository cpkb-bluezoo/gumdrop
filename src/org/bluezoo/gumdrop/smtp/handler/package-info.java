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
 * Staged handler and state interfaces for the SMTP server (RFC 5321).
 *
 * <p>Handler interfaces are what an application implements: {@link
 * ClientConnected} (new connection), {@link HelloHandler} (HELO/EHLO,
 * STARTTLS, AUTH), {@link MailFromHandler} (MAIL FROM), {@link
 * RecipientHandler} (RCPT TO, DATA/BDAT), {@link MessageDataHandler}
 * (message completion). State interfaces are handed to those callbacks
 * by {@link org.bluezoo.gumdrop.smtp.SMTPProtocolHandler} to accept or
 * reject each step: {@link ConnectedState}, {@link HelloState}, {@link
 * AuthenticateState}, {@link MailFromState}, {@link RecipientState},
 * {@link MessageStartState}, {@link MessageEndState}, {@link ResetState}.
 *
 * <p>STARTTLS and SASL authentication mechanics are handled entirely by
 * the protocol handler; the application only sees the outcome, via
 * {@link HelloHandler#tlsEstablished} and {@link HelloHandler#authenticated}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp
 * @see org.bluezoo.gumdrop.smtp.SMTPProtocolHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a> (SMTP)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4954">RFC 4954</a> (SASL AUTH)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3207">RFC 3207</a> (STARTTLS)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3030">RFC 3030</a> (BDAT)
 */
package org.bluezoo.gumdrop.smtp.handler;
