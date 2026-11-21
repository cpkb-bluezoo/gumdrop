/*
 * ArgumentStream.java
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

package org.bluezoo.gumdrop.redis.client;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One reading of an {@link ArgumentSource}, pulled by the connection as it can
 * take the bytes. Called on the connection's event loop, so it must not
 * block: a stream whose data is not at hand returns 0 and tells its
 * {@link ArgumentSink} when there is more.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ArgumentStream {

    /**
     * Puts the next bytes of the argument into the buffer.
     *
     * @param dst the buffer, from its position to its limit
     * @return the number of bytes put; 0 if there are none to give now (the
     * stream calls {@link ArgumentSink#ready()} when there are); -1 once the
     * argument is complete
     * @throws IOException if the argument cannot be read: the connection is
     * closed, as for {@link ArgumentSink#fail}
     */
    int read(ByteBuffer dst) throws IOException;

    /**
     * Releases what the stream holds. Called once, when the argument has been
     * sent or the attempt is abandoned.
     */
    void close();

}
