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
 * Message indexing system for fast IMAP SEARCH operations.
 * 
 * <h2>Overview</h2>
 * <p>This package provides a message indexing system that pre-extracts and stores
 * searchable metadata from messages, enabling fast IMAP SEARCH operations without
 * parsing messages from disk.
 * 
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.index.MessageIndex} - Main index class
 *       managing primary index and auxiliary sub-indexes</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.index.MessageIndexEntry} - Single message's
 *       indexed metadata with property descriptor format</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.index.MessageIndexBuilder} - Builds entries
 *       by parsing messages using the message parser</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.index.IndexedMessageContext} - MessageContext
 *       implementation backed by index entry for search evaluation</li>
 * </ul>
 * 
 * <h2>Indexed Fields</h2>
 * <p>The following message attributes are indexed:
 * <ul>
 *   <li>UID and message number</li>
 *   <li>Message size</li>
 *   <li>Internal date and sent date</li>
 *   <li>System flags (Seen, Answered, Flagged, Deleted, Draft, Recent)</li>
 *   <li>From, To, Cc, Bcc header values</li>
 *   <li>Subject header</li>
 *   <li>Message-ID header</li>
 *   <li>Custom keywords</li>
 * </ul>
 * 
 * <h2>Not Indexed</h2>
 * <p>Body text and full headers are NOT indexed to keep index size manageable.
 * TEXT and BODY searches fall back to parsing the actual messages.
 * 
 * <h2>Sub-Indexes</h2>
 * <p>The index maintains auxiliary structures for fast lookups:
 * <ul>
 *   <li>Flag BitSets - O(1) lookup of messages with/without specific flags</li>
 *   <li>Date TreeMaps - Range queries on internal/sent dates</li>
 *   <li>Size TreeMap - Range queries for LARGER/SMALLER searches</li>
 *   <li>Address reverse indexes - Lookup by individual email address</li>
 *   <li>Keyword reverse index - Lookup by keyword</li>
 * </ul>
 * 
 * <h2>File Format</h2>
 * <p>Indexes are persisted as {@code .gidx} files alongside the mailbox:
 * <ul>
 *   <li>For mbox: {@code mailbox.mbox.gidx}</li>
 *   <li>For Maildir: {@code .gidx} in the Maildir directory</li>
 * </ul>
 * 
 * <h2>Session Model</h2>
 * <p>Each mailbox session loads its own copy of the index. This provides:
 * <ul>
 *   <li>No locking between sessions</li>
 *   <li>Isolation of modifications until close</li>
 *   <li>Simple per-session memory management</li>
 * </ul>
 * 
 * <h2>Incremental Updates</h2>
 * <p>The index supports incremental updates:
 * <ul>
 *   <li>New messages are indexed and added on append</li>
 *   <li>Deleted messages are removed on expunge</li>
 *   <li>Flag changes update the index immediately</li>
 *   <li>Changes are persisted when the mailbox is closed</li>
 * </ul>
 * 
 * <h2>Corruption Detection</h2>
 * <p>The index file includes:
 * <ul>
 *   <li>Magic number for format identification</li>
 *   <li>Version number for compatibility</li>
 *   <li>CRC32 checksums for header and entry sections</li>
 *   <li>UID validity for mailbox consistency validation</li>
 * </ul>
 * <p>If corruption is detected, the index is rebuilt from scratch.
 * 
 * <h2>Future Enhancements</h2>
 * <p>TODO: Progressive index building - currently index building is synchronous.
 * A future enhancement could implement background building where searches on
 * non-indexed messages fall back to parsing, while newly indexed messages
 * become searchable immediately.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mailbox.SearchCriteria
 * @see org.bluezoo.gumdrop.mailbox.Mailbox#search(org.bluezoo.gumdrop.mailbox.SearchCriteria)
 */
package org.bluezoo.gumdrop.mailbox.index;

