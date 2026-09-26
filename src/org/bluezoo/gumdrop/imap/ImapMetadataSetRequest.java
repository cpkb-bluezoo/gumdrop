/*
 * ImapMetadataSetRequest.java
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
 * Parsed SETMETADATA command arguments (RFC 5464).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapMetadataSetRequest {

    public static final class EntryValue {
        public final String entryName;
        public final String value;

        public EntryValue(String entryName, String value) {
            this.entryName = entryName;
            this.value = value;
        }
    }

    private final String mailboxName;
    private final List<EntryValue> entries;

    public ImapMetadataSetRequest(String mailboxName, List<EntryValue> entries) {
        this.mailboxName = mailboxName != null ? mailboxName : "";
        this.entries = new ArrayList<EntryValue>(entries);
    }

    public String getMailboxName() {
        return mailboxName;
    }

    public List<EntryValue> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
