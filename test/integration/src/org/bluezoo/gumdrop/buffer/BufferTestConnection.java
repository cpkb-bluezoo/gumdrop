/*
 * BufferTestConnection.java
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

import org.bluezoo.gumdrop.Connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Test connection that looks for complete messages and leaves incomplete data.
 * 
 * <p>This connection simulates a real message-based protocol. It scans the
 * buffer for complete message patterns, consumes all complete messages found,
 * and leaves any incomplete trailing data for the next receive() call.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BufferTestConnection extends Connection {
    
    private static final Logger LOGGER = Logger.getLogger(BufferTestConnection.class.getName());
    
    private final BufferTestServer server;
    private final SocketChannel channel;
    
    /** All complete messages received, in order */
    private final List<String> receivedMessages;
    
    /** The bytes seen at the start of each receive() call (for debugging) */
    private final List<byte[]> receiveCallData;
    
    /** Number of times receive() has been called */
    private int receiveCallCount;
    
    /** Flag to track if connection was closed by peer */
    private volatile boolean peerClosed;
    
    /**
     * Creates a new buffer test connection.
     * 
     * @param server the parent server
     * @param channel the socket channel
     * @param engine the SSL engine (may be null)
     * @param secure whether TLS is active immediately
     */
    public BufferTestConnection(BufferTestServer server, SocketChannel channel, 
                                 SSLEngine engine, boolean secure) {
        super(engine, secure);
        this.server = server;
        this.channel = channel;
        this.receivedMessages = Collections.synchronizedList(new ArrayList<String>());
        this.receiveCallData = Collections.synchronizedList(new ArrayList<byte[]>());
        this.receiveCallCount = 0;
        this.peerClosed = false;
    }
    
    /**
     * Receives data from the client.
     * 
     * <p>This method looks for complete message patterns in the buffer,
     * consumes all complete messages found, and leaves any incomplete
     * trailing data for the next receive() call.
     * 
     * @param data the application data received
     */
    @Override
    public void receive(ByteBuffer data) {
        receiveCallCount++;
        int available = data.remaining();
        byte[] messagePattern = server.getMessagePattern();
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("receive() call #" + receiveCallCount + 
                       ": available=" + available + 
                       ", looking for pattern: " + new String(messagePattern));
        }
        
        // Record the data at the start of this receive call (for test verification)
        byte[] snapshot = new byte[available];
        int originalPosition = data.position();
        data.get(snapshot);
        data.position(originalPosition); // Reset for actual consumption
        receiveCallData.add(snapshot);
        
        // Consume all complete messages we can find
        int messagesFound = 0;
        while (data.remaining() >= messagePattern.length) {
            // Check if the next bytes match the message pattern
            boolean matches = true;
            int checkPos = data.position();
            
            for (int i = 0; i < messagePattern.length; i++) {
                if (data.get(checkPos + i) != messagePattern[i]) {
                    matches = false;
                    break;
                }
            }
            
            if (matches) {
                // Found a complete message - consume it
                byte[] message = new byte[messagePattern.length];
                data.get(message);
                receivedMessages.add(new String(message));
                messagesFound++;
                
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Found complete message #" + messagesFound);
                }
            } else {
                // No match at current position - this is incomplete data
                // Leave it for the next receive() call
                break;
            }
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("After consume: found " + messagesFound + " messages, " +
                       "remaining=" + data.remaining() + " bytes (incomplete)");
        }
        
        // Note: We leave the buffer position where it is.
        // Any remaining bytes are incomplete message data that the
        // framework should preserve for the next receive() call.
    }
    
    @Override
    protected void disconnected() throws IOException {
        peerClosed = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Connection closed by peer after " + receiveCallCount + " receive calls");
        }
    }
    
    /**
     * Returns all complete messages received.
     * 
     * @return list of received messages in order
     */
    public List<String> getReceivedMessages() {
        synchronized (receivedMessages) {
            return new ArrayList<String>(receivedMessages);
        }
    }
    
    /**
     * Returns the total bytes consumed (complete messages only).
     * 
     * @return total consumed byte count
     */
    public int getTotalBytesConsumed() {
        return receivedMessages.size() * server.getMessagePattern().length;
    }
    
    /**
     * Returns the data snapshot from a specific receive() call.
     * 
     * @param callIndex 0-based index of the receive() call
     * @return the bytes available at the start of that call, or null if invalid index
     */
    public byte[] getReceiveCallData(int callIndex) {
        synchronized (receiveCallData) {
            if (callIndex >= 0 && callIndex < receiveCallData.size()) {
                return receiveCallData.get(callIndex);
            }
            return null;
        }
    }
    
    /**
     * Returns the number of times receive() has been called.
     * 
     * @return receive call count
     */
    public int getReceiveCallCount() {
        return receiveCallCount;
    }
    
    /**
     * Returns true if the peer closed the connection.
     * 
     * @return true if disconnected() was called
     */
    public boolean isPeerClosed() {
        return peerClosed;
    }
    
    /**
     * Resets all recorded data for fresh testing.
     */
    public void reset() {
        receivedMessages.clear();
        receiveCallData.clear();
        receiveCallCount = 0;
    }
}
