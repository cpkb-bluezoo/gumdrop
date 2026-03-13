/*
 * DoQClientTransportTest.java
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

import java.lang.reflect.Field;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoQClientTransport} session resumption and 0-RTT.
 * RFC 9250 section 4.5.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQClientTransportTest {

    @Test
    public void testSessionTicketCacheExists() throws Exception {
        Field cacheField = DoQClientTransport.class
                .getDeclaredField("sessionTicketCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        assertNotNull("Session ticket cache must be initialized", cache);
        assertTrue("Session ticket cache should be a ConcurrentHashMap",
                cache instanceof java.util.concurrent.ConcurrentHashMap);
    }

    @Test
    public void testSessionTicketCacheIsStatic() throws Exception {
        Field cacheField = DoQClientTransport.class
                .getDeclaredField("sessionTicketCache");
        assertTrue("Session ticket cache should be static",
                java.lang.reflect.Modifier.isStatic(
                        cacheField.getModifiers()));
    }

    @Test
    public void testCloseOnNewTransport() {
        DoQClientTransport transport = new DoQClientTransport();
        transport.close();
    }

    @Test
    public void testSendWithoutOpenReportsError() {
        DoQClientTransport transport = new DoQClientTransport();
        final boolean[] errorReported = {false};
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(12);

        DNSClientTransportHandler handler = new DNSClientTransportHandler() {
            @Override
            public void onReceive(java.nio.ByteBuffer response) {
            }

            @Override
            public void onError(Exception cause) {
                errorReported[0] = true;
            }
        };

        try {
            Field handlerField = DoQClientTransport.class
                    .getDeclaredField("handler");
            handlerField.setAccessible(true);
            handlerField.set(transport, handler);
        } catch (ReflectiveOperationException e) {
            fail("Could not set handler: " + e);
        }

        transport.send(data);
        assertTrue("Should report error when not connected",
                errorReported[0]);
    }
}
