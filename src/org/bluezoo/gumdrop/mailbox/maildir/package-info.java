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
 * Maildir++ format {@link org.bluezoo.gumdrop.mailbox.MailboxStore}
 * implementation: one file per message under {@code tmp/}/{@code new/}/
 * {@code cur/}, subfolders as dot-prefixed directories, requiring no
 * locking for concurrent access since delivery and flag changes are
 * atomic renames rather than in-place edits; safe even on networked
 * filesystems (NFS).
 *
 * <p>Message filenames encode delivery timestamp, a unique ID, size, and
 * flags (e.g. {@code 1733356800000.12345.1,S=4523:2,SF}). Per-message
 * MODSEQ for CONDSTORE/QRESYNC is tracked in a {@code .modseq} sidecar
 * file, with expunged UIDs and their last MODSEQ recorded in {@code
 * .expunged} for QRESYNC's VANISHED (EARLIER) response. Message I/O is
 * asynchronous, via {@link org.bluezoo.gumdrop.mailbox.AsyncMessageContent}/
 * {@link org.bluezoo.gumdrop.mailbox.AsyncMessageWriter} backed by
 * {@code java.nio.channels.AsynchronousFileChannel}, so large messages
 * stream without buffering.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxStore
 * @see org.bluezoo.gumdrop.mailbox.maildir.MaildirMailbox
 * @see <a href="https://en.wikipedia.org/wiki/Maildir">Maildir on Wikipedia</a>
 */
package org.bluezoo.gumdrop.mailbox.maildir;
