/*
 * StatusItem.java
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

package org.bluezoo.gumdrop.imap;

/**
 * Status data items that can be requested in an IMAP STATUS command.
 * 
 * <p>These correspond to the status data items defined in RFC 9051 Section 6.3.11.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051#section-6.3.11">RFC 9051 Section 6.3.11</a>
 */
public enum StatusItem {

    /**
     * The number of messages in the mailbox.
     */
    MESSAGES("MESSAGES"),

    /**
     * The number of messages with the \Recent flag set.
     * 
     * <p>Note: This is deprecated in IMAP4rev2 but still supported for
     * backward compatibility.
     */
    RECENT("RECENT"),

    /**
     * The next unique identifier value of the mailbox.
     */
    UIDNEXT("UIDNEXT"),

    /**
     * The unique identifier validity value of the mailbox.
     */
    UIDVALIDITY("UIDVALIDITY"),

    /**
     * The number of messages which do not have the \Seen flag set.
     */
    UNSEEN("UNSEEN"),

    /**
     * The number of messages with the \Deleted flag set.
     * 
     * <p>This is an IMAP4rev2 extension.
     */
    DELETED("DELETED"),

    /**
     * The total size of the mailbox in octets.
     * 
     * <p>This is an IMAP4rev2 extension.
     */
    SIZE("SIZE"),

    /**
     * The highest mod-sequence value of all messages in the mailbox.
     * 
     * <p>Requires CONDSTORE extension (RFC 7162).
     */
    HIGHESTMODSEQ("HIGHESTMODSEQ"),

    /**
     * The mailbox append limit in octets.
     * 
     * <p>Requires APPENDLIMIT extension (RFC 7889).
     */
    APPENDLIMIT("APPENDLIMIT");

    private final String imapName;

    StatusItem(String imapName) {
        this.imapName = imapName;
    }

    /**
     * Returns the IMAP protocol name for this status item.
     * 
     * @return the IMAP name (e.g., "MESSAGES", "UIDNEXT")
     */
    public String getImapName() {
        return imapName;
    }

    /**
     * Parses an IMAP status item name to the corresponding enum value.
     * 
     * @param name the IMAP status item name (case-insensitive)
     * @return the corresponding StatusItem, or null if not recognized
     */
    public static StatusItem fromImapName(String name) {
        if (name == null) {
            return null;
        }
        String upper = name.toUpperCase();
        for (StatusItem item : values()) {
            if (item.imapName.equals(upper)) {
                return item;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return imapName;
    }

}

