/*
 * RESPEncoderIntegrationTest.java
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
 * Integration tests for {@link RespEncoder}.
 *
 * <p>Integration test: shares one encoder between real threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RESPEncoderIntegrationTest {

    private RespEncoder encoder;

    @Before
    public void setUp() {
        encoder = new RespEncoder();
    }

    private String bufferToString(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command encoding (no arguments)
    // ─────────────────────────────────────────────────────────────────────────
    // Command encoding (string arguments)
    // ─────────────────────────────────────────────────────────────────────────
    // Command encoding (byte array arguments)
    // ─────────────────────────────────────────────────────────────────────────
    // Mixed argument encoding
    // ─────────────────────────────────────────────────────────────────────────
    // Inline command encoding
    // ─────────────────────────────────────────────────────────────────────────
    // Buffer position tests
    // ─────────────────────────────────────────────────────────────────────────
    // Edge cases
    // ─────────────────────────────────────────────────────────────────────────

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
