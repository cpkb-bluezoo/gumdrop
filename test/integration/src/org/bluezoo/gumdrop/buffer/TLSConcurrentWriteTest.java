/*
 * TLSConcurrentWriteTest.java
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

package org.bluezoo.gumdrop.buffer;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.IntegrationTlsClient;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Test for TLS buffer race conditions with concurrent writes.
 * 
 * <p>This test sends tagged messages over TLS and verifies they are echoed
 * back correctly without corruption. Uses the existing BufferTestServer
 * in echo mode.
 *
 * <p>The key test is concurrent writes from multiple threads to detect
 * any race conditions in the SSL wrap/unwrap synchronization.
 */
public class TLSConcurrentWriteTest extends AbstractServerIntegrationTest {
    
    private static final int TEST_PORT = 19445;
    // Use a simple tagged message format: "TAG:DATA\n"
    
    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/tls-concurrent-write-test.xml");
    }
    
    /**
     * Returns the TLSEchoServer instance from the running servers.
     */
    private TLSEchoServer getEchoServer() {
        for (TCPListener server : servers) {
            if (server instanceof TLSEchoServer) {
                return (TLSEchoServer) server;
            }
        }
        throw new IllegalStateException("TLSEchoServer not found in server list");
    }
    
    private static final EmptyX509TrustManager TRUST_ALL = new EmptyX509TrustManager();

    private static byte[] echoExchange(String message) throws Exception {
        byte[] outbound = message.getBytes(StandardCharsets.UTF_8);
        return IntegrationTlsClient.exchangeWhenComplete("::1", TEST_PORT, outbound, TRUST_ALL, 10000,
                inbound -> {
                    for (int i = 0; i < inbound.length; i++) {
                        if (inbound[i] == '\n') {
                            return true;
                        }
                    }
                    return false;
                });
    }

    private static String readEchoTag(byte[] response) {
        String line = new String(response, StandardCharsets.UTF_8);
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline);
        }
        int colonPos = line.indexOf(':');
        return colonPos > 0 ? line.substring(0, colonPos) : line;
    }

    /**
     * Test sending tagged messages sequentially over TLS.
     * Each message has a unique tag that should be echoed back correctly.
     */
    @Test
    public void testSequentialTaggedMessages() throws Exception {
        List<String> sentTags = new ArrayList<String>();
        List<String> receivedTags = new ArrayList<String>();

        for (int i = 0; i < 10; i++) {
            String tag = "SEQ" + String.format("%03d", i);
            String message = tag + ":Hello World " + i + "\n";
            sentTags.add(tag);

            byte[] response = echoExchange(message);
            receivedTags.add(readEchoTag(response));
        }

        assertEquals("Should receive same number of responses", sentTags.size(), receivedTags.size());
        for (int i = 0; i < sentTags.size(); i++) {
            assertEquals("Tag " + i + " should match", sentTags.get(i), receivedTags.get(i));
        }
    }

    /**
     * Test concurrent TLS echo sessions from multiple threads.
     */
    @Test
    public void testConcurrentTaggedMessages() throws Exception {
        final int NUM_THREADS = 5;
        final int MESSAGES_PER_THREAD = 20;
        final int TOTAL_MESSAGES = NUM_THREADS * MESSAGES_PER_THREAD;

        Set<String> sentTags = Collections.synchronizedSet(new HashSet<String>());
        Set<String> receivedTags = Collections.synchronizedSet(new HashSet<String>());
        AtomicInteger sendCount = new AtomicInteger(0);
        AtomicInteger corruptCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        for (int t = 0; t < NUM_THREADS; t++) {
            final int threadId = t;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        startLatch.await();
                        for (int m = 0; m < MESSAGES_PER_THREAD; m++) {
                            String tag = "T" + threadId + "M" + String.format("%02d", m);
                            String message = tag + ":Data from thread " + threadId + " msg " + m + "\n";
                            sentTags.add(tag);
                            byte[] response = echoExchange(message);
                            String echoedTag = readEchoTag(response);
                            receivedTags.add(echoedTag);
                            if (!echoedTag.matches("T[0-4]M\\d{2}")) {
                                corruptCount.incrementAndGet();
                            }
                            sendCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals("Should send all messages", TOTAL_MESSAGES, sendCount.get());
        assertEquals("Should receive all messages", TOTAL_MESSAGES, receivedTags.size());
        assertEquals("Should have no corrupted messages", 0, corruptCount.get());
        for (String sentTag : sentTags) {
            assertTrue("Should receive " + sentTag, receivedTags.contains(sentTag));
        }
    }

    /**
     * Test rapid-fire messages without any delay between writes.
     */
    @Test
    public void testRapidFireMessages() throws Exception {
        final int NUM_MESSAGES = 100;
        Set<String> sentTags = new HashSet<String>();
        Set<String> receivedTags = new HashSet<String>();
        AtomicInteger corruptCount = new AtomicInteger(0);

        for (int i = 0; i < NUM_MESSAGES; i++) {
            String tag = "RAPID" + String.format("%03d", i);
            String message = tag + ":Rapid fire message " + i + "\n";
            sentTags.add(tag);
            byte[] response = echoExchange(message);
            String echoedTag = readEchoTag(response);
            receivedTags.add(echoedTag);
            if (!echoedTag.matches("RAPID\\d{3}")) {
                corruptCount.incrementAndGet();
            }
        }

        assertEquals("Should receive all messages", NUM_MESSAGES, receivedTags.size());
        assertEquals("Should have no corrupted messages", 0, corruptCount.get());
        for (String tag : sentTags) {
            assertTrue("Should receive " + tag, receivedTags.contains(tag));
        }
    }
}
