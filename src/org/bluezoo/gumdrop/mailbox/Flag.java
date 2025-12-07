/*
 * Flag.java
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

import java.util.EnumSet;
import java.util.Set;

/**
 * Enumeration of standard IMAP message flags.
 * 
 * <p>These flags are defined in RFC 9051 (IMAP4rev2) Section 2.3.2.
 * While the IMAP specification theoretically allows for custom flags,
 * in practice only these standard flags are widely used and understood.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051#section-2.3.2">RFC 9051 Section 2.3.2</a>
 */
public enum Flag {

    /**
     * Message has been read.
     * IMAP atom: \Seen
     */
    SEEN("\\Seen"),

    /**
     * Message has been answered.
     * IMAP atom: \Answered
     */
    ANSWERED("\\Answered"),

    /**
     * Message is flagged for urgent/special attention.
     * IMAP atom: \Flagged
     */
    FLAGGED("\\Flagged"),

    /**
     * Message is marked for deletion.
     * The message will be permanently removed when the mailbox is expunged.
     * IMAP atom: \Deleted
     */
    DELETED("\\Deleted"),

    /**
     * Message is a draft (incomplete composition).
     * IMAP atom: \Draft
     */
    DRAFT("\\Draft"),

    /**
     * Message is "recent" - this is the first session to be notified about this message.
     * This flag is read-only and cannot be altered by the client.
     * IMAP atom: \Recent
     */
    RECENT("\\Recent");

    private final String imapAtom;

    Flag(String imapAtom) {
        this.imapAtom = imapAtom;
    }

    /**
     * Returns the IMAP atom representation of this flag.
     * For example, SEEN returns "\\Seen".
     *
     * @return the IMAP flag atom
     */
    public String getImapAtom() {
        return imapAtom;
    }

    /**
     * Parses an IMAP flag atom string to the corresponding Flag enum value.
     * The comparison is case-insensitive.
     *
     * @param atom the IMAP flag atom (e.g., "\\Seen", "\\SEEN", "\\seen")
     * @return the corresponding Flag, or null if not recognized
     */
    public static Flag fromImapAtom(String atom) {
        if (atom == null || atom.isEmpty()) {
            return null;
        }
        for (Flag flag : values()) {
            if (flag.imapAtom.equalsIgnoreCase(atom)) {
                return flag;
            }
        }
        return null;
    }

    /**
     * Returns the set of all permanent flags.
     * Permanent flags are those that can be stored persistently.
     * This excludes RECENT which is session-specific and read-only.
     *
     * @return set of permanent flags
     */
    public static Set<Flag> permanentFlags() {
        return EnumSet.of(SEEN, ANSWERED, FLAGGED, DELETED, DRAFT);
    }

    /**
     * Returns the set of all flags.
     *
     * @return set of all flags
     */
    public static Set<Flag> allFlags() {
        return EnumSet.allOf(Flag.class);
    }

    @Override
    public String toString() {
        return imapAtom;
    }
}

