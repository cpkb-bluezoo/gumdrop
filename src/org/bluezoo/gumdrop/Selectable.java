/*
 * Copyright (C) 2004-2025 Gumdrop Server contributors.
 */

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.BlockingQueue;

/**
 * Interface for objects that can be attached to SelectionKeys in the Server's main event loop.
 * This allows both server-side connections (Connection) and client-side connections 
 * (like SMTPClientConnection) to participate in the same Selector-based event processing.
 * 
 * @author <a href='mailto:cburdess@mimecast.com'>Chris Burdess</a>
 */
public interface Selectable {
    
    /**
     * Called when a client connection completes successfully (OP_CONNECT).
     * Only relevant for client-side connections.
     */
    default void connected() {
        // Default no-op for server-side connections
    }
    
    /**
     * Called when a client connection fails to complete (OP_CONNECT error).
     * Only relevant for client-side connections.
     * 
     * @param cause the connection failure cause
     */
    default void finishConnectFailed(IOException cause) {
        // Default no-op for server-side connections
    }
    
    /**
     * Called when data is available to read (OP_READ).
     * 
     * @param data the received data buffer
     */
    void receive(ByteBuffer data);
    
    /**
     * Called when a read operation fails (OP_READ error).
     * 
     * @param cause the read failure cause
     */
    void receiveFailed(IOException cause);
    
    /**
     * Closes this selectable object and any associated resources.
     */
    void close();
    
    // Write operations support for Server Selector integration
    
    /**
     * Returns the outbound data queue for this selectable object.
     * The Server will drain this queue when OP_WRITE events occur.
     * 
     * @return the outbound data queue
     */
    BlockingQueue<ByteBuffer> getOutboundQueue();
    
    /**
     * Returns true if this selectable has OP_WRITE interest registered.
     * Used by Server to avoid duplicate OP_WRITE registrations.
     * 
     * @return true if OP_WRITE interest is active
     */
    boolean hasOpWriteInterest();
    
    /**
     * Sets the OP_WRITE interest flag for this selectable.
     * Called by Server when managing OP_WRITE interest ops.
     * 
     * @param value true to mark OP_WRITE interest as active
     */
    void setHasOpWriteInterest(boolean value);
    
    /**
     * Returns the SelectionKey associated with this selectable.
     * Used by Server for managing interest operations.
     * 
     * @return the SelectionKey, or null if not registered
     */
    SelectionKey getSelectionKey();
    
    /**
     * Sets the SelectionKey for this selectable.
     * Called by Server when registering with Selector.
     * 
     * @param key the SelectionKey
     */
    void setSelectionKey(SelectionKey key);
    
    /**
     * Returns true if this selectable should be closed after sending pending data.
     * Used by Server to handle graceful connection shutdown.
     * 
     * @return true if close is pending after send completion
     */
    default boolean shouldCloseAfterSend() {
        return false;
    }
}
