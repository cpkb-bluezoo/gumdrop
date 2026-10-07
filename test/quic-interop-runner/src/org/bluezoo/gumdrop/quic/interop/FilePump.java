/*
 * FilePump.java
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

package org.bluezoo.gumdrop.quic.interop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Streams a file to a peer one chunk at a time, keeping at most one chunk
 * queued in the transport: the next chunk is read only once the previous
 * one has been handed to the packetiser, so a slow or flow-control-blocked
 * peer never makes the sender buffer the whole file.
 *
 * <p>The transport's write-ready notification can fire synchronously from
 * inside the send call that drained the queue, so the continuation is
 * always rescheduled on the selector loop rather than run in place; this
 * keeps the pump iterative however fast the path is.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class FilePump implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(FilePump.class.getName());

    static final int CHUNK_SIZE = 64 * 1024;

    private final FileChannel channel;
    private final ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
    private final Runnable resume = new Runnable() {
        @Override
        public void run() {
            execute(FilePump.this);
        }
    };
    private boolean finished;

    FilePump(FileChannel channel) {
        this.channel = channel;
    }

    /** Hands one chunk to the transport. */
    abstract void write(ByteBuffer data);

    /** Called when the file has been written completely. */
    abstract void finish();

    /** Called when the file could not be read; the response should be cut short. */
    abstract void abort();

    /** Asks to be called back once the transport has drained what it was given. */
    abstract void onWritable(Runnable callback);

    /** Runs the task on the transport's selector loop, later. */
    abstract void execute(Runnable task);

    void start() {
        run();
    }

    @Override
    public void run() {
        if (finished) {
            return;
        }
        int n;
        try {
            buffer.clear();
            n = channel.read(buffer);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "reading served file failed", e);
            finished = true;
            closeChannel();
            abort();
            return;
        }
        if (n < 0) {
            finished = true;
            closeChannel();
            finish();
            return;
        }
        buffer.flip();
        // Register before writing: a synchronous flush inside write() may
        // drain the queue and deliver the notification immediately.
        onWritable(resume);
        write(buffer);
    }

    private void closeChannel() {
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "closing served file failed", e);
        }
    }

}
