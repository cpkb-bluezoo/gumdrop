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
 * POP3 (RFC 1939) server for mailbox retrieval.
 *
 * <p>{@link org.bluezoo.gumdrop.pop3.POP3Service} is the abstract base
 * for POP3 application services; {@link
 * org.bluezoo.gumdrop.pop3.POP3Listener} is the TCP transport listener;
 * {@link org.bluezoo.gumdrop.pop3.POP3ProtocolHandler} implements the
 * three-state protocol (AUTHORIZATION, TRANSACTION, UPDATE) directly.
 * Mailbox storage is pluggable via {@link
 * org.bluezoo.gumdrop.mailbox.Mailbox}/{@link
 * org.bluezoo.gumdrop.mailbox.MailboxFactory}; {@link
 * org.bluezoo.gumdrop.mailbox.mbox.MboxMailbox} is the built-in mbox
 * implementation.
 *
 * <p>Authentication runs through the standard {@link
 * org.bluezoo.gumdrop.auth.Realm} interface for every mechanism:
 * USER/PASS, APOP, and SASL AUTH (PLAIN, LOGIN, CRAM-MD5, DIGEST-MD5,
 * SCRAM-SHA-256, OAUTHBEARER, GSSAPI, EXTERNAL). Transport security is
 * either implicit TLS (POP3S, port 995) or STARTTLS via STLS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.POP3Listener
 * @see org.bluezoo.gumdrop.pop3.POP3ProtocolHandler
 * @see org.bluezoo.gumdrop.mailbox.Mailbox
 * @see org.bluezoo.gumdrop.auth.Realm
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939 - POP3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2449">RFC 2449 - CAPA</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2595">RFC 2595 - STLS</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5034">RFC 5034 - SASL for POP3</a>
 */
package org.bluezoo.gumdrop.pop3;
