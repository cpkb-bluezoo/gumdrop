/*
 * ListType.java
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

package org.bluezoo.gumdrop.nntp.server;

/**
 * Which group listing a client asked for (RFC 3977 sections 7.3 and 7.6).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum ListType {

    /** LIST ACTIVE: name, high, low and posting status. */
    ACTIVE,

    /** LIST NEWSGROUPS: name and description. */
    NEWSGROUPS,

    /** NEWGROUPS: as ACTIVE, for groups created since a time. */
    NEW_GROUPS

}
