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
 * Mbox file format mailbox implementation.
 *
 * <p>This package provides mailbox implementations for both single-mailbox
 * access (POP3) and multi-folder mail stores (IMAP) using the mbox format.
 *
 * <h2>Classes</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailbox} - Single mailbox access</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxStore} - Multi-folder mail store</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory} - Factory for both</li>
 * </ul>
 *
 * <h2>Directory Structure</h2>
 * <pre>
 * root/
 *   username/
 *     INBOX/
 *       1.eml, 2.eml, ...
 *     Sent/
 *       1.eml, 2.eml, ...
 *     folder/
 *       subfolder/
 *         1.eml, 2.eml, ...
 *     .subscriptions
 * </pre>
 *
 * <h2>Security</h2>
 * <p>All path operations are sandboxed within the configured root directory.
 * Path traversal attacks (using ".." or absolute paths) are rejected.
 * Mailbox names are validated to contain only alphanumeric characters,
 * underscores, hyphens, and periods.
 *
 * <h2>Usage</h2>
 *
 * <h3>POP3 (Single Mailbox)</h3>
 * <pre>{@code
 * MailboxFactory factory = new MboxMailboxFactory(new File("/var/mail"));
 * Mailbox mailbox = factory.createMailbox();
 * mailbox.open("username");
 * // Access messages...
 * mailbox.close(true);
 * }</pre>
 *
 * <h3>IMAP (Multi-folder Store)</h3>
 * <pre>{@code
 * MailboxFactory factory = new MboxMailboxFactory(new File("/var/mail"));
 * MailboxStore store = factory.createStore();
 * store.open("username");
 *
 * // List mailboxes
 * List<String> folders = store.listMailboxes("", "*");
 *
 * // Create a new mailbox
 * store.createMailbox("Archive/2025");
 *
 * // Open a mailbox
 * Mailbox inbox = store.openMailbox("INBOX", false);
 *
 * store.close();
 * }</pre>
 *
 * <p><b>Note:</b> The current implementation uses a one-file-per-message
 * directory structure. A future version may support the true mbox single-file
 * format as defined in RFC 4155.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4155">RFC 4155 - The application/mbox Media Type</a>
 */
package org.bluezoo.gumdrop.mailbox.mbox;
