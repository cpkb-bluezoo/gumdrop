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
 * Non-blocking SMTP client (RFC 5321) for sending outbound email, with
 * STARTTLS, SASL authentication, and transparent BDAT (CHUNKING) support.
 *
 * <p>{@link org.bluezoo.gumdrop.smtp.client.SMTPClientProtocolHandler}
 * drives the protocol exchange, using BDAT instead of dot-stuffed DATA
 * transparently whenever the server advertises CHUNKING support. The
 * protocol flow is modeled as a sequence of state interfaces (package
 * {@link org.bluezoo.gumdrop.smtp.client.handler}) so only the commands
 * legal at each point can be issued: {@link
 * org.bluezoo.gumdrop.smtp.client.handler.ServerGreeting} is the entry
 * point, through {@link
 * org.bluezoo.gumdrop.smtp.client.handler.ClientHelloState} (post
 * EHLO/HELO), {@link
 * org.bluezoo.gumdrop.smtp.client.handler.ClientEnvelopeState} (MAIL
 * FROM/RCPT TO/DATA), to {@link
 * org.bluezoo.gumdrop.smtp.client.handler.ClientMessageData} for
 * streaming the message body. After STARTTLS succeeds, the handler must
 * re-issue EHLO per RFC 5321 section 4.1.1.1, receiving a fresh state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.smtp.client.SMTPClientProtocolHandler
 * @see org.bluezoo.gumdrop.smtp
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a> (SMTP)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3207">RFC 3207</a> (STARTTLS)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4954">RFC 4954</a> (SMTP AUTH)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3030">RFC 3030</a> (CHUNKING)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8314">RFC 8314</a> (Implicit TLS)
 */
package org.bluezoo.gumdrop.smtp.client;
