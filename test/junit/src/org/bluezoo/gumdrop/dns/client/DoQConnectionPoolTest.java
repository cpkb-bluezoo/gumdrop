/*
 * DoQConnectionPoolTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DoQConnectionPool}.
 * RFC 9250 section 5.5.1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoQConnectionPoolTest {

    @After
    public void tearDown() {
        DoQConnectionPool.closeAll();
        DoQConnectionPool.setMaxIdleTimeMs(30_000);
        DoQConnectionPool.setTransportSource(null);
    }

    /** Transport mock that records calls instead of opening a QUIC connection. */
    private static final class MockTransport extends DoQClientTransport {
        int opens;
        int sends;
        int closes;
        int timers;
        boolean failOpen;

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DnsClientTransportHandler handler) throws IOException {
            opens++;
            if (failOpen) {
                throw new IOException("quic unavailable");
            }
        }

        @Override
        public void send(ByteBuffer data) {
            sends++;
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            timers++;
            return null;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class Source implements DoQConnectionPool.TransportSource {
        final List<MockTransport> created = new ArrayList<MockTransport>();
        boolean failOpen;

        @Override
        public DoQClientTransport create() {
            MockTransport t = new MockTransport();
            t.failOpen = failOpen;
            created.add(t);
            return t;
        }
    }

    private static final class NoopHandler implements DnsClientTransportHandler {
        @Override
        public void onReceive(ByteBuffer data) {
        }

        @Override
        public void onError(Exception cause) {
        }
    }

    @Test
    public void testOpenReusesLiveConnectionAndDelegates() throws Exception {
        Source source = new Source();
        DoQConnectionPool.setTransportSource(source);
        InetAddress host = InetAddress.getByName("192.0.2.9");
        DoQConnectionPool first = new DoQConnectionPool();
        first.open(host, 853, null, new NoopHandler());
        assertEquals(1, DoQConnectionPool.poolSize());
        first.send(ByteBuffer.allocate(4));
        assertNull(first.scheduleTimer(10, new Runnable() {
            @Override public void run() { }
        }));
        DoQConnectionPool second = new DoQConnectionPool();
        second.open(host, 853, null, new NoopHandler());
        assertEquals("a live connection is shared", 1, source.created.size());
        assertEquals("the shared connection is opened once", 1, source.created.get(0).opens);
        second.send(ByteBuffer.allocate(4));
        assertEquals(2, source.created.get(0).sends);
        assertEquals(1, source.created.get(0).timers);
        second.close();
        assertEquals("closing a lease keeps the connection", 1, DoQConnectionPool.poolSize());
        assertEquals(0, source.created.get(0).closes);
        // a different port is a different connection
        DoQConnectionPool other = new DoQConnectionPool();
        other.open(host, 8853, null, new NoopHandler());
        assertEquals(2, DoQConnectionPool.poolSize());
    }

    @Test
    public void testStaleConnectionIsReplacedAndEvicted() throws Exception {
        Source source = new Source();
        DoQConnectionPool.setTransportSource(source);
        InetAddress host = InetAddress.getByName("192.0.2.10");
        DoQConnectionPool lease = new DoQConnectionPool();
        lease.open(host, 853, null, new NoopHandler());
        DoQConnectionPool.setMaxIdleTimeMs(-1);
        DoQConnectionPool again = new DoQConnectionPool();
        again.open(host, 853, null, new NoopHandler());
        assertEquals("an unusable entry is replaced", 2, source.created.size());
        assertEquals(1, source.created.get(0).closes);
        again.close();
        assertEquals("idle entries are evicted on close", 0, DoQConnectionPool.poolSize());
        assertEquals(1, source.created.get(1).closes);
    }

    @Test
    public void testCloseAllClosesPooledTransports() throws Exception {
        Source source = new Source();
        DoQConnectionPool.setTransportSource(source);
        DoQConnectionPool lease = new DoQConnectionPool();
        lease.open(InetAddress.getByName("192.0.2.11"), 853, null, new NoopHandler());
        DoQConnectionPool.closeAll();
        assertEquals(0, DoQConnectionPool.poolSize());
        assertEquals(1, source.created.get(0).closes);
    }

    @Test
    public void testFailedOpenIsRetriedOnNextOpen() throws Exception {
        Source source = new Source();
        source.failOpen = true;
        DoQConnectionPool.setTransportSource(source);
        InetAddress host = InetAddress.getByName("192.0.2.12");
        DoQConnectionPool lease = new DoQConnectionPool();
        try {
            lease.open(host, 853, null, new NoopHandler());
            fail("open failure must propagate");
        } catch (IOException expected) {
            assertEquals("quic unavailable", expected.getMessage());
        }
        // the entry never became usable, so the next open builds a new one
        source.failOpen = false;
        DoQConnectionPool retry = new DoQConnectionPool();
        retry.open(host, 853, null, new NoopHandler());
        assertEquals(2, source.created.size());
        assertEquals(1, source.created.get(1).opens);
    }

    @Test
    public void testDefaultMaxIdleTime() {
        assertEquals(30_000, DoQConnectionPool.getMaxIdleTimeMs());
    }

    @Test
    public void testSetMaxIdleTime() {
        DoQConnectionPool.setMaxIdleTimeMs(60_000);
        assertEquals(60_000, DoQConnectionPool.getMaxIdleTimeMs());
    }

    @Test
    public void testPoolSizeInitiallyZero() {
        assertEquals(0, DoQConnectionPool.poolSize());
    }

    @Test
    public void testCloseAllClearsPool() {
        DoQConnectionPool.closeAll();
        assertEquals(0, DoQConnectionPool.poolSize());
    }

    @Test
    public void testCloseWithoutOpenDoesNotThrow() {
        DoQConnectionPool pool = new DoQConnectionPool();
        pool.close();
    }

    @Test(expected = IllegalStateException.class)
    public void testSendWithoutOpenThrows() {
        DoQConnectionPool pool = new DoQConnectionPool();
        pool.send(java.nio.ByteBuffer.allocate(10));
    }

    @Test(expected = IllegalStateException.class)
    public void testScheduleTimerWithoutOpenThrows() {
        DoQConnectionPool pool = new DoQConnectionPool();
        pool.scheduleTimer(1000, new Runnable() {
            @Override public void run() { }
        });
    }

    @Test
    public void testEvictStaleRemovesExpired() {
        DoQConnectionPool.setMaxIdleTimeMs(0);
        DoQConnectionPool.evictStale();
        assertEquals(0, DoQConnectionPool.poolSize());
    }
}
