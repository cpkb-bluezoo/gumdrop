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
 * Unix mbox format (RFC 4155) mailbox implementation: each folder is one
 * file, messages concatenated and separated by a {@code "From "}
 * envelope line, with in-body lines starting {@code "From "} escaped as
 * {@code ">From "} on write and unescaped on read.
 *
 * <p>{@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailbox} is a single
 * mailbox file for POP3; {@link
 * org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxStore} is a directory of
 * mbox files (one per IMAP folder, subfolders as nested directories) for
 * IMAP; {@link org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory}
 * creates either. File locking serializes access, since the format
 * requires exclusive access during modification; all path operations
 * are sandboxed within the configured root directory, rejecting
 * traversal via {@code ".."} or absolute paths.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4155">RFC 4155 - The application/mbox Media Type</a>
 */
package org.bluezoo.gumdrop.mailbox.mbox;
