/*
 * HTTPProtocolHandlerStreamMapTest.java
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

import static org.junit.Assert.*;

/**
 * Regression test for issue #279: on an HTTP/1.1 (or 1.0) connection there
 * is exactly one request/response in flight at a time, sequentially - so
 * once {@code getStream()} is asked for a brand-new stream ID while still
 * in one of the plain HTTP/1.x parsing states, any previously tracked
 * stream is guaranteed to have already fully completed. Before the fix,
 * that stale entry stayed in {@code streams} until the next periodic
 * {@code maybeCleanupClosedStreams()} sweep (gated 30 seconds apart), so
 * under sustained keep-alive throughput the map kept growing between
 * sweeps and had to repeatedly resize - the {@code ConcurrentHashMap}
 * resize/rehash churn a JFR profile caught under load.
 *
 * <p>This does not attempt to test concurrent-map-vs-plain-map performance
 * directly (not meaningfully unit-testable); it tests the actual growth
 * behaviour the issue is about: {@code streams} must stay at a single
 * entry across sequential HTTP/1.1 requests instead of accumulating one
 * per request. {@code getStream()} and {@code streams} are relaxed from
 * {@code private} to package-private for this (see {@link
 * HTTPProtocolHandler#streamCountForTesting()}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPProtocolHandlerStreamMapTest {

    @Test
    public void testCompletedHttp1StreamEvictedImmediatelyOnNextRequest() {
        HTTPListener listener = new HTTPListener();
        HTTPProtocolHandler connection = new HTTPProtocolHandler(listener);
        // Default state (REQUEST_LINE) and version (HTTP_1_0) are already
        // the plain, sequential HTTP/1.x case this fix targets.

        Stream first = connection.getStream(1);
        assertEquals(1, connection.streamCountForTesting());

        first.streamClose();

        Stream second = connection.getStream(3);
        assertEquals("a new stream ID on a sequential HTTP/1.x connection "
                + "should evict the previous, already-completed stream "
                + "immediately rather than letting streams accumulate "
                + "until the next periodic cleanup sweep",
                1, connection.streamCountForTesting());
        assertNotSame(first, second);
    }

    @Test
    public void testFirstStreamOnFreshConnectionIsNotAffectedByEviction() {
        HTTPListener listener = new HTTPListener();
        HTTPProtocolHandler connection = new HTTPProtocolHandler(listener);

        Stream first = connection.getStream(1);

        assertEquals(1, connection.streamCountForTesting());
        assertSame("looking up the same still-open stream ID again must "
                + "return the same instance, not evict and recreate it",
                first, connection.getStream(1));
    }
}
