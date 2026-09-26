/*
 * ImapMetadataGetRequest.java
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

package org.bluezoo.gumdrop.imap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed GETMETADATA command arguments (RFC 5464).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapMetadataGetRequest {

    private final String mailboxName;
    private final int maxSize;
    private final int depth;
    private final List<String> entryNames;

    public ImapMetadataGetRequest(String mailboxName, int maxSize, int depth,
            List<String> entryNames) {
        this.mailboxName = mailboxName != null ? mailboxName : "";
        this.maxSize = maxSize;
        this.depth = depth;
        this.entryNames = new ArrayList<String>(entryNames);
    }

    public String getMailboxName() {
        return mailboxName;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public int getDepth() {
        return depth;
    }

    public List<String> getEntryNames() {
        return Collections.unmodifiableList(entryNames);
    }

    public boolean isServerMetadata() {
        return mailboxName.isEmpty();
    }
}
