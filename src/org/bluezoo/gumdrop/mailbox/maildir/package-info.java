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
 * Maildir format mailbox implementation.
 * 
 * <h2>Overview</h2>
 * 
 * <p>This package provides a {@link org.bluezoo.gumdrop.mailbox.MailboxStore}
 * implementation using the Maildir++ format. Maildir stores each message as
 * a separate file within a directory structure, making it well-suited for
 * concurrent access and reliable delivery.
 * 
 * <h2>Maildir Format</h2>
 * 
 * <p>Each mailbox is a directory containing three subdirectories:
 * <ul>
 *   <li>{@code tmp/} - Temporary files during message delivery</li>
 *   <li>{@code new/} - Newly delivered messages not yet seen by client</li>
 *   <li>{@code cur/} - Messages that have been accessed by the client</li>
 * </ul>
 * 
 * <h2>Maildir++ Extensions</h2>
 * 
 * <p>The Maildir++ format extends basic Maildir with support for subfolders.
 * Subfolders are represented as directories starting with a dot:
 * <pre>
 * ~/Maildir/           (INBOX)
 *   cur/ new/ tmp/
 * ~/Maildir/.Sent/     (Sent folder)
 *   cur/ new/ tmp/
 * ~/Maildir/.folder.subfolder/  (folder/subfolder)
 *   cur/ new/ tmp/
 * </pre>
 * 
 * <h2>Message Filenames</h2>
 * 
 * <p>Message filenames encode metadata including:
 * <ul>
 *   <li>Delivery timestamp</li>
 *   <li>Unique identifier (process ID, counter)</li>
 *   <li>Message size</li>
 *   <li>Flags (Seen, Answered, Flagged, Deleted, Draft)</li>
 *   <li>Custom keywords</li>
 * </ul>
 * 
 * <p>Example filename: {@code 1733356800000.12345.1,S=4523:2,SF}
 * 
 * <h2>Advantages</h2>
 * 
 * <ul>
 *   <li>No file locking required for concurrent access</li>
 *   <li>Atomic message delivery (write to tmp, rename to new)</li>
 *   <li>Flag changes are simple renames</li>
 *   <li>Safe on networked file systems (NFS)</li>
 *   <li>Easy backup and recovery</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * 
 * <pre>
 * Path mailRoot = Paths.get("/var/mail");
 * MaildirMailboxStore store = new MaildirMailboxStore(mailRoot);
 * 
 * store.open("username");
 * 
 * // Access INBOX
 * Mailbox inbox = store.openMailbox("INBOX", false);
 * 
 * // Create a new folder
 * store.createMailbox("Archives/2025");
 * 
 * // List all mailboxes
 * List&lt;String&gt; mailboxes = store.listMailboxes("", "*");
 * 
 * inbox.close(true);  // expunge deleted messages
 * store.close();
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxStore
 * @see org.bluezoo.gumdrop.mailbox.maildir.MaildirMailbox
 * @see <a href="https://en.wikipedia.org/wiki/Maildir">Maildir on Wikipedia</a>
 */
package org.bluezoo.gumdrop.mailbox.maildir;

