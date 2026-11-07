/*
 * NntpPoster.java
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

package org.bluezoo.gumdrop.nntp.client;

/**
 * Supplies the article of a streaming POST (RFC 3977 section 6.3.1). The
 * session calls {@link #ready} when the server has agreed to take the
 * article (340); the poster then writes it to the {@link NntpPostStream} in
 * chunks of any size and finishes it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see NntpSession#post(NntpPoster, NntpCallback)
 */
public interface NntpPoster {

    /**
     * The server is waiting for the article. Called on the connection's
     * event loop. Write the article to the stream, one chunk at a time as
     * each is written out, and finish it.
     *
     * @param stream where to write the article
     */
    void ready(NntpPostStream stream);

}
