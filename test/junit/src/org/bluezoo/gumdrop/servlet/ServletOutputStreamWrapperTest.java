/*
 * ServletOutputStreamWrapperTest.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import jakarta.servlet.WriteListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link ServletOutputStreamWrapper} over a plain byte sink with no
 * owning response: writes, closing, and the {@link WriteListener} rules.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletOutputStreamWrapperTest {

    /** Records listener callbacks. */
    private static final class Recorder implements WriteListener {
        int possible;
        Throwable error;
        boolean failInPossible;
        boolean failInError;

        @Override
        public void onWritePossible() throws IOException {
            possible++;
            if (failInPossible) {
                throw new IOException("write");
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

    private ByteArrayOutputStream sink;
    private ServletOutputStreamWrapper out;

    @Before
    public void setUp() {
        sink = new ByteArrayOutputStream();
        out = new ServletOutputStreamWrapper(null, sink);
    }

    @Test
    public void testWritesReachTheSink() throws IOException {
        out.write(65);
        out.write(new byte[] {66, 67});
        out.write(new byte[] {0, 68, 69, 0}, 1, 2);
        ByteBuffer heap = ByteBuffer.wrap(new byte[] {70, 71});
        out.write(heap);
        assertEquals(2, heap.position());
        ByteBuffer direct = ByteBuffer.allocateDirect(2);
        direct.put((byte) 72);
        direct.put((byte) 73);
        direct.flip();
        out.write(direct);
        out.write(ByteBuffer.allocate(0));
        out.flush();
        assertEquals("ABCDEFGHI", sink.toString("ISO-8859-1"));
        assertFalse(out.hasWriteListener());
    }

    @Test
    public void testClosedStreamRejectsWrites() throws IOException {
        out.close();
        out.close();
        assertFalse(out.isReady());
        try {
            out.write(1);
            fail("closed");
        } catch (IOException expected) {
            assertEquals(0, sink.size());
        }
        try {
            out.flush();
            fail("closed");
        } catch (IOException expected) {
            assertEquals(0, sink.size());
        }
    }

    @Test
    public void testListenerIsNotifiedOnceWhenReady() throws IOException {
        Recorder r = new Recorder();
        out.setWriteListener(r);
        assertTrue(out.hasWriteListener());
        assertEquals(1, r.possible);
        assertTrue(out.isReady());
        out.write(1);
        out.flush();
        assertEquals(1, r.possible);
        out.notifyWritePossible();
        assertEquals(2, r.possible);
    }

    @Test
    public void testListenerRegistrationRules() {
        try {
            out.setWriteListener(null);
            fail("null listener");
        } catch (NullPointerException expected) {
            assertFalse(out.hasWriteListener());
        }
        out.setWriteListener(new Recorder());
        try {
            out.setWriteListener(new Recorder());
            fail("second listener");
        } catch (IllegalStateException expected) {
            assertTrue(out.hasWriteListener());
        }
    }

    @Test
    public void testListenerFailuresGoToOnError() {
        Recorder r = new Recorder();
        r.failInPossible = true;
        out.setWriteListener(r);
        assertEquals("write", r.error.getMessage());
        r.failInError = true;
        out.notifyWritePossible();
        assertEquals(2, r.possible);
    }

    @Test
    public void testNotifyWithoutListenerIsHarmless() {
        out.notifyWritePossible();
        assertFalse(out.hasWriteListener());
    }
}
