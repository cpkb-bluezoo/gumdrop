/*
 * RedisClientResultDispatchTest.java
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

import org.bluezoo.gumdrop.redis.codec.RespValue;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link RedisClientProtocolHandler} dispatches RESP replies to typed handlers.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientResultDispatchTest {

    private RedisClientProtocolHandler handler;
    private AtomicReference<RedisSession> sessionRef;

    @Before
    public void setUp() {
        sessionRef = new AtomicReference<RedisSession>();
        handler = new RedisClientProtocolHandler(new RedisClientProtocolHandlerTest.StubConnectionReady(sessionRef));
        handler.connected(new RedisClientProtocolHandlerTest.StubEndpoint());
        assertNotNull(sessionRef.get());
    }

    @Test
    public void testPingDispatchesSimpleString() {
        RecordingStringHandler stringHandler = new RecordingStringHandler();
        sessionRef.get().ping(stringHandler);
        handler.receive(ByteBuffer.wrap(bytes("+PONG\r\n")));
        assertEquals("PONG", stringHandler.result.get());
    }

    @Test
    public void testGetDispatchesBulkString() {
        RecordingBulkHandler bulkHandler = new RecordingBulkHandler();
        sessionRef.get().get("key", bulkHandler);
        handler.receive(ByteBuffer.wrap(bytes("$3\r\nfoo\r\n")));
        assertEquals("foo", new String(bulkHandler.result.get(), StandardCharsets.UTF_8));
    }

    @Test
    public void testGetNullBulkDispatchesNull() {
        RecordingBulkHandler bulkHandler = new RecordingBulkHandler();
        sessionRef.get().get("missing", bulkHandler);
        handler.receive(ByteBuffer.wrap(bytes("$-1\r\n")));
        assertTrue(bulkHandler.nullResult.get());
    }

    @Test
    public void testIncrDispatchesInteger() {
        RecordingIntHandler intHandler = new RecordingIntHandler();
        sessionRef.get().incr("counter", intHandler);
        handler.receive(ByteBuffer.wrap(bytes(":42\r\n")));
        assertEquals(42L, intHandler.result.get().longValue());
    }

    @Test
    public void testSetnxDispatchesBoolean() {
        RecordingBoolHandler boolHandler = new RecordingBoolHandler();
        sessionRef.get().setnx("key", "val", boolHandler);
        handler.receive(ByteBuffer.wrap(bytes(":1\r\n")));
        assertTrue(boolHandler.result.get());
    }

    @Test
    public void testScanDispatchesCursorAndElements() {
        RecordingScanHandler scanHandler = new RecordingScanHandler();
        sessionRef.get().scan("0", scanHandler);
        handler.receive(ByteBuffer.wrap(bytes("*2\r\n$1\r\n0\r\n*1\r\n$3\r\nkey\r\n")));
        assertEquals("0", scanHandler.cursor.get());
        assertNotNull(scanHandler.elements.get());
        assertEquals(1, scanHandler.elements.get().size());
    }

    @Test
    public void testErrorDispatchesToStringHandler() {
        RecordingStringHandler stringHandler = new RecordingStringHandler();
        sessionRef.get().ping(stringHandler);
        handler.receive(ByteBuffer.wrap(bytes("-ERR auth required\r\n")));
        assertEquals("ERR auth required", stringHandler.error.get());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingStringHandler implements StringResultHandler {
        final AtomicReference<String> result = new AtomicReference<String>();
        final AtomicReference<String> error = new AtomicReference<String>();

        @Override
        public void handleResult(String result, RedisSession session) {
            this.result.set(result);
        }

        @Override
        public void handleError(String error, RedisSession session) {
            this.error.set(error);
        }
    }

    private static final class RecordingBulkHandler implements BulkResultHandler {
        final AtomicReference<byte[]> result = new AtomicReference<byte[]>();
        final AtomicReference<Boolean> nullResult = new AtomicReference<Boolean>();

        @Override
        public void handleResult(byte[] value, RedisSession session) {
            result.set(value);
        }

        @Override
        public void handleNull(RedisSession session) {
            nullResult.set(Boolean.TRUE);
        }

        @Override
        public void handleError(String error, RedisSession session) {
        }
    }

    private static final class RecordingIntHandler implements IntegerResultHandler {
        final AtomicReference<Long> result = new AtomicReference<Long>();

        @Override
        public void handleResult(long value, RedisSession session) {
            result.set(Long.valueOf(value));
        }

        @Override
        public void handleError(String error, RedisSession session) {
        }
    }

    private static final class RecordingBoolHandler implements BooleanResultHandler {
        final AtomicReference<Boolean> result = new AtomicReference<Boolean>();

        @Override
        public void handleResult(boolean value, RedisSession session) {
            result.set(Boolean.valueOf(value));
        }

        @Override
        public void handleError(String error, RedisSession session) {
        }
    }

    private static final class RecordingScanHandler implements ScanResultHandler {
        final AtomicReference<String> cursor = new AtomicReference<String>();
        final AtomicReference<List<RespValue>> elements = new AtomicReference<List<RespValue>>();

        @Override
        public void handleResult(String cursor, List<RespValue> elements, RedisSession session) {
            this.cursor.set(cursor);
            this.elements.set(elements);
        }

        @Override
        public void handleError(String error, RedisSession session) {
        }
    }
}
