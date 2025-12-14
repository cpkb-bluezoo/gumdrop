/*
 * MailboxAttribute.java
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
 * Enumeration of IMAP mailbox attributes.
 * 
 * <p>These attributes are defined in RFC 9051 (IMAP4rev2) Section 7.2.2
 * and RFC 6154 (Special-Use Mailboxes).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051#section-7.2.2">RFC 9051 Section 7.2.2</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6154">RFC 6154 Special-Use Mailboxes</a>
 */
public enum MailboxAttribute {

    // ============== Standard Attributes (RFC 9051) ==============

    /**
     * Mailbox cannot have any child mailboxes.
     * IMAP atom: \Noinferiors
     */
    NOINFERIORS("\\Noinferiors"),

    /**
     * Not a real mailbox; just a placeholder in the hierarchy.
     * It cannot be selected.
     * IMAP atom: \Noselect
     */
    NOSELECT("\\Noselect"),

    /**
     * The mailbox has been marked "interesting" by the server.
     * IMAP atom: \Marked
     */
    MARKED("\\Marked"),

    /**
     * The mailbox does not contain any additional messages since
     * the last time it was selected.
     * IMAP atom: \Unmarked
     */
    UNMARKED("\\Unmarked"),

    /**
     * The mailbox has child mailboxes that are accessible.
     * Mutually exclusive with HASNOCHILDREN.
     * IMAP atom: \HasChildren
     */
    HASCHILDREN("\\HasChildren"),

    /**
     * The mailbox has no child mailboxes that are accessible.
     * Mutually exclusive with HASCHILDREN.
     * IMAP atom: \HasNoChildren
     */
    HASNOCHILDREN("\\HasNoChildren"),

    /**
     * The mailbox is subscribed.
     * IMAP atom: \Subscribed
     */
    SUBSCRIBED("\\Subscribed"),

    /**
     * The mailbox does not actually exist, but is a placeholder
     * for child mailboxes.
     * IMAP atom: \NonExistent
     */
    NONEXISTENT("\\NonExistent"),

    /**
     * The mailbox exists on a remote server.
     * IMAP atom: \Remote
     */
    REMOTE("\\Remote"),

    // ============== Special-Use Attributes (RFC 6154) ==============

    /**
     * Virtual mailbox containing all messages.
     * IMAP atom: \All
     */
    ALL("\\All"),

    /**
     * Used for archive messages.
     * IMAP atom: \Archive
     */
    ARCHIVE("\\Archive"),

    /**
     * Used for draft messages.
     * IMAP atom: \Drafts
     */
    DRAFTS("\\Drafts"),

    /**
     * Virtual mailbox containing all flagged messages.
     * IMAP atom: \Flagged
     */
    FLAGGED("\\Flagged"),

    /**
     * Used for important messages.
     * IMAP atom: \Important
     */
    IMPORTANT("\\Important"),

    /**
     * Used for junk/spam messages.
     * IMAP atom: \Junk
     */
    JUNK("\\Junk"),

    /**
     * Used for sent messages.
     * IMAP atom: \Sent
     */
    SENT("\\Sent"),

    /**
     * Used for deleted/trash messages.
     * IMAP atom: \Trash
     */
    TRASH("\\Trash");

    private final String imapAtom;

    MailboxAttribute(String imapAtom) {
        this.imapAtom = imapAtom;
    }

    /**
     * Returns the IMAP atom representation of this attribute.
     * For example, NOSELECT returns "\\Noselect".
     *
     * @return the IMAP attribute atom
     */
    public String getImapAtom() {
        return imapAtom;
    }

    /**
     * Parses an IMAP attribute atom string to the corresponding enum value.
     * The comparison is case-insensitive.
     *
     * @param atom the IMAP attribute atom (e.g., "\\Noselect", "\\NOSELECT")
     * @return the corresponding MailboxAttribute, or null if not recognized
     */
    public static MailboxAttribute fromImapAtom(String atom) {
        if (atom == null || atom.isEmpty()) {
            return null;
        }
        for (MailboxAttribute attr : values()) {
            if (attr.imapAtom.equalsIgnoreCase(atom)) {
                return attr;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return imapAtom;
    }

}

