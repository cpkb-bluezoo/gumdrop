/*
 * PostSink.java
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

import java.nio.ByteBuffer;

/**
 * Receives an article being posted (RFC 3977 section 6.3.1).
 *
 * <p>The article arrives dot-unstuffed, as it was sent, in chunks of any
 * size, and is never held whole by the server: a sink that stores it does so
 * as it arrives. {@link #content} is called on the event loop and the buffer
 * is valid only during the call: the sink consumes it at once or copies what
 * it needs, and must not block. When it holds more than it wants, it asks the
 * server to stop reading with {@link PostState#pauseInput()} and lets it go
 * on with {@link PostState#resumeInput()}; chunks already read when it asks
 * still arrive, so the most a sink can be handed after asking is one network
 * read.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface PostSink {

    /**
     * A chunk of the article.
     *
     * @param chunk the bytes, ready for reading
     */
    void content(ByteBuffer chunk);

    /**
     * The whole article has been received. This is called off the event
     * loop, so it may block. The sink answers through the state, by calling
     * {@link PostState#posted} or {@link PostState#notPosted}.
     *
     * @param state the state to answer through
     */
    void end(PostState state);

    /**
     * The connection was lost before the article was complete.
     */
    void abort();

}
