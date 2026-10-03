/*
 * RequestInputStreamTest.java
 * Copyright (C) 2025 Chris Burdess
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


package org.bluezoo.gumdrop.servlet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import jakarta.servlet.ReadListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link RequestInputStream} over a {@link RequestBodyStream} with no
 * owning request: blocking and listener-driven (non-blocking) reads, skip,
 * mark, close and the {@link ReadListener} notification rules.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RequestInputStreamTest {

    /** Records listener callbacks. */
    private static final class Recorder implements ReadListener {
        int available;
        int allRead;
        Throwable error;
        boolean failInAvailable;
        boolean failInAllRead;
        boolean failInError;

        @Override
        public void onDataAvailable() throws IOException {
            available++;
            if (failInAvailable) {
                throw new IOException("avail");
            }
        }

        @Override
        public void onAllDataRead() throws IOException {
            allRead++;
            if (failInAllRead) {
                throw new IOException("all");
            }
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            if (failInError) {
                throw new IllegalStateException("onError");
            }
        }
    }

    private RequestBodyStream body;
    private RequestInputStream in;

    @Before
    public void setUp() {
        body = new RequestBodyStream();
        in = new RequestInputStream(null, body);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Test
    public void testBlockingReads() throws IOException {
        body.offer(bytes("abcdefgh"));
        body.finish();
        assertEquals('a', in.read());
        byte[] two = new byte[2];
        assertEquals(2, in.read(two));
        assertEquals('b', two[0]);
        assertEquals(5, in.available());
        assertEquals(4, in.skip(4));
        assertEquals('h', in.read());
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(two, 0, 2));
        assertTrue(in.isFinished());
        assertFalse(in.isReady());
    }

    @Test
    public void testReadIntoByteBuffers() throws IOException {
        body.offer(bytes("0123456789"));
        body.finish();
        ByteBuffer empty = ByteBuffer.allocate(0);
        assertEquals(0, in.read(empty));
        ByteBuffer heap = ByteBuffer.allocate(4);
        assertEquals(4, in.read(heap));
        assertEquals(4, heap.position());
        ByteBuffer direct = ByteBuffer.allocateDirect(3);
        assertEquals(3, in.read(direct));
        assertEquals(3, direct.position());
        ByteBuffer rest = ByteBuffer.allocateDirect(10);
        assertEquals(3, in.read(rest));
        ByteBuffer after = ByteBuffer.allocateDirect(10);
        assertEquals(-1, in.read(after));
        ByteBuffer afterHeap = ByteBuffer.allocate(10);
        assertEquals(-1, in.read(afterHeap));
        assertEquals(0, afterHeap.position());
    }

    @Test
    public void testMarkIsNotSupported() throws IOException {
        assertFalse(in.markSupported());
        in.mark(10);
        try {
            in.reset();
            fail("reset must fail without mark support");
        } catch (IOException expected) {
            assertFalse(in.markSupported());
        }
    }

    @Test
    public void testCloseMakesStreamFinished() throws IOException {
        body.offer(bytes("xyz"));
        assertTrue(in.isReady());
        assertFalse(in.isFinished());
        in.close();
        assertTrue(in.isFinished());
        assertFalse(in.isReady());
        assertEquals(-1, in.read());
    }

    @Test
    public void testListenerLifecycleAndNonBlockingReads() throws IOException {
        Recorder r = new Recorder();
        in.setReadListener(r);
        assertTrue(in.hasReadListener());
        assertEquals(0, r.available);
        body.offer(bytes("ab"));
        in.dispatchDataAvailable();
        assertEquals(1, r.available);
        assertEquals('a', in.read());
        byte[] one = new byte[1];
        assertEquals(1, in.read(one));
        assertEquals('b', one[0]);
        try {
            in.read();
            fail("not ready");
        } catch (IllegalStateException expected) {
            assertFalse(in.isReady());
        }
        try {
            in.read(one, 0, 1);
            fail("not ready");
        } catch (IllegalStateException expected) {
            assertEquals(0, in.available());
        }
        assertEquals(0, in.read(one, 0, 0));
        body.finish();
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(one, 0, 1));
        assertEquals(1, r.allRead);
        in.notifyAllDataRead();
        assertEquals(1, r.allRead);
    }

    @Test
    public void testListenerSkip() throws IOException {
        Recorder r = new Recorder();
        in.setReadListener(r);
        assertEquals(0, in.skip(0));
        assertEquals(0, in.skip(-3));
        try {
            in.skip(2);
            fail("not ready");
        } catch (IllegalStateException expected) {
            assertEquals(0, in.available());
        }
        body.offer(bytes("abcdef"));
        assertEquals(6, in.skip(6));
        assertEquals(0, in.available());
    }

    @Test
    public void testListenerSkipThroughPartialData() throws IOException {
        Recorder r = new Recorder();
        in.setReadListener(r);
        body.offer(bytes("abc"));
        try {
            in.skip(5);
            fail("data ran out before the skip completed");
        } catch (IllegalStateException expected) {
            assertEquals(0, in.available());
        }
        body.finish();
        assertEquals(0, in.skip(1));
    }

    @Test
    public void testListenerRegistrationRules() {
        try {
            in.setReadListener(null);
            fail("null listener");
        } catch (NullPointerException expected) {
            assertFalse(in.hasReadListener());
        }
        Recorder r = new Recorder();
        in.setReadListener(r);
        try {
            in.setReadListener(new Recorder());
            fail("second listener");
        } catch (IllegalStateException expected) {
            assertSame(r, in.readListener);
        }
    }

    @Test
    public void testListenerSeesDataAndEndAtRegistration() {
        body.offer(bytes("data"));
        body.finish();
        Recorder r = new Recorder();
        in.setReadListener(r);
        assertEquals(1, r.available);
        assertEquals(0, r.allRead);
    }

    @Test
    public void testListenerSeesEmptyFinishedBodyAtRegistration() {
        body.finish();
        Recorder r = new Recorder();
        in.setReadListener(r);
        assertEquals(0, r.available);
        assertEquals(1, r.allRead);
    }

    @Test
    public void testListenerFailuresAreReportedToOnError() {
        Recorder r = new Recorder();
        r.failInAvailable = true;
        r.failInAllRead = true;
        in.setReadListener(r);
        body.offer(bytes("q"));
        in.dispatchDataAvailable();
        assertEquals("avail", r.error.getMessage());
        in.notifyAllDataRead();
        assertEquals("all", r.error.getMessage());
        r.failInError = true;
        in.notifyError(new IOException("again"));
        assertEquals("again", r.error.getMessage());
    }

    @Test
    public void testNotificationsIgnoredWithoutListener() {
        in.dispatchDataAvailable();
        in.notifyDataAvailable();
        in.notifyAllDataRead();
        in.notifyError(new IOException("x"));
        assertFalse(in.hasReadListener());
    }
}
