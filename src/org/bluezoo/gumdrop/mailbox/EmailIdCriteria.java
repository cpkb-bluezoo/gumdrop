/*
 * EmailIdCriteria.java
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;

/**
 * A {@link SearchCriteria} matching the message with a specific RFC 8474
 * EMAILID, as produced by {@link SearchCriteria#emailId}.
 *
 * <p>A named, inspectable class rather than an anonymous one (mirroring
 * {@link FlagCriteria}, issue #304): {@code
 * org.bluezoo.gumdrop.mailbox.index.MessageIndex} needs to recognise
 * this specific shape of criteria via {@code instanceof} to answer it
 * in O(1) from its EMAILID reverse index instead of scanning every
 * message. Constructed only by {@link SearchCriteria}'s own factory
 * methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class EmailIdCriteria implements SearchCriteria {

    private final String emailId;

    EmailIdCriteria(String emailId) {
        this.emailId = emailId;
    }

    /**
     * Returns the EMAILID this criteria matches.
     *
     * @return the EMAILID
     */
    public String getEmailId() {
        return emailId;
    }

    @Override
    public boolean matches(MessageContext context) throws IOException {
        String contextEmailId = context.getEmailId();
        return contextEmailId != null && contextEmailId.equals(emailId);
    }

    @Override
    public String toString() {
        return "EmailIdCriteria[" + emailId + "]";
    }
}
