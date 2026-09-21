/*
 * AsyncFileIntegrationTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.testsupport.RecordingCompletionHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link AsyncFile} on the default file system, where it wraps a real
 * {@link java.nio.channels.AsynchronousFileChannel}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AsyncFileIntegrationTest {

    private Path dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("asyncfile");
    }

    @After
    public void tearDown() throws Exception {
        java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(dir);
        try {
            for (Path child : children) {
                Files.deleteIfExists(child);
            }
        } finally {
            children.close();
        }
        Files.deleteIfExists(dir);
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
    public void testWriteThenReadBackOnDisk() throws Exception {
        Path f = dir.resolve("f");
        AsyncFile file = AsyncFile.open(null, f, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            assertEquals(5, writeAt(file, "hello", 0));
            assertEquals(5, file.size());
            assertEquals("hello", readAt(file, 16, 0));
            assertNull(readAt(file, 4, 5));
        } finally {
            file.close();
        }
        assertEquals("hello", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
    }

    @Test
    public void testCompletionRunsOnJdkThreadNotCaller() throws Exception {
        Path f = dir.resolve("f");
        Files.write(f, bytes("abc"));
        AsyncFile file = AsyncFile.open(null, f, StandardOpenOption.READ);
        try {
            ByteBuffer buf = ByteBuffer.allocate(4);
            RecordingCompletionHandler<Integer, ByteBuffer> h =
                    new RecordingCompletionHandler<Integer, ByteBuffer>();
            file.read(buf, 0, buf, h);
            assertTrue(h.await(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), h.getCompletedOn());
        } finally {
            file.close();
        }
    }
}
