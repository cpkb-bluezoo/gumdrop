/*
 * MboxMailboxFactory.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Factory for creating mbox-format mail store instances.
 * 
 * <p>This factory creates mail stores that access mbox-format mailbox files.
 * Both POP3 and IMAP use the store to access mailboxes.
 * 
 * <p>The factory can be configured with a file extension for mbox files
 * (default: ".mbox"). Only files with this extension are treated as mailboxes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MboxMailboxStore
 * @see MboxMailbox
 */
public class MboxMailboxFactory implements MailboxFactory {

    private Path basedir;
    private String extension = MboxMailboxStore.DEFAULT_EXTENSION;

    /**
     * Creates a new mbox mailbox factory.
     * The base directory must be set via {@link #setBaseDirectory(String)} before use.
     */
    public MboxMailboxFactory() {
    }

    /**
     * Creates a new mbox mailbox factory with default extension (.mbox).
     * 
     * @param basedir the base directory containing all user mailboxes
     */
    public MboxMailboxFactory(File basedir) {
        this(basedir.toPath(), MboxMailboxStore.DEFAULT_EXTENSION);
    }

    /**
     * Creates a new mbox mailbox factory with default extension (.mbox).
     * 
     * @param basedir the base directory containing all user mailboxes
     */
    public MboxMailboxFactory(Path basedir) {
        this(basedir, MboxMailboxStore.DEFAULT_EXTENSION);
    }

    /**
     * Creates a new mbox mailbox factory with custom extension.
     * 
     * @param basedir the base directory containing all user mailboxes
     * @param extension the file extension for mbox files (e.g., ".mbox")
     */
    public MboxMailboxFactory(Path basedir, String extension) {
        if (basedir == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }
        this.basedir = basedir.toAbsolutePath().normalize();
        this.extension = extension;
    }

    /**
     * Sets the base directory for all mailboxes.
     * 
     * @param baseDirectory the base directory path as a string
     */
    public void setBaseDirectory(String baseDirectory) {
        if (baseDirectory == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }
        this.basedir = Paths.get(baseDirectory).toAbsolutePath().normalize();
    }

    /**
     * Sets the base directory for all mailboxes.
     * 
     * @param baseDirectory the base directory path
     */
    public void setBaseDirectory(Path baseDirectory) {
        if (baseDirectory == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }
        this.basedir = baseDirectory.toAbsolutePath().normalize();
    }

    /**
     * Returns the base directory for all mailboxes.
     * 
     * @return the base directory path
     */
    public Path getBaseDirectory() {
        return basedir;
    }

    /**
     * Sets the file extension for mbox files.
     * 
     * @param extension the extension (e.g., ".mbox")
     */
    public void setExtension(String extension) {
        this.extension = extension;
    }

    /**
     * Returns the file extension used for mbox files.
     * 
     * @return the extension (including leading dot)
     */
    public String getExtension() {
        return extension;
    }

    @Override
    public MailboxStore createStore() {
        if (basedir == null) {
            throw new IllegalStateException("Base directory not configured");
        }
        return new MboxMailboxStore(basedir, extension);
    }

}
