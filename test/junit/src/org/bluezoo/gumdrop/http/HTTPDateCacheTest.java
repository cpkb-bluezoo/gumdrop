/*
 * HTTPDateCacheTest.java
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

package org.bluezoo.gumdrop.http;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9110 section 6.6.1 cached Date header tests.
 */
public class HTTPDateCacheTest {

    private static final String IMF_FIXDATE_PATTERN =
        "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4} \\d{2}:\\d{2}:\\d{2} GMT$";

    @Test
    public void testCachedDateIsIMFFixdate() {
        String date = HTTPDateCache.get();
        assertNotNull(date);
        assertTrue("Expected IMF-fixdate, got: " + date,
                   date.matches(IMF_FIXDATE_PATTERN));
    }

    @Test
    public void testCachedDateRefreshesPerSecond() throws InterruptedException {
        String before = HTTPDateCache.get();
        // Poll rather than sleeping the worst case: usually resolves well
        // under a second, only ever as slow as the fixed sleep it replaces
        // if the tick lands right after this loop starts.
        String after = before;
        long deadline = System.currentTimeMillis() + 2500;
        while (after.equals(before) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            after = HTTPDateCache.get();
        }
        assertTrue(after.matches(IMF_FIXDATE_PATTERN));
        assertTrue("Cached date did not refresh: " + before + " == " + after,
                   !after.equals(before));
    }

    /**
     * getLineBytes() is the fast-path counterpart to get(): the complete
     * "Date: <value>\r\n" response header line, pre-encoded as ASCII bytes
     * so HTTPProtocolHandler can bulk-copy it into the output buffer
     * instead of encoding get()'s characters one at a time on every
     * response. The two must always describe the same cached instant.
     */
    @Test
    public void testLineBytesMatchCachedDateValue() {
        String date = HTTPDateCache.get();
        byte[] line = HTTPDateCache.getLineBytes();
        assertNotNull(line);
        String expected = "Date: " + date + "\r\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), line);
    }
}
