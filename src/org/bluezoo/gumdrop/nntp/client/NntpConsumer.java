/*
 * NntpConsumer.java
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
 * Receives the parts of a multi-line response as they arrive, called on the
 * connection's event loop.
 *
 * @param <T> what is delivered: {@link java.nio.ByteBuffer} slices of article
 * text, or parsed entries
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface NntpConsumer<T> {

    /**
     * The next part. A {@link java.nio.ByteBuffer} is valid only during the
     * call.
     *
     * @param item the part
     */
    void accept(T item);

}
