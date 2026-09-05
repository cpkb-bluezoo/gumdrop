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
 * Staged handler interfaces for the IMAP4rev2 server (RFC 9051).
 *
 * <p>{@link ClientConnected} is the entry point; {@link
 * NotAuthenticatedHandler}, {@link AuthenticatedHandler}, and {@link
 * SelectedHandler} correspond to IMAP's three post-connection states,
 * each exposing only the commands legal there and a State interface per
 * command to accept or reject it. Handler methods receive whatever
 * context they need to decide -- a {@link
 * org.bluezoo.gumdrop.mailbox.MailboxStore} once authenticated, a {@link
 * org.bluezoo.gumdrop.mailbox.Mailbox} once one is selected -- so the
 * application makes policy decisions (is this principal allowed, does
 * this mailbox exist, is access permitted) without touching protocol
 * mechanics: tags, CAPABILITY, NOOP, LOGOUT, STARTTLS, IDLE, and the
 * LOGIN/AUTHENTICATE exchange itself are all handled automatically by
 * {@code IMAPProtocolHandler}, which hands the application only the
 * verified {@code Principal} once authentication succeeds.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientConnected
 * @see org.bluezoo.gumdrop.imap.IMAPProtocolHandler
 */
package org.bluezoo.gumdrop.imap.handler;
