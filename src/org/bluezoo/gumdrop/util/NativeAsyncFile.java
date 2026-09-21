/*
 * NativeAsyncFile.java
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

package org.bluezoo.gumdrop.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;

/**
 * {@link AsyncFile} over a real {@link AsynchronousFileChannel}: every call
 * is delegated, so behaviour is exactly that of the JDK channel.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class NativeAsyncFile implements AsyncFile {

    private final AsynchronousFileChannel channel;

    NativeAsyncFile(AsynchronousFileChannel channel) {
        this.channel = channel;
    }

    @Override
    public <A> void read(ByteBuffer dst, long position, A attachment,
            CompletionHandler<Integer, ? super A> handler) {
        channel.read(dst, position, attachment, handler);
    }

    @Override
    public <A> void write(ByteBuffer src, long position, A attachment,
            CompletionHandler<Integer, ? super A> handler) {
        channel.write(src, position, attachment, handler);
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
