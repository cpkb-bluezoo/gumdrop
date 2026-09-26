/*
 * ImapNotifyMailboxFilter.java
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
 * Mailbox selector from an RFC 5465 NOTIFY SET event group.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapNotifyMailboxFilter {

    public enum Kind {
        SELECTED,
        SELECTED_DELAYED,
        INBOXES,
        PERSONAL,
        SUBSCRIBED,
        SUBTREE,
        MAILBOXES
    }

    private final Kind kind;
    private final List<String> mailboxNames;

    private ImapNotifyMailboxFilter(Kind kind, List<String> mailboxNames) {
        this.kind = kind;
        this.mailboxNames = mailboxNames;
    }

    public static ImapNotifyMailboxFilter selected(boolean delayed) {
        return new ImapNotifyMailboxFilter(
                delayed ? Kind.SELECTED_DELAYED : Kind.SELECTED,
                Collections.<String>emptyList());
    }

    public static ImapNotifyMailboxFilter inboxes() {
        return new ImapNotifyMailboxFilter(Kind.INBOXES,
                Collections.<String>emptyList());
    }

    public static ImapNotifyMailboxFilter personal() {
        return new ImapNotifyMailboxFilter(Kind.PERSONAL,
                Collections.<String>emptyList());
    }

    public static ImapNotifyMailboxFilter subscribed() {
        return new ImapNotifyMailboxFilter(Kind.SUBSCRIBED,
                Collections.<String>emptyList());
    }

    public static ImapNotifyMailboxFilter subtree(List<String> roots) {
        return new ImapNotifyMailboxFilter(Kind.SUBTREE,
                new ArrayList<String>(roots));
    }

    public static ImapNotifyMailboxFilter mailboxes(List<String> names) {
        return new ImapNotifyMailboxFilter(Kind.MAILBOXES,
                new ArrayList<String>(names));
    }

    public Kind getKind() {
        return kind;
    }

    public List<String> getMailboxNames() {
        return mailboxNames;
    }

    public boolean affectsSelectedMailbox() {
        return kind == Kind.SELECTED || kind == Kind.SELECTED_DELAYED;
    }
}
