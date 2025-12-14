/*
 * RESPEncoderTest.java
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

package org.bluezoo.gumdrop.redis.codec;

import org.junit.Before;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link RESPEncoder}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPEncoderTest {

    private RESPEncoder encoder;

    @Before
    public void setUp() {
        encoder = new RESPEncoder();
    }

    private String bufferToString(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command encoding (no arguments)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testEncodeSimpleCommand() {
        ByteBuffer result = encoder.encodeCommand("PING");
        assertEquals("*1\r\n$4\r\nPING\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeQuit() {
        ByteBuffer result = encoder.encodeCommand("QUIT");
        assertEquals("*1\r\n$4\r\nQUIT\r\n", bufferToString(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command encoding (string arguments)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testEncodeCommandWithStringArgs() {
        ByteBuffer result = encoder.encodeCommand("SET", new String[] { "mykey", "myvalue" });
        assertEquals("*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$7\r\nmyvalue\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeGet() {
        ByteBuffer result = encoder.encodeCommand("GET", new String[] { "key" });
        assertEquals("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeCommandWithEmptyStringArg() {
        ByteBuffer result = encoder.encodeCommand("SET", new String[] { "key", "" });
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$0\r\n\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeCommandWithSpaces() {
        ByteBuffer result = encoder.encodeCommand("SET", new String[] { "key", "hello world" });
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$11\r\nhello world\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeMget() {
        ByteBuffer result = encoder.encodeCommand("MGET", new String[] { "key1", "key2", "key3" });
        assertEquals("*4\r\n$4\r\nMGET\r\n$4\r\nkey1\r\n$4\r\nkey2\r\n$4\r\nkey3\r\n", bufferToString(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command encoding (byte array arguments)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testEncodeCommandWithByteArgs() {
        byte[] value = new byte[] { 0x00, 0x01, 0x02 };
        ByteBuffer result = encoder.encodeCommand("SET", new byte[][] { "key".getBytes(StandardCharsets.UTF_8), value });

        // Check header and key
        String expected = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$3\r\n";
        ByteBuffer expectedBuf = ByteBuffer.allocate(expected.length() + 3 + 2);
        expectedBuf.put(expected.getBytes(StandardCharsets.UTF_8));
        expectedBuf.put(value);
        expectedBuf.put("\r\n".getBytes(StandardCharsets.UTF_8));
        expectedBuf.flip();

        assertEquals(expectedBuf.remaining(), result.remaining());
    }

    @Test
    public void testEncodeCommandWithBinaryData() {
        byte[] data = new byte[] { (byte) 0xFF, '\r', '\n', 0x00 };
        ByteBuffer result = encoder.encodeCommand("SET", new byte[][] { "bin".getBytes(StandardCharsets.UTF_8), data });

        // Verify size is correct
        // *3\r\n = 4 bytes
        // $3\r\nSET\r\n = 9 bytes
        // $3\r\nbin\r\n = 9 bytes
        // $4\r\n = 4 bytes + 4 binary bytes + \r\n = 10 bytes
        // Total = 4 + 9 + 9 + 10 = 32 bytes
        assertEquals(32, result.remaining());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mixed argument encoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testEncodeMixedArgs() {
        ByteBuffer result = encoder.encode("SETEX", "key", 60, "value");
        assertEquals("*4\r\n$5\r\nSETEX\r\n$3\r\nkey\r\n$2\r\n60\r\n$5\r\nvalue\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeMixedArgsWithLong() {
        ByteBuffer result = encoder.encode("PSETEX", "key", 5000L, "value");
        assertEquals("*4\r\n$6\r\nPSETEX\r\n$3\r\nkey\r\n$4\r\n5000\r\n$5\r\nvalue\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeMixedArgsWithDouble() {
        ByteBuffer result = encoder.encode("ZADD", "myset", 1.5, "member");
        assertEquals("*4\r\n$4\r\nZADD\r\n$5\r\nmyset\r\n$3\r\n1.5\r\n$6\r\nmember\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeMixedArgsWithByteArray() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        ByteBuffer result = encoder.encode("SET", "key", data);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$4\r\ntest\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeMixedArgsWithNull() {
        ByteBuffer result = encoder.encode("SET", "key", null);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$0\r\n\r\n", bufferToString(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inline command encoding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testEncodeInlineCommand() {
        ByteBuffer result = encoder.encodeInline("PING");
        assertEquals("PING\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeInlineCommandWithArgs() {
        ByteBuffer result = encoder.encodeInline("SET", "key", "value");
        assertEquals("SET key value\r\n", bufferToString(result));
    }

    @Test
    public void testEncodeInlineGet() {
        ByteBuffer result = encoder.encodeInline("GET", "mykey");
        assertEquals("GET mykey\r\n", bufferToString(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Buffer position tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testEncodedBufferIsFlipped() {
        ByteBuffer result = encoder.encodeCommand("PING");
        assertEquals(0, result.position());
        assertTrue(result.remaining() > 0);
    }

    @Test
    public void testEncodedBufferHasCorrectLimit() {
        ByteBuffer result = encoder.encodeCommand("PING");
        // *1\r\n$4\r\nPING\r\n = 14 bytes
        assertEquals(14, result.limit());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testEncodeUnicodeString() {
        ByteBuffer result = encoder.encodeCommand("SET", new String[] { "key", "こんにちは" });
        // The Japanese text is 15 bytes in UTF-8
        String encoded = bufferToString(result);
        assertTrue(encoded.startsWith("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$15\r\n"));
    }

    @Test
    public void testEncodeLargeNumber() {
        ByteBuffer result = encoder.encode("INCRBY", "counter", Long.MAX_VALUE);
        String encoded = bufferToString(result);
        assertTrue(encoded.contains(String.valueOf(Long.MAX_VALUE)));
    }

    @Test
    public void testEncodeNegativeNumber() {
        ByteBuffer result = encoder.encode("INCRBY", "counter", -100);
        String encoded = bufferToString(result);
        assertTrue(encoded.contains("-100"));
    }

    @Test
    public void testEncoderIsThreadSafe() throws InterruptedException {
        // Verify that multiple threads can encode concurrently
        final int threadCount = 10;
        final int iterations = 100;
        Thread[] threads = new Thread[threadCount];
        final boolean[] success = new boolean[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            ByteBuffer result = encoder.encodeCommand("SET",
                                new String[] { "key" + threadIndex, "value" + i });
                            // Verify the result is valid
                            String encoded = bufferToString(result);
                            if (!encoded.startsWith("*3\r\n$3\r\nSET\r\n")) {
                                return;
                            }
                        }
                        success[threadIndex] = true;
                    } catch (Exception e) {
                        // Test failed
                    }
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        for (int t = 0; t < threadCount; t++) {
            assertTrue("Thread " + t + " failed", success[t]);
        }
    }

}

