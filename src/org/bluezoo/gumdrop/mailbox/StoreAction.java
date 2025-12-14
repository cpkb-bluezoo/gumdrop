/*
 * StoreAction.java
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
 * Enumeration of IMAP STORE command actions.
 * 
 * <p>The STORE command modifies the flags of messages. This enum defines
 * the three operations that can be performed on message flags as specified
 * in RFC 9051 Section 6.4.6.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051#section-6.4.6">RFC 9051 Section 6.4.6</a>
 */
public enum StoreAction {

    /**
     * Replace the existing flags with the specified flags.
     * IMAP command: FLAGS or FLAGS.SILENT
     */
    REPLACE("FLAGS"),

    /**
     * Add the specified flags to the existing flags.
     * IMAP command: +FLAGS or +FLAGS.SILENT
     */
    ADD("+FLAGS"),

    /**
     * Remove the specified flags from the existing flags.
     * IMAP command: -FLAGS or -FLAGS.SILENT
     */
    REMOVE("-FLAGS");

    private final String imapKeyword;

    StoreAction(String imapKeyword) {
        this.imapKeyword = imapKeyword;
    }

    /**
     * Returns the IMAP keyword representation of this action.
     * For example, ADD returns "+FLAGS".
     * 
     * @return the IMAP keyword (without .SILENT suffix)
     */
    public String getImapKeyword() {
        return imapKeyword;
    }

    /**
     * Parses an IMAP store action keyword to the corresponding StoreAction enum value.
     * The comparison is case-insensitive. The .SILENT suffix is ignored if present.
     * 
     * @param keyword the IMAP keyword (e.g., "FLAGS", "+FLAGS", "-FLAGS.SILENT")
     * @return the corresponding StoreAction, or null if not recognized
     */
    public static StoreAction fromImapKeyword(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        // Remove .SILENT suffix if present
        String normalized = keyword.toUpperCase();
        if (normalized.endsWith(".SILENT")) {
            normalized = normalized.substring(0, normalized.length() - 7);
        }
        for (StoreAction action : values()) {
            if (action.imapKeyword.equals(normalized)) {
                return action;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return imapKeyword;
    }
}

