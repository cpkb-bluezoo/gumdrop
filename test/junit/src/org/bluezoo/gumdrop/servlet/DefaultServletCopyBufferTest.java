/*
 * DefaultServletCopyBufferTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #314: {@link DefaultServlet} sized its
 * static-resource copy buffer with {@code in.available()}, which for a
 * {@code FileInputStream}-backed resource often returns the entire remaining
 * file size and allocates a per-request buffer proportional to asset size.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultServletCopyBufferTest {

    @Test
    public void testCopyBufferSizeIsFixed() {
        assertEquals(DefaultServlet.COPY_BUFFER_SIZE,
                DefaultServlet.newCopyBuffer().length);
    }

    @Test
    public void testCopyBufferDoesNotFollowAvailable() throws Exception {
        final int payloadSize = 512 * 1024;
        byte[] payload = new byte[payloadSize];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xff);
        }

        final int[] largestRead = { 0 };
        InputStream in = new FilterInputStream(new ByteArrayInputStream(payload)) {
            @Override
            public int available() {
                return payloadSize;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    largestRead[0] = Math.max(largestRead[0], n);
                }
                return n;
            }
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream(payloadSize);
        byte[] buf = DefaultServlet.newCopyBuffer();
        for (int len = in.read(buf); len != -1; len = in.read(buf)) {
            out.write(buf, 0, len);
        }

        assertArrayEquals(payload, out.toByteArray());
        assertTrue("copy buffer must stay bounded even when available() reports "
                        + "the full file size (largest read was " + largestRead[0] + " bytes)",
                largestRead[0] <= DefaultServlet.COPY_BUFFER_SIZE);
    }
}
