/*
 * MailboxIndexKey.java
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

package org.bluezoo.gumdrop.mailbox.index;

import java.nio.file.Path;

/**
 * Stable identity for a mailbox's search index, used to deduplicate and
 * prioritize {@link MailboxIndexer} jobs.
 *
 * <p>Wraps the absolute path of the mailbox's persisted index file (its
 * {@code .gidx} sidecar for mbox, or the equivalent for maildir) — this is
 * unique per logical mailbox regardless of backend, without either backend
 * needing to expose any other internal identity.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailboxIndexKey {

    private final Path indexPath;

    /**
     * @param indexPath the absolute path of the mailbox's persisted search
     *      index file
     */
    public MailboxIndexKey(Path indexPath) {
        if (indexPath == null) {
            throw new NullPointerException("indexPath");
        }
        this.indexPath = indexPath.toAbsolutePath().normalize();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MailboxIndexKey)) {
            return false;
        }
        return indexPath.equals(((MailboxIndexKey) o).indexPath);
    }

    @Override
    public int hashCode() {
        return indexPath.hashCode();
    }

    @Override
    public String toString() {
        return indexPath.toString();
    }
}
