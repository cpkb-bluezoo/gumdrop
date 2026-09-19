/*
 * EnableReplyHandler.java
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

package org.bluezoo.gumdrop.imap.client;

import java.util.List;

/**
 * Handler for ENABLE command replies (RFC 5161, RFC 6855, RFC 7162).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface EnableReplyHandler extends ReplyHandler {

    /**
     * Called when the server accepts one or more extensions.
     *
     * @param session authenticated (or selected) session
     * @param enabled extension names from untagged ENABLED responses
     */
    void handleEnabled(ClientAuthenticatedState session,
            List<String> enabled);

    /**
     * Called when ENABLE fails with a tagged NO or BAD.
     *
     * @param session session in the pre-failure state
     * @param message server response text
     */
    void handleError(ClientAuthenticatedState session, String message);
}
