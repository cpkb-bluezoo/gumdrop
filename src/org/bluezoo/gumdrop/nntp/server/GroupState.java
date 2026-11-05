/*
 * GroupState.java
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

import org.bluezoo.gumdrop.mailbox.Mailbox;

/**
 * Responses to a group selection (GROUP and LISTGROUP, RFC 3977 sections
 * 6.1.1 and 6.1.2).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface GroupState {

    /**
     * The group exists and becomes the current group. The protocol
     * closes the mailbox of the previously selected group.
     *
     * @param mailbox the open mailbox of the group
     * @param group the group name
     * @param count an estimate of the number of articles
     * @param low the lowest article number, 0 if none
     * @param high the highest article number, 0 if none
     * @param handler the handler for the next command
     */
    void selected(Mailbox mailbox, String group, long count, long low,
                  long high, ReaderHandler handler);

    /**
     * There is no such group (411).
     * @param handler the handler for the next command
     */
    void noSuchGroup(ReaderHandler handler);

    /**
     * The group could not be selected.
     * @param message the text after the response code
     * @param handler the handler for the next command
     */
    void error(String message, ReaderHandler handler);

}
