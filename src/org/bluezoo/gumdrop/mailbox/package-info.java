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
 * Mailbox storage abstraction shared by the POP3 and IMAP servers.
 *
 * <p>{@link org.bluezoo.gumdrop.mailbox.Mailbox} is a single mailbox
 * (POP3's whole world; one selected folder in IMAP); {@link
 * org.bluezoo.gumdrop.mailbox.MailboxStore} is IMAP's multi-folder view
 * over several {@code Mailbox} instances; {@link
 * org.bluezoo.gumdrop.mailbox.MailboxFactory} creates either for a given
 * backend. {@link org.bluezoo.gumdrop.mailbox.MessageDescriptor} (and
 * IMAP's richer {@link org.bluezoo.gumdrop.mailbox.IMAPMessageDescriptor})
 * describe one message's metadata; {@link
 * org.bluezoo.gumdrop.mailbox.AsyncMessageContent}/{@link
 * org.bluezoo.gumdrop.mailbox.AsyncMessageWriter} provide non-blocking
 * message read/append. {@link org.bluezoo.gumdrop.mailbox.MailboxNameCodec}
 * encodes Unicode/filesystem-unsafe mailbox names into a safe form (a
 * modified Quoted-Printable) without losing the original name.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox} - Unix mbox format</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.maildir} - Maildir++ format</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.pop3.POP3Listener
 * @see org.bluezoo.gumdrop.imap.IMAPListener
 */
package org.bluezoo.gumdrop.mailbox;
