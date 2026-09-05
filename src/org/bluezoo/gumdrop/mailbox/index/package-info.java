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
 * Message index for fast IMAP SEARCH, avoiding a full parse of every
 * message on disk.
 *
 * <p>{@link org.bluezoo.gumdrop.mailbox.index.MessageIndex} owns a
 * primary index plus auxiliary structures for common queries -- flag
 * bitsets, date and size TreeMaps for range queries, and reverse indexes
 * for address/keyword lookups. {@link
 * org.bluezoo.gumdrop.mailbox.index.MessageIndexBuilder} builds one
 * {@link org.bluezoo.gumdrop.mailbox.index.MessageIndexEntry} per
 * message by parsing it once; {@link
 * org.bluezoo.gumdrop.mailbox.index.IndexedMessageContext} then answers
 * search evaluation directly from the indexed entry. UID, message
 * number, size, dates, system flags, keywords, and the From/To/Cc/Bcc/
 * Subject/Message-ID headers are indexed; body text and full headers are
 * not, so TEXT/BODY searches fall back to parsing the actual message.
 *
 * <p>Indexes persist as a {@code .gidx} file alongside the mailbox
 * (mbox) or inside its directory (Maildir), versioned with a magic
 * number and CRC32 checksums; a failed checksum triggers a full rebuild
 * rather than serving stale or corrupt data. Each session loads its own
 * copy, so there is no cross-session locking, and updates (append,
 * expunge, flag change) apply incrementally and persist on close.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mailbox.SearchCriteria
 * @see org.bluezoo.gumdrop.mailbox.Mailbox#search(org.bluezoo.gumdrop.mailbox.SearchCriteria)
 */
package org.bluezoo.gumdrop.mailbox.index;
