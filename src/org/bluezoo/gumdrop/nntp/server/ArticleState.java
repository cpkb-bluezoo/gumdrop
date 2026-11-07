/*
 * ArticleState.java
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

import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;

/**
 * Responses to ARTICLE, HEAD, BODY, STAT, LAST and NEXT (RFC 3977
 * sections 6.1.3 to 6.2.4).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ArticleState {

    /**
     * The article exists. It becomes the current article, except where
     * it was identified by message-id.
     *
     * @param number the article number
     * @param messageId the message-id, with angle brackets
     * @param content the whole article, which the protocol closes once it
     * has sent the part requested; may be null for STAT, LAST and NEXT
     * @param handler the handler for the next command
     */
    void found(long number, String messageId, AsyncMessageContent content,
               ReaderHandler handler);

    /**
     * There is no such article (423, 430, 420, 421 or 422, according to how
     * it was identified).
     * @param handler the handler for the next command
     */
    void notFound(ReaderHandler handler);

    /**
     * The article could not be read.
     * @param message the text after the response code
     * @param handler the handler for the next command
     */
    void error(String message, ReaderHandler handler);

}
