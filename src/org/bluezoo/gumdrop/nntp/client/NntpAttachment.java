/*
 * NntpAttachment.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.util.AsyncFile;

/**
 * A file to post with an article, as a uuencoded block in the body: the
 * way files travel on Usenet.
 *
 * <p>The content is read as the post is written, a chunk at a time and
 * without blocking the event loop, so a file of any size can be posted in
 * constant memory. It comes from a file ({@link #NntpAttachment(String, int,
 * Path)}), from a {@link Source} of your own ({@link #NntpAttachment(String,
 * int, Source)}) or from memory ({@link #NntpAttachment(String, int,
 * byte[])}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see NntpSession#postWithAttachments(byte[], java.util.List, NntpCallback)
 */
public final class NntpAttachment {

    private final String name;
    private final int mode;
    private final byte[] data;
    private final Path file;
    private final Source source;

    /**
     * Creates an attachment from bytes in memory (not copied).
     *
     * @param name the file name; not empty, and without control characters
     * @param mode the Unix permission bits for the begin line, 0 to 0777
     * (0644 is the usual)
     * @param data the file's content
     * @throws IllegalArgumentException if the name or the mode is not valid
     */
    public NntpAttachment(String name, int mode, byte[] data) {
        this(name, mode, data, null, null);
        if (data == null) {
            throw new IllegalArgumentException("an attachment needs content");
        }
    }

    /**
     * Creates an attachment from a file, read as the post is written.
     *
     * @param name the file name to give in the post; not empty, and without
     * control characters
     * @param mode the Unix permission bits for the begin line, 0 to 0777
     * @param file the file to read
     * @throws IllegalArgumentException if the name or the mode is not valid
     */
    public NntpAttachment(String name, int mode, Path file) {
        this(name, mode, null, file, null);
        if (file == null) {
            throw new IllegalArgumentException("an attachment needs a file");
        }
    }

    /**
     * Creates an attachment from a source of your own, read from the start
     * to its end as the post is written, and closed afterwards.
     *
     * @param name the file name to give in the post; not empty, and without
     * control characters
     * @param mode the Unix permission bits for the begin line, 0 to 0777
     * @param source supplies the content
     * @throws IllegalArgumentException if the name or the mode is not valid
     */
    public NntpAttachment(String name, int mode, Source source) {
        this(name, mode, null, null, source);
        if (source == null) {
            throw new IllegalArgumentException("an attachment needs a source");
        }
    }

    private NntpAttachment(String name, int mode, byte[] data, Path file, Source source) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("an attachment needs a file name");
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < ' ' || name.charAt(i) == 0x7f) {
                throw new IllegalArgumentException("control character in the file name");
            }
        }
        if (mode < 0 || mode > 0777) {
            throw new IllegalArgumentException("mode out of range: " + Integer.toOctalString(mode));
        }
        this.name = name;
        this.mode = mode;
        this.data = data;
        this.file = file;
        this.source = source;
    }

    /**
     * Returns the file name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the permission bits.
     *
     * @return the mode
     */
    public int getMode() {
        return mode;
    }

    // ── reading ──

    /**
     * A sequential, asynchronous reader of an attachment's content. It is
     * called on the connection's event loop, so it must not block.
     */
    public interface Source {

        /**
         * Reads the next piece of the content into {@code dst}. The handler is
         * called (on any thread) with the number of bytes read, or -1 at the
         * end of the content, and the next read is not made until it has been.
         *
         * @param dst where to put the bytes
         * @param handler told how much was read
         */
        void read(ByteBuffer dst, CompletionHandler<Integer, ByteBuffer> handler);

        /** The content has been read, or the post has failed: release what is held. */
        void close();
    }

    /**
     * Starts reading the content.
     *
     * @param storage the executor for files, or null
     * @throws IOException if a file cannot be opened
     */
    Source open(StorageExecutor storage) throws IOException {
        if (data != null) {
            return new MemorySource(data);
        }
        if (file != null) {
            return new FileSource(AsyncFile.open(storage, file, StandardOpenOption.READ));
        }
        return source;
    }

    private static final class MemorySource implements Source {
        private final byte[] data;
        private int position;

        MemorySource(byte[] data) {
            this.data = data;
        }

        @Override
        public void read(ByteBuffer dst, CompletionHandler<Integer, ByteBuffer> handler) {
            int n = Math.min(dst.remaining(), data.length - position);
            if (n <= 0) {
                handler.completed(Integer.valueOf(-1), dst);
                return;
            }
            dst.put(data, position, n);
            position += n;
            handler.completed(Integer.valueOf(n), dst);
        }

        @Override
        public void close() {
        }
    }

    private static final class FileSource implements Source {
        private final AsyncFile file;
        private long position;

        FileSource(AsyncFile file) {
            this.file = file;
        }

        @Override
        public void read(ByteBuffer dst, final CompletionHandler<Integer, ByteBuffer> handler) {
            file.read(dst, position, dst, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer n, ByteBuffer attachment) {
                    if (n.intValue() > 0) {
                        position += n.intValue();
                    }
                    handler.completed(n, attachment);
                }

                @Override
                public void failed(Throwable error, ByteBuffer attachment) {
                    handler.failed(error, attachment);
                }
            });
        }

        @Override
        public void close() {
            try {
                file.close();
            } catch (IOException e) {
                // read only: nothing is lost
            }
        }
    }

}
