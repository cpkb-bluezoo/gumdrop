/*
 * MaildirMailboxFactory.java
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

package org.bluezoo.gumdrop.mailbox.maildir;

import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import java.io.File;
import java.nio.file.Path;

/**
 * Factory for creating Maildir-format mail store instances.
 * 
 * <p>This factory creates mail stores that access Maildir-format mailbox
 * directories. Both POP3 and IMAP use the store to access mailboxes.
 * 
 * <p>Each user has a directory under the base directory. The user's INBOX
 * is the root of their directory (containing cur/, new/, tmp/), while
 * subfolders are directories starting with a dot (Maildir++ convention).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MaildirMailboxStore
 * @see MaildirMailbox
 */
public class MaildirMailboxFactory implements MailboxFactory {

    private final Path basedir;

    /**
     * Creates a new Maildir mailbox factory.
     * 
     * @param basedir the base directory containing all user mailboxes
     */
    public MaildirMailboxFactory(File basedir) {
        this(basedir.toPath());
    }

    /**
     * Creates a new Maildir mailbox factory.
     * 
     * @param basedir the base directory containing all user mailboxes
     */
    public MaildirMailboxFactory(Path basedir) {
        if (basedir == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }
        this.basedir = basedir.toAbsolutePath().normalize();
    }

    /**
     * Returns the base directory for all mailboxes.
     * 
     * @return the base directory path
     */
    public Path getBaseDirectory() {
        return basedir;
    }

    @Override
    public MailboxStore createStore() {
        return new MaildirMailboxStore(basedir);
    }

}


