/*
 * ImapNotifyRequest.java
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
 * Parsed NOTIFY command (RFC 5465).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapNotifyRequest {

    public enum Form {
        NONE,
        SET
    }

    private final Form form;
    private final boolean statusIndicator;
    private final List<ImapNotifyEventGroup> eventGroups;

    private ImapNotifyRequest(Form form, boolean statusIndicator,
            List<ImapNotifyEventGroup> eventGroups) {
        this.form = form;
        this.statusIndicator = statusIndicator;
        this.eventGroups = eventGroups;
    }

    public static ImapNotifyRequest none() {
        return new ImapNotifyRequest(Form.NONE, false,
                Collections.<ImapNotifyEventGroup>emptyList());
    }

    public static ImapNotifyRequest set(boolean statusIndicator,
            List<ImapNotifyEventGroup> groups) {
        return new ImapNotifyRequest(Form.SET, statusIndicator,
                new ArrayList<ImapNotifyEventGroup>(groups));
    }

    public Form getForm() {
        return form;
    }

    public boolean isStatusIndicator() {
        return statusIndicator;
    }

    public List<ImapNotifyEventGroup> getEventGroups() {
        return Collections.unmodifiableList(eventGroups);
    }
}
