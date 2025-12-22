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
import org.bluezoo.gumdrop.Server;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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
        for (Server server : servers) {
            if (server instanceof TLSEchoServer) {
                return (TLSEchoServer) server;
            }
        }
        throw new IllegalStateException("TLSEchoServer not found in server list");
    }
    
    /**
     * Creates an SSL socket factory that trusts all certificates.
     */
    private SSLSocketFactory createTrustAllSocketFactory() 
            throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
                
                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }
                
                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }
        };
        
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext.getSocketFactory();
    }
    
    /**
     * Test sending tagged messages sequentially over TLS.
     * Each message has a unique tag that should be echoed back correctly.
     */
    @Test
    public void testSequentialTaggedMessages() throws Exception {
        SSLSocketFactory factory = createTrustAllSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", TEST_PORT);
        
        try {
            socket.setSoTimeout(5000);
            socket.startHandshake();
            
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            
            List<String> sentTags = new ArrayList<>();
            List<String> receivedTags = new ArrayList<>();
            
            // Send 10 tagged messages sequentially
            for (int i = 0; i < 10; i++) {
                String tag = "SEQ" + String.format("%03d", i);
                String message = tag + ":Hello World " + i + "\n";
                sentTags.add(tag);
                
                out.write(message.getBytes("UTF-8"));
                out.flush();
                
                // Read the echoed response
                StringBuilder response = new StringBuilder();
                int b;
                while ((b = in.read()) != -1 && b != '\n') {
                    response.append((char) b);
                }
                
                // Extract tag from response
                String respStr = response.toString();
                int colonPos = respStr.indexOf(':');
                if (colonPos > 0) {
                    receivedTags.add(respStr.substring(0, colonPos));
                }
            }
            
            // Verify all tags match
            assertEquals("Should receive same number of responses", 
                        sentTags.size(), receivedTags.size());
            
            for (int i = 0; i < sentTags.size(); i++) {
                assertEquals("Tag " + i + " should match", sentTags.get(i), receivedTags.get(i));
            }
            
            System.out.println("Sequential test passed: " + sentTags.size() + " messages");
            
        } finally {
            socket.close();
        }
    }
    
    /**
     * Test concurrent writes from multiple threads over a single TLS connection.
     * This is the key test for detecting race conditions in SSL buffer handling.
     */
    @Test
    public void testConcurrentTaggedMessages() throws Exception {
        SSLSocketFactory factory = createTrustAllSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", TEST_PORT);
        
        try {
            socket.setSoTimeout(10000);
            socket.startHandshake();
            
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            
            final int NUM_THREADS = 5;
            final int MESSAGES_PER_THREAD = 20;
            final int TOTAL_MESSAGES = NUM_THREADS * MESSAGES_PER_THREAD;
            
            Set<String> sentTags = Collections.synchronizedSet(new HashSet<>());
            Set<String> receivedTags = Collections.synchronizedSet(new HashSet<>());
            AtomicInteger sendCount = new AtomicInteger(0);
            AtomicInteger corruptCount = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);
            
            // Start reader thread
            Thread readerThread = new Thread(() -> {
                try {
                    StringBuilder current = new StringBuilder();
                    int received = 0;
                    while (received < TOTAL_MESSAGES) {
                        int b = in.read();
                        if (b == -1) break;
                        
                        if (b == '\n') {
                            String line = current.toString();
                            int colonPos = line.indexOf(':');
                            if (colonPos > 0) {
                                String tag = line.substring(0, colonPos);
                                receivedTags.add(tag);
                                
                                // Check for corruption (tag should match format)
                                if (!tag.matches("T[0-4]M\\d{2}")) {
                                    System.err.println("CORRUPTED: " + line);
                                    corruptCount.incrementAndGet();
                                }
                            }
                            received++;
                            current.setLength(0);
                        } else {
                            current.append((char) b);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            readerThread.start();
            
            // Start writer threads
            ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
            for (int t = 0; t < NUM_THREADS; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        
                        for (int m = 0; m < MESSAGES_PER_THREAD; m++) {
                            String tag = "T" + threadId + "M" + String.format("%02d", m);
                            String message = tag + ":Data from thread " + threadId + " msg " + m + "\n";
                            sentTags.add(tag);
                            
                            // Synchronized write to prevent interleaving
                            synchronized (out) {
                                out.write(message.getBytes("UTF-8"));
                                out.flush();
                            }
                            sendCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            
            // Start all writers at once
            startLatch.countDown();
            
            // Wait for all writers to finish
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();
            
            // Wait for reader to finish
            readerThread.join(5000);
            
            System.out.println("Sent: " + sendCount.get() + ", Received: " + receivedTags.size() + 
                             ", Corrupted: " + corruptCount.get());
            
            // Verify results
            assertEquals("Should send all messages", TOTAL_MESSAGES, sendCount.get());
            assertEquals("Should receive all messages", TOTAL_MESSAGES, receivedTags.size());
            assertEquals("Should have no corrupted messages", 0, corruptCount.get());
            
            // Check all sent tags were received
            for (String sentTag : sentTags) {
                assertTrue("Should receive " + sentTag, receivedTags.contains(sentTag));
            }
            
        } finally {
            socket.close();
        }
    }
    
    /**
     * Test rapid-fire messages without any delay between writes.
     */
    @Test
    public void testRapidFireMessages() throws Exception {
        SSLSocketFactory factory = createTrustAllSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", TEST_PORT);
        
        try {
            socket.setSoTimeout(10000);
            socket.startHandshake();
            
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            
            final int NUM_MESSAGES = 100;
            Set<String> sentTags = new HashSet<>();
            Set<String> receivedTags = new HashSet<>();
            AtomicInteger corruptCount = new AtomicInteger(0);
            
            // Start reader thread
            Thread readerThread = new Thread(() -> {
                try {
                    StringBuilder current = new StringBuilder();
                    int received = 0;
                    while (received < NUM_MESSAGES) {
                        int b = in.read();
                        if (b == -1) break;
                        
                        if (b == '\n') {
                            String line = current.toString();
                            int colonPos = line.indexOf(':');
                            if (colonPos > 0) {
                                String tag = line.substring(0, colonPos);
                                receivedTags.add(tag);
                                
                                if (!tag.matches("RAPID\\d{3}")) {
                                    System.err.println("CORRUPTED: " + line);
                                    corruptCount.incrementAndGet();
                                }
                            }
                            received++;
                            current.setLength(0);
                        } else {
                            current.append((char) b);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            readerThread.start();
            
            // Send messages as fast as possible
            for (int i = 0; i < NUM_MESSAGES; i++) {
                String tag = "RAPID" + String.format("%03d", i);
                String message = tag + ":Rapid fire message " + i + "\n";
                sentTags.add(tag);
                out.write(message.getBytes("UTF-8"));
            }
            out.flush();
            
            // Wait for reader
            readerThread.join(10000);
            
            System.out.println("Rapid fire: Sent " + sentTags.size() + ", Received " + 
                             receivedTags.size() + ", Corrupted " + corruptCount.get());
            
            assertEquals("Should receive all messages", NUM_MESSAGES, receivedTags.size());
            assertEquals("Should have no corrupted messages", 0, corruptCount.get());
            
            for (String tag : sentTags) {
                assertTrue("Should receive " + tag, receivedTags.contains(tag));
            }
            
        } finally {
            socket.close();
        }
    }
}
