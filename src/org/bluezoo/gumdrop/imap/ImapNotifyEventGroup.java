/*
 * ImapNotifyEventGroup.java
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
 * RFC 5465 NOTIFY SET event group: mailbox filter plus event list.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapNotifyEventGroup {

    private final ImapNotifyMailboxFilter mailboxFilter;
    private final List<ImapNotifyEventSpec> events;
    private final boolean eventsNone;

    public ImapNotifyEventGroup(ImapNotifyMailboxFilter mailboxFilter,
            List<ImapNotifyEventSpec> events, boolean eventsNone) {
        this.mailboxFilter = mailboxFilter;
        this.events = new ArrayList<ImapNotifyEventSpec>(events);
        this.eventsNone = eventsNone;
    }

    public ImapNotifyMailboxFilter getMailboxFilter() {
        return mailboxFilter;
    }

    public List<ImapNotifyEventSpec> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public boolean isEventsNone() {
        return eventsNone;
    }
}
