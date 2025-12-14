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
 * <p>This package provides mailbox implementations using the standard Unix
 * mbox format as defined in RFC 4155. In the mbox format, each folder is
 * stored as a single file containing all messages concatenated together,
 * separated by "From " envelope lines.
 *
 * <h2>Mbox Format</h2>
 *
 * <p>An mbox file contains multiple messages, each preceded by a "From "
 * line (note the trailing space) that marks the message boundary:
 *
 * <pre>
 * From sender@example.com Mon Jan  1 00:00:00 2025
 * From: sender@example.com
 * To: recipient@example.com
 * Subject: First message
 *
 * Message body here...
 *
 * From another@example.com Tue Jan  2 12:30:00 2025
 * From: another@example.com
 * To: recipient@example.com
 * Subject: Second message
 *
 * Another message body...
 * </pre>
 *
 * <p>Lines in message bodies that begin with "From " are escaped by
 * prepending "&gt;" to become "&gt;From ". This escaping is reversed
 * when reading messages.
 *
 * <h2>Classes</h2>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailbox} - Single mailbox
 *       (mbox file) access for POP3</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxStore} - Multi-folder
 *       mail store for IMAP (directory of mbox files)</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory} - Factory
 *       for creating mailbox and store instances</li>
 * </ul>
 *
 * <h2>Directory Structure</h2>
 *
 * <p>For IMAP access, the store uses a directory structure where each
 * folder is an mbox file. The file extension is configurable (commonly
 * {@code .mbox}, or no extension at all):
 *
 * <pre>
 * root/
 *   username/
 *     INBOX           (mbox file containing INBOX messages)
 *     Sent            (mbox file containing Sent messages)
 *     Drafts          (mbox file)
 *     folder/         (subfolder directory)
 *       subfolder     (nested mbox file)
 *     .subscriptions  (IMAP folder subscriptions)
 * </pre>
 *
 * <h2>File Locking</h2>
 *
 * <p>This implementation uses file locking to prevent concurrent access
 * to mbox files, which is essential since the format requires exclusive
 * access during modifications.
 *
 * <h2>Security</h2>
 *
 * <p>All path operations are sandboxed within the configured root directory.
 * Path traversal attacks (using ".." or absolute paths) are rejected.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4155">RFC 4155 - The application/mbox Media Type</a>
 */
package org.bluezoo.gumdrop.mailbox.mbox;
