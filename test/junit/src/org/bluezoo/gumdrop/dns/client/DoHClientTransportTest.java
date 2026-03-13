/*
 * DoHClientTransportTest.java
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

package org.bluezoo.gumdrop.dns.client;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.TimerHandle;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoHClientTransport}.
 * RFC 8484.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoHClientTransportTest {

    @Test
    public void testDefaultPath() {
        DoHClientTransport transport = new DoHClientTransport();
        assertEquals("/dns-query", transport.getPath());
    }

    @Test
    public void testSetPath() {
        DoHClientTransport transport = new DoHClientTransport();
        transport.setPath("/custom-dns");
        assertEquals("/custom-dns", transport.getPath());
    }

    @Test
    public void testContentTypeConstant() {
        assertEquals("application/dns-message",
                DoHClientTransport.DNS_MESSAGE_CONTENT_TYPE);
    }

    @Test
    public void testSendWithoutOpenReportsError() {
        DoHClientTransport transport = new DoHClientTransport();
        final boolean[] errorReported = {false};

        try {
            java.lang.reflect.Field handlerField =
                    DoHClientTransport.class.getDeclaredField("handler");
            handlerField.setAccessible(true);
            handlerField.set(transport, new DNSClientTransportHandler() {
                @Override
                public void onReceive(ByteBuffer response) {
                }

                @Override
                public void onError(Exception cause) {
                    errorReported[0] = true;
                }
            });
        } catch (ReflectiveOperationException e) {
            fail("Could not set handler: " + e);
        }

        transport.send(ByteBuffer.allocate(12));
        assertTrue("Should report error when not connected",
                errorReported[0]);
    }

    @Test
    public void testCloseWithoutOpen() {
        DoHClientTransport transport = new DoHClientTransport();
        transport.close();
    }

    @Test
    public void testScheduleTimer() throws Exception {
        DoHClientTransport transport = new DoHClientTransport();
        final CountDownLatch latch = new CountDownLatch(1);
        TimerHandle handle = transport.scheduleTimer(50,
                latch::countDown);
        assertNotNull(handle);
        assertFalse(handle.isCancelled());
        assertTrue("Timer should fire within 2 seconds",
                latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleTimerCancel() {
        DoHClientTransport transport = new DoHClientTransport();
        TimerHandle handle = transport.scheduleTimer(60_000, () -> {
            fail("Cancelled timer should not fire");
        });
        handle.cancel();
        assertTrue(handle.isCancelled());
    }
}
