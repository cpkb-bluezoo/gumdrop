/*
 * PostState.java
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
 * Responses to POST (RFC 3977 section 6.3.1). The handler first accepts
 * the post, giving the sink that will receive the article, or refuses it.
 * Once the article has been received, the sink answers with {@link #posted}
 * or {@link #notPosted}.
 *
 * <p>All methods may be called from any thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface PostState {

    /**
     * Posting may go ahead (340). The client sends the article next.
     *
     * @param sink receives the article
     */
    void accept(PostSink sink);

    /**
     * Posting is not permitted (440).
     *
     * @param handler the handler for the next command
     */
    void refuse(ReaderHandler handler);

    /**
     * The article was accepted (240).
     *
     * @param handler the handler for the next command
     */
    void posted(ReaderHandler handler);

    /**
     * The article was not accepted (441).
     *
     * @param message the reason
     * @param handler the handler for the next command
     */
    void notPosted(String message, ReaderHandler handler);

}
