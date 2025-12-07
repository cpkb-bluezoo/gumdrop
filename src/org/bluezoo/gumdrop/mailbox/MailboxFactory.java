/*
 * MailboxFactory.java
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

package org.bluezoo.gumdrop.mailbox;

/**
 * Factory interface for creating mail store instances.
 * Implementations provide mailbox access for different storage backends
 * (mbox files, Maildir, database, IMAP proxy, etc.).
 * 
 * <p>The standard access pattern for both POP3 and IMAP is:
 * <ol>
 *   <li>Call {@link #createStore()} to get a mail store instance</li>
 *   <li>Call {@link MailboxStore#open(String)} with the username</li>
 *   <li>Call {@link MailboxStore#openMailbox(String, boolean)} to access mailboxes</li>
 * </ol>
 * 
 * <p>For POP3, only the "INBOX" mailbox will be available. For IMAP,
 * multiple mailboxes can be accessed through the store.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface MailboxFactory {

    /**
     * Creates a new mail store instance.
     * Each session should get its own store instance
     * to ensure thread safety and proper session isolation.
     * 
     * @return a new mail store instance
     */
    MailboxStore createStore();

}
