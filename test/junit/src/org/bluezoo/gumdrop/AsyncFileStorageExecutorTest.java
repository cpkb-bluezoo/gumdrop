/*
 * AsyncFileStorageExecutorTest.java
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

package org.bluezoo.gumdrop;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import org.bluezoo.gumdrop.testsupport.RecordingCompletionHandler;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.bluezoo.gumdrop.util.AsyncFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

/**
 * Tests {@link AsyncFile} on a file system without asynchronous channels,
 * with blocking I/O run on a real {@link StorageExecutor}. Lives in this
 * package because the executor's constructor is package-private.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AsyncFileStorageExecutorTest {

    private StorageExecutor storage;
    private Path root;

    @Before
    public void setUp() throws Exception {
        storage = new StorageExecutor(2, 8);
        root = MemoryFileSystem.create().getPath("/data");
        Files.createDirectories(root);
    }

    @After
    public void tearDown() {
        storage.shutdown();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int writeAt(AsyncFile f, String text, long position) throws Exception {
        RecordingCompletionHandler<Integer, String> h =
                new RecordingCompletionHandler<Integer, String>();
        f.write(ByteBuffer.wrap(bytes(text)), position, "att", h);
        assertTrue(h.await(5, TimeUnit.SECONDS));
        assertNull(h.getError());
        assertEquals("att", h.getAttachment());
        return h.getResult().intValue();
    }

    private static String readAt(AsyncFile f, int length, long position) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(length);
        RecordingCompletionHandler<Integer, ByteBuffer> h =
                new RecordingCompletionHandler<Integer, ByteBuffer>();
        f.read(buf, position, buf, h);
        assertTrue(h.await(5, TimeUnit.SECONDS));
        assertNull(h.getError());
        if (h.getResult().intValue() < 0) {
            return null;
        }
        return new String(buf.array(), 0, h.getResult().intValue(), StandardCharsets.UTF_8);
    }

    @Test
    public void testBlockingWorkRunsOnStorageThreadNotCaller() throws Exception {
        Path f = root.resolve("f");
        Files.write(f, bytes("hello"));
        AsyncFile file = AsyncFile.open(storage, f, StandardOpenOption.READ);
        try {
            ByteBuffer buf = ByteBuffer.allocate(8);
            RecordingCompletionHandler<Integer, ByteBuffer> h =
                    new RecordingCompletionHandler<Integer, ByteBuffer>();
            file.read(buf, 0, buf, h);
            assertTrue(h.await(5, TimeUnit.SECONDS));
            assertEquals(5, h.getResult().intValue());
            assertNotSame(Thread.currentThread(), h.getCompletedOn());
        } finally {
            file.close();
        }
    }

    @Test
    public void testWriteThenReadBack() throws Exception {
        Path f = root.resolve("f");
        AsyncFile file = AsyncFile.open(storage, f, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            assertEquals(3, writeAt(file, "abc", 0));
            assertEquals(3, writeAt(file, "def", 3));
            assertEquals("abcdef", readAt(file, 16, 0));
            assertNull(readAt(file, 4, 6));
        } finally {
            file.close();
        }
    }

    @Test
    public void testFailureIsDeliveredToHandler() throws Exception {
        Path f = root.resolve("f");
        Files.write(f, bytes("abc"));
        AsyncFile file = AsyncFile.open(storage, f, StandardOpenOption.READ);
        file.close();
        ByteBuffer buf = ByteBuffer.allocate(4);
        RecordingCompletionHandler<Integer, ByteBuffer> h =
                new RecordingCompletionHandler<Integer, ByteBuffer>();
        file.read(buf, 0, buf, h);
        assertTrue(h.await(5, TimeUnit.SECONDS));
        assertTrue(h.getError() instanceof ClosedChannelException);
        assertSame(buf, h.getAttachment());
    }

    @Test
    public void testChainedReadsFromHandlersComplete() throws Exception {
        final int length = 500;
        Path f = root.resolve("big");
        Files.write(f, new byte[length]);
        final AsyncFile file = AsyncFile.open(storage, f, StandardOpenOption.READ);
        try {
            final RecordingCompletionHandler<Long, Void> finished =
                    new RecordingCompletionHandler<Long, Void>();
            final ByteBuffer buf = ByteBuffer.allocate(1);
            final long[] position = { 0 };
            file.read(buf, 0, buf,
                    new java.nio.channels.CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer n, ByteBuffer attachment) {
                    if (n.intValue() < 0) {
                        finished.completed(Long.valueOf(position[0]), null);
                        return;
                    }
                    position[0] += n.intValue();
                    attachment.clear();
                    file.read(attachment, position[0], attachment, this);
                }

                @Override
                public void failed(Throwable error, ByteBuffer attachment) {
                    finished.failed(error, null);
                }
            });
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertNull(String.valueOf(finished.getError()), finished.getError());
            assertEquals(length, finished.getResult().longValue());
        } finally {
            file.close();
        }
    }

    /**
     * A saturated pool reports through the handler rather than blocking or
     * throwing at the call site.
     */
    @Test
    public void testSaturatedPoolFailsThroughHandler() throws Exception {
        StorageExecutor tiny = new StorageExecutor(1, 1);
        try {
            Path f = root.resolve("f");
            Files.write(f, bytes("abc"));
            AsyncFile file = AsyncFile.open(tiny, f, StandardOpenOption.READ);
            final CountDownLatch release = new CountDownLatch(1);
            final CountDownLatch started = new CountDownLatch(1);
            java.util.concurrent.Executor direct = new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };
            tiny.submit(direct, new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    started.countDown();
                    release.await();
                    return null;
                }
            }, new StorageExecutor.Callback<Void>() {
                @Override
                public void completed(Void result) {
                }

                @Override
                public void failed(Throwable error) {
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // One worker is busy; one queue slot takes the first read...
            ByteBuffer queued = ByteBuffer.allocate(4);
            RecordingCompletionHandler<Integer, ByteBuffer> first =
                    new RecordingCompletionHandler<Integer, ByteBuffer>();
            file.read(queued, 0, queued, first);
            // ...so the second finds the queue full.
            ByteBuffer rejected = ByteBuffer.allocate(4);
            RecordingCompletionHandler<Integer, ByteBuffer> second =
                    new RecordingCompletionHandler<Integer, ByteBuffer>();
            file.read(rejected, 0, rejected, second);
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertTrue(second.getError() instanceof RejectedExecutionException);
            assertFalse(first.isDone() && first.getError() != null);
            release.countDown();
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertEquals(3, first.getResult().intValue());
            file.close();
        } finally {
            tiny.shutdown();
        }
    }
}
