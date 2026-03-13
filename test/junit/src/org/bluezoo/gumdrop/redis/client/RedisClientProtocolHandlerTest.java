/*
 * RedisClientProtocolHandlerTest.java
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

package org.bluezoo.gumdrop.redis.client;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.codec.RESPValue;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for new features in {@link RedisClientProtocolHandler}:
 * HELLO, CLIENT commands, SCAN, blocking commands, streams, RESET.
 */
public class RedisClientProtocolHandlerTest {

    private RedisClientProtocolHandler handler;
    private StubEndpoint endpoint;
    private AtomicReference<RedisSession> sessionRef;

    @Before
    public void setUp() {
        sessionRef = new AtomicReference<>();
        handler = new RedisClientProtocolHandler(new StubConnectionReady(sessionRef));
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
        assertNotNull(sessionRef.get());
    }

    private void simulateResponse(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    private String lastSentCommand() {
        if (endpoint.sentBuffers.isEmpty()) {
            return null;
        }
        ByteBuffer buf = endpoint.sentBuffers.get(endpoint.sentBuffers.size() - 1);
        buf.rewind();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELLO command (#75)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testHelloSendsCommand() {
        RedisSession session = sessionRef.get();
        session.hello(3, new StubArrayHandler());

        String sent = lastSentCommand();
        assertNotNull(sent);
        assertTrue(sent.contains("HELLO"));
        assertTrue(sent.contains("3"));
    }

    @Test
    public void testHelloWithAuth() {
        RedisSession session = sessionRef.get();
        session.hello(3, "user", "pass", new StubArrayHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("HELLO"));
        assertTrue(sent.contains("AUTH"));
        assertTrue(sent.contains("user"));
        assertTrue(sent.contains("pass"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT commands (#76)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testClientSetName() {
        RedisSession session = sessionRef.get();
        session.clientSetName("myconn", new StubStringHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("CLIENT"));
        assertTrue(sent.contains("SETNAME"));
        assertTrue(sent.contains("myconn"));
    }

    @Test
    public void testClientGetName() {
        RedisSession session = sessionRef.get();
        session.clientGetName(new StubBulkHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("CLIENT"));
        assertTrue(sent.contains("GETNAME"));
    }

    @Test
    public void testClientId() {
        RedisSession session = sessionRef.get();
        session.clientId(new StubIntHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("CLIENT"));
        assertTrue(sent.contains("ID"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCAN commands (#77)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testScan() {
        RedisSession session = sessionRef.get();
        session.scan("0", new StubScanHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("SCAN"));
        assertTrue(sent.contains("0"));
    }

    @Test
    public void testScanWithMatchAndCount() {
        RedisSession session = sessionRef.get();
        session.scan("0", "user:*", 100, new StubScanHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("SCAN"));
        assertTrue(sent.contains("MATCH"));
        assertTrue(sent.contains("user:*"));
        assertTrue(sent.contains("COUNT"));
        assertTrue(sent.contains("100"));
    }

    @Test
    public void testScanResponseDispatched() {
        final AtomicReference<String> cursorRef = new AtomicReference<>();
        final AtomicReference<List<RESPValue>> elementsRef = new AtomicReference<>();

        RedisSession session = sessionRef.get();
        session.scan("0", new ScanResultHandler() {
            @Override
            public void handleResult(String cursor, List<RESPValue> elements, RedisSession s) {
                cursorRef.set(cursor);
                elementsRef.set(elements);
            }
            @Override
            public void handleError(String error, RedisSession s) {
                fail("Unexpected error: " + error);
            }
        });

        // Simulate SCAN response: *2\r\n$3\r\n123\r\n*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
        simulateResponse("*2\r\n$3\r\n123\r\n*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");

        assertEquals("123", cursorRef.get());
        assertNotNull(elementsRef.get());
        assertEquals(2, elementsRef.get().size());
        assertEquals("foo", elementsRef.get().get(0).asString());
        assertEquals("bar", elementsRef.get().get(1).asString());
    }

    @Test
    public void testHscan() {
        RedisSession session = sessionRef.get();
        session.hscan("myhash", "0", new StubScanHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("HSCAN"));
        assertTrue(sent.contains("myhash"));
    }

    @Test
    public void testSscan() {
        RedisSession session = sessionRef.get();
        session.sscan("myset", "0", new StubScanHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("SSCAN"));
        assertTrue(sent.contains("myset"));
    }

    @Test
    public void testZscan() {
        RedisSession session = sessionRef.get();
        session.zscan("myzset", "0", new StubScanHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("ZSCAN"));
        assertTrue(sent.contains("myzset"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Blocking commands (#78)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testBlpop() {
        RedisSession session = sessionRef.get();
        session.blpop(5.0, new StubArrayHandler(), "queue1", "queue2");

        String sent = lastSentCommand();
        assertTrue(sent.contains("BLPOP"));
        assertTrue(sent.contains("queue1"));
        assertTrue(sent.contains("queue2"));
        assertTrue(sent.contains("5.0"));
    }

    @Test
    public void testBrpop() {
        RedisSession session = sessionRef.get();
        session.brpop(10.0, new StubArrayHandler(), "queue");

        String sent = lastSentCommand();
        assertTrue(sent.contains("BRPOP"));
        assertTrue(sent.contains("queue"));
        assertTrue(sent.contains("10.0"));
    }

    @Test
    public void testBlmove() {
        RedisSession session = sessionRef.get();
        session.blmove("src", "dest", "LEFT", "RIGHT", 3.0, new StubBulkHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("BLMOVE"));
        assertTrue(sent.contains("src"));
        assertTrue(sent.contains("dest"));
        assertTrue(sent.contains("LEFT"));
        assertTrue(sent.contains("RIGHT"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stream commands (#79)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testXadd() {
        RedisSession session = sessionRef.get();
        session.xadd("mystream", "*", new StubBulkHandler(), "field1", "value1");

        String sent = lastSentCommand();
        assertTrue(sent.contains("XADD"));
        assertTrue(sent.contains("mystream"));
        assertTrue(sent.contains("field1"));
        assertTrue(sent.contains("value1"));
    }

    @Test
    public void testXlen() {
        RedisSession session = sessionRef.get();
        session.xlen("mystream", new StubIntHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("XLEN"));
        assertTrue(sent.contains("mystream"));
    }

    @Test
    public void testXrange() {
        RedisSession session = sessionRef.get();
        session.xrange("mystream", "-", "+", new StubArrayHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("XRANGE"));
        assertTrue(sent.contains("mystream"));
    }

    @Test
    public void testXrangeWithCount() {
        RedisSession session = sessionRef.get();
        session.xrange("mystream", "-", "+", 10, new StubArrayHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("XRANGE"));
        assertTrue(sent.contains("COUNT"));
        assertTrue(sent.contains("10"));
    }

    @Test
    public void testXread() {
        RedisSession session = sessionRef.get();
        session.xread(5, 1000, new StubArrayHandler(), "stream1", "0");

        String sent = lastSentCommand();
        assertTrue(sent.contains("XREAD"));
        assertTrue(sent.contains("COUNT"));
        assertTrue(sent.contains("BLOCK"));
        assertTrue(sent.contains("STREAMS"));
        assertTrue(sent.contains("stream1"));
    }

    @Test
    public void testXreadWithoutBlock() {
        RedisSession session = sessionRef.get();
        session.xread(5, -1, new StubArrayHandler(), "stream1", "0");

        String sent = lastSentCommand();
        assertTrue(sent.contains("XREAD"));
        assertFalse(sent.contains("BLOCK"));
    }

    @Test
    public void testXtrim() {
        RedisSession session = sessionRef.get();
        session.xtrim("mystream", 1000, new StubIntHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("XTRIM"));
        assertTrue(sent.contains("MAXLEN"));
        assertTrue(sent.contains("1000"));
    }

    @Test
    public void testXack() {
        RedisSession session = sessionRef.get();
        session.xack("mystream", "mygroup", new StubIntHandler(), "1-0", "2-0");

        String sent = lastSentCommand();
        assertTrue(sent.contains("XACK"));
        assertTrue(sent.contains("mystream"));
        assertTrue(sent.contains("mygroup"));
        assertTrue(sent.contains("1-0"));
    }

    @Test
    public void testXgroupCreate() {
        RedisSession session = sessionRef.get();
        session.xgroupCreate("mystream", "mygroup", "$", new StubStringHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("XGROUP"));
        assertTrue(sent.contains("CREATE"));
        assertTrue(sent.contains("MKSTREAM"));
    }

    @Test
    public void testXgroupDestroy() {
        RedisSession session = sessionRef.get();
        session.xgroupDestroy("mystream", "mygroup", new StubIntHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("XGROUP"));
        assertTrue(sent.contains("DESTROY"));
    }

    @Test
    public void testXpending() {
        RedisSession session = sessionRef.get();
        session.xpending("mystream", "mygroup", new StubArrayHandler());

        String sent = lastSentCommand();
        assertTrue(sent.contains("XPENDING"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESET command (#80)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testReset() {
        RedisSession session = sessionRef.get();
        final AtomicReference<String> resultRef = new AtomicReference<>();

        session.reset(new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                resultRef.set(result);
            }
            @Override
            public void handleError(String error, RedisSession s) {
                fail("Unexpected error: " + error);
            }
        });

        String sent = lastSentCommand();
        assertTrue(sent.contains("RESET"));

        simulateResponse("+RESET\r\n");
        assertEquals("RESET", resultRef.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESP3 dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testResp3MapDispatchedToArrayHandler() {
        final AtomicReference<List<RESPValue>> resultRef = new AtomicReference<>();

        RedisSession session = sessionRef.get();
        session.command(new ArrayResultHandler() {
            @Override
            public void handleResult(List<RESPValue> array, RedisSession s) {
                resultRef.set(array);
            }
            @Override
            public void handleNull(RedisSession s) {}
            @Override
            public void handleError(String error, RedisSession s) {}
        }, "CONFIG", "GET", "maxmemory");

        // Simulate RESP3 map response: %1\r\n+maxmemory\r\n+0\r\n
        simulateResponse("%1\r\n+maxmemory\r\n+0\r\n");

        assertNotNull(resultRef.get());
        assertEquals(2, resultRef.get().size());
        assertEquals("maxmemory", resultRef.get().get(0).asString());
        assertEquals("0", resultRef.get().get(1).asString());
    }

    @Test
    public void testResp3BlobErrorDispatchedAsError() {
        final AtomicReference<String> errorRef = new AtomicReference<>();

        RedisSession session = sessionRef.get();
        session.get("somekey", new BulkResultHandler() {
            @Override
            public void handleResult(byte[] value, RedisSession s) {
                fail("Should not get a value");
            }
            @Override
            public void handleNull(RedisSession s) {
                fail("Should not get null");
            }
            @Override
            public void handleError(String error, RedisSession s) {
                errorRef.set(error);
            }
        });

        simulateResponse("!11\r\nERR unknown\r\n");
        assertEquals("ERR unknown", errorRef.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stub implementations
    // ─────────────────────────────────────────────────────────────────────────

    static class StubConnectionReady implements RedisConnectionReady {
        private final AtomicReference<RedisSession> ref;
        StubConnectionReady(AtomicReference<RedisSession> ref) { this.ref = ref; }
        @Override public void handleReady(RedisSession s) { ref.set(s); }
        @Override public void onConnected(Endpoint ep) {}
        @Override public void onDisconnected() {}
        @Override public void onSecurityEstablished(SecurityInfo info) {}
        @Override public void onError(Exception e) {}
    }

    static class StubEndpoint implements Endpoint {
        List<ByteBuffer> sentBuffers = new ArrayList<>();
        boolean open = true;

        @Override public void send(ByteBuffer buf) {
            byte[] copy = new byte[buf.remaining()];
            buf.get(copy);
            sentBuffers.add(ByteBuffer.wrap(copy));
        }
        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() throws IOException {}
        @Override public void pauseRead() {}
        @Override public void resumeRead() {}
        @Override public void onWriteReady(Runnable callback) {}
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void setTrace(org.bluezoo.gumdrop.telemetry.Trace trace) {}
        @Override public org.bluezoo.gumdrop.telemetry.Trace getTrace() { return null; }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public org.bluezoo.gumdrop.telemetry.TelemetryConfig getTelemetryConfig() { return null; }
        @Override public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }
        @Override public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
    }

    static class StubStringHandler implements StringResultHandler {
        @Override public void handleResult(String result, RedisSession s) {}
        @Override public void handleError(String error, RedisSession s) {}
    }

    static class StubBulkHandler implements BulkResultHandler {
        @Override public void handleResult(byte[] value, RedisSession s) {}
        @Override public void handleNull(RedisSession s) {}
        @Override public void handleError(String error, RedisSession s) {}
    }

    static class StubIntHandler implements IntegerResultHandler {
        @Override public void handleResult(long value, RedisSession s) {}
        @Override public void handleError(String error, RedisSession s) {}
    }

    static class StubArrayHandler implements ArrayResultHandler {
        @Override public void handleResult(List<RESPValue> array, RedisSession s) {}
        @Override public void handleNull(RedisSession s) {}
        @Override public void handleError(String error, RedisSession s) {}
    }

    static class StubScanHandler implements ScanResultHandler {
        @Override public void handleResult(String cursor, List<RESPValue> elements, RedisSession s) {}
        @Override public void handleError(String error, RedisSession s) {}
    }

}
