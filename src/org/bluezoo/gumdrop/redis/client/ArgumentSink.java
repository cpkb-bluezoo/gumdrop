/*
 * ArgumentSink.java
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

/**
 * Given to an {@link ArgumentSource} when it is opened, so that a stream that
 * has nothing to give at the moment can tell the connection when it has.
 * Both methods may be called from any thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ArgumentSink {

    /**
     * More of the argument can now be read: call after
     * {@link ArgumentStream#read} returned 0.
     */
    void ready();

    /**
     * The argument cannot be completed. The connection is closed, the
     * command fails, and so do the commands behind it, since the length
     * of the argument has been announced and the only way to resynchronise
     * is to start again.
     *
     * @param cause why
     */
    void fail(IOException cause);

}
