/*
 * ArgumentSource.java
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

import java.nio.ByteBuffer;

/**
 * A request argument whose bytes are pulled as the connection can take them,
 * so that a value larger than memory can be written to Redis without being
 * held. RESP puts the length of each argument before its bytes, so a source
 * declares its {@link #length()} up front and must deliver exactly that.
 *
 * <p>A command can be sent more than once: a cluster follows {@code MOVED}
 * and {@code ASK} redirections, and a connection can be lost and redialled.
 * Each attempt calls {@link #open} afresh. A source that can be read only
 * once returns null from the second {@code open}, and the command then fails
 * with a {@code NOT_REPEATABLE} error instead of sending a short value.
 *
 * <p>A key must be in memory, as a cluster reads it to route the command.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ArgumentSource {

    /**
     * Returns the length of the argument in bytes.
     *
     * @return the length, at least 0
     */
    long length();

    /**
     * Opens the argument for one attempt to send it, from its beginning.
     *
     * @param sink for a stream that has to wait to tell the connection when
     * it has more
     * @return the stream, or null if this source cannot be opened again
     */
    ArgumentStream open(ArgumentSink sink);

    /**
     * Returns a source for bytes already in memory. It can be opened any
     * number of times. The array is not copied, so it must not be changed
     * while the command is in flight.
     *
     * @param bytes the argument
     * @return the source
     */
    static ArgumentSource of(final byte[] bytes) {
        return new ArgumentSource() {
            @Override
            public long length() {
                return bytes.length;
            }

            @Override
            public ArgumentStream open(ArgumentSink sink) {
                return new ArgumentStream() {
                    private int position;

                    @Override
                    public int read(ByteBuffer dst) {
                        int n = Math.min(dst.remaining(), bytes.length - position);
                        if (n <= 0 && position >= bytes.length) {
                            return -1;
                        }
                        dst.put(bytes, position, n);
                        position += n;
                        return n;
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

}
