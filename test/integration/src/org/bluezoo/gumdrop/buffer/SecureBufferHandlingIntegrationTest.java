/*
 * SecureBufferHandlingIntegrationTest.java
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
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration test for buffer underflow handling over TLS connections.
 * 
 * <p>This test verifies that the SSLState unwrap process correctly handles
 * cases where the Connection's {@code receive()} method only partially
 * consumes the decrypted application data (because an incomplete message
 * is waiting for more data).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SecureBufferHandlingIntegrationTest extends AbstractServerIntegrationTest {
    
    private static final int TEST_PORT = 19443;
    private static final String MESSAGE_PATTERN = "0123456789";
    
    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/buffer-test-secure.xml");
    }
    
    /**
     * Returns the BufferTestServer instance from the running servers.
     */
    private BufferTestServer getBufferTestServer() {
        for (TCPListener server : servers) {
            if (server instanceof BufferTestServer) {
                return (BufferTestServer) server;
            }
        }
        throw new IllegalStateException("BufferTestServer not found in server list");
    }
    
    /**
     * Creates an SSL socket factory that trusts all certificates.
     * For testing only.
     */
    private static final EmptyX509TrustManager TRUST_ALL = new EmptyX509TrustManager();

    @Test
    public void testSecureServerStartsAndAcceptsConnections() throws Exception {
        assertNotNull("Gumdrop should be running", gumdrop);
        assertTrue("Port " + TEST_PORT + " should be listening", 
                  isPortListening("127.0.0.1", TEST_PORT));
        
        // Verify server is secure
        BufferTestServer server = getBufferTestServer();
        assertTrue("Server should be configured as secure", server.isSecure());
    }
    
    @Test
    public void testSecureSingleCompleteMessage() throws Exception {
        // Send exactly one complete message over TLS
        BufferTestServer server = getBufferTestServer();
        server.setMessagePattern(MESSAGE_PATTERN);
        server.clearConnections();
        
        sendSecureDataAndClose("0123456789".getBytes("US-ASCII"));
        pause(300);
        
        assertEquals("Should have one connection", 1, server.getConnections().size());
        BufferTestConnection conn = server.getConnections().get(0);
        
        List<String> messages = conn.getReceivedMessages();
        assertEquals("Should receive exactly one message over TLS", 1, messages.size());
        assertEquals("Message should match pattern", MESSAGE_PATTERN, messages.get(0));
    }
    
    @Test
    public void testSecureMultipleMessagesInOneChunk() throws Exception {
        // Send two complete messages in one TLS write
        BufferTestServer server = getBufferTestServer();
        server.setMessagePattern(MESSAGE_PATTERN);
        server.clearConnections();
        
        sendSecureDataAndClose("01234567890123456789".getBytes("US-ASCII"));
        pause(300);
        
        assertEquals("Should have one connection", 1, server.getConnections().size());
        BufferTestConnection conn = server.getConnections().get(0);
        
        List<String> messages = conn.getReceivedMessages();
        assertEquals("Should receive two messages over TLS", 2, messages.size());
    }
    
    @Test
    public void testSecureUnderflowPreservation() throws Exception {
        // Test that underflow data is preserved correctly with TLS
        // Same pattern as plaintext test:
        // Client sends: "0123456" (7 bytes) - incomplete message
        // Client sends: "7890123" (7 bytes) - completes first, starts second  
        // Client sends: "456789" (6 bytes) - completes second
        
        BufferTestServer server = getBufferTestServer();
        server.setMessagePattern(MESSAGE_PATTERN);
        server.clearConnections();
        
        IntegrationTlsClient.withConnectedEndpoint("127.0.0.1", TEST_PORT, TRUST_ALL, 10000, endpoint -> {
            endpoint.send(ByteBuffer.wrap("0123456".getBytes("US-ASCII")));
            pause(100);
            endpoint.send(ByteBuffer.wrap("7890123".getBytes("US-ASCII")));
            pause(100);
            endpoint.send(ByteBuffer.wrap("456789".getBytes("US-ASCII")));
            pause(100);
            endpoint.close();
        });
        
        pause(400);
        
        assertEquals("Should have one connection", 1, server.getConnections().size());
        BufferTestConnection conn = server.getConnections().get(0);
        
        List<String> messages = conn.getReceivedMessages();
        int callCount = conn.getReceiveCallCount();
        
        System.out.println("Secure underflow preservation test:");
        System.out.println("  Receive call count: " + callCount);
        for (int i = 0; i < callCount; i++) {
            byte[] callData = conn.getReceiveCallData(i);
            if (callData != null) {
                System.out.println("  Call " + (i + 1) + " saw: \"" + 
                                  new String(callData, "US-ASCII") + "\" (" + callData.length + " bytes)");
            }
        }
        System.out.println("  Messages found: " + messages.size());
        
        // Verify we found both complete messages
        assertEquals("Should find 2 complete messages over TLS", 2, messages.size());
        assertEquals("First message should match", MESSAGE_PATTERN, messages.get(0));
        assertEquals("Second message should match", MESSAGE_PATTERN, messages.get(1));
        
        // Verify the underflow was preserved
        if (callCount >= 2) {
            byte[] call2Data = conn.getReceiveCallData(1);
            assertNotNull("Should have data for call 2", call2Data);
            assertTrue("Call 2 should have more than 7 bytes (new + underflow)", 
                      call2Data.length > 7);
            assertEquals("Call 2 should start with '0' (underflow from call 1)",
                        '0', call2Data[0]);
        }
    }
    
    @Test
    public void testSecureIncompleteMessagePreserved() throws Exception {
        // Send data that doesn't complete a message
        BufferTestServer server = getBufferTestServer();
        server.setMessagePattern(MESSAGE_PATTERN);
        server.clearConnections();
        
        sendSecureDataAndClose("01234".getBytes("US-ASCII"));
        pause(300);
        
        assertEquals("Should have one connection", 1, server.getConnections().size());
        BufferTestConnection conn = server.getConnections().get(0);
        
        List<String> messages = conn.getReceivedMessages();
        assertEquals("Should receive no complete messages over TLS", 0, messages.size());
    }
    
    // ============== Helper Methods ==============
    
    /**
     * Sends data to the test server over TLS and closes the connection.
     */
    private void sendSecureDataAndClose(byte[] data) throws Exception {
        IntegrationTlsClient.sendAndClose("127.0.0.1", TEST_PORT, data, TRUST_ALL, 5000);
        pause(100);
    }
}
