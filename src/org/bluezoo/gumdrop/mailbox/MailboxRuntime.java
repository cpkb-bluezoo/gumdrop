/*
 * MailboxRuntime.java
 * Copyright (C) 2026 Chris Burdess
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

import java.io.IOException;

import org.bluezoo.gumdrop.mailbox.index.MailboxIndexer;
import org.bluezoo.gumdrop.mailbox.index.MailboxWatcher;

/**
 * Shared mailbox background services for maildir/mbox stores.
 *
 * <p>Started via {@link org.bluezoo.gumdrop.mailbox.spi.MailboxLifecycle}
 * when {@code gumdrop-mailbox.jar} is on the classpath; {@code null}
 * indexer/watcher when the jar is absent.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailboxRuntime {

    private static MailboxIndexer indexer;
    private static MailboxWatcher watcher;

    private MailboxRuntime() {
    }

    static void start() throws IOException {
        if (indexer == null) {
            indexer = new MailboxIndexer();
        }
        if (watcher == null) {
            watcher = new MailboxWatcher();
        }
    }

    static void shutdown() {
        if (indexer != null) {
            indexer.shutdown();
            indexer = null;
        }
        if (watcher != null) {
            watcher.shutdown();
            watcher = null;
        }
    }

    /**
     * Returns the shared background indexer, or {@code null} if mailbox support
     * is not loaded.
     */
    public static MailboxIndexer getIndexer() {
        return indexer;
    }

    /**
     * Returns the shared filesystem watcher, or {@code null} if mailbox support
     * is not loaded.
     */
    public static MailboxWatcher getWatcher() {
        return watcher;
    }

}
