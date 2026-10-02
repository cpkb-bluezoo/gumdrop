/*
 * DoHClientTransportIntegrationTest.java
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

package org.bluezoo.gumdrop.http.doh;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.TimerHandle;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Integration tests for {@link DoHClientTransport}.
 * RFC 8484.
 *
 * <p>Integration test: schedules on the real shared timer thread: one test lets
 * a timer fire, the other cancels one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoHClientTransportIntegrationTest {

    @Test
    public void testScheduleTimer() throws Exception {
        DoHClientTransport transport = new DoHClientTransport();
        final CountDownLatch latch = new CountDownLatch(1);
        TimerHandle handle = transport.scheduleTimer(50, new Runnable() {
            @Override public void run() { latch.countDown(); }
        });
        assertNotNull(handle);
        assertFalse(handle.isCancelled());
        assertTrue("Timer should fire within 2 seconds",
                latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleTimerCancel() {
        DoHClientTransport transport = new DoHClientTransport();
        TimerHandle handle = transport.scheduleTimer(60_000, new Runnable() {
            @Override
            public void run() {
                fail("Cancelled timer should not fire");
            }
        });
        handle.cancel();
        assertTrue(handle.isCancelled());
    }
}
