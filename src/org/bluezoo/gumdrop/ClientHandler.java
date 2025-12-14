/*
 * ClientHandler.java
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

package org.bluezoo.gumdrop;

/**
 * Base interface for handling client-side connection lifecycle events.
 * This interface defines the fundamental TCP connection events that all
 * client protocol implementations must handle.
 * 
 * <p>Client handlers are event-driven and asynchronous, responding to
 * network events delivered by the underlying Connection. Protocol-specific
 * subinterfaces extend this base interface to add protocol-specific events
 * and behaviors.
 * 
 * <p>All handler methods are called from the Connection's executor thread
 * and should not perform blocking operations. Long-running or blocking
 * operations should be delegated to separate threads.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Client#connect(ClientHandler)
 * @see ConnectionInfo
 * @see TLSInfo
 */
public interface ClientHandler {

    /**
     * Called when the TCP connection has been successfully established.
     * 
     * <p>This method is invoked after the socket connection completes and
     * any SSL handshake (if applicable) has finished successfully. The
     * connection is ready for protocol-specific communication.
     * 
     * <p>The {@link ConnectionInfo} parameter provides details about the
     * connection including:
     * <ul>
     * <li>Local and remote socket addresses</li>
     * <li>Whether the connection is secure (TLS)</li>
     * <li>TLS details if secure (protocol, cipher, certificates)</li>
     * </ul>
     * 
     * <p>Protocol implementations typically use this event to initiate
     * the protocol handshake or send initial commands.
     * 
     * @param info details about the established connection
     */
    void onConnected(ConnectionInfo info);

    /**
     * Called when a connection or protocol error occurs.
     * 
     * <p>This method is invoked for various error conditions including:
     * <ul>
     * <li>TCP connection failures (host unreachable, connection refused)</li>
     * <li>SSL handshake failures</li>
     * <li>Network I/O errors during operation</li>
     * <li>Protocol-specific error conditions</li>
     * </ul>
     * 
     * <p>After this method is called, the connection should be considered
     * unusable and will be cleaned up automatically.
     * 
     * @param cause the exception that caused the error condition
     */
    void onError(Exception cause);

    /**
     * Called when the remote peer has closed the connection.
     * 
     * <p>This method is invoked when the remote end closes the TCP connection
     * gracefully. It provides an opportunity for cleanup and final processing
     * before the connection resources are released.
     * 
     * <p>Protocol implementations can use this event to update statistics,
     * log completion status, or trigger reconnection logic if appropriate.
     */
    void onDisconnected();

    /**
     * Called when a TLS upgrade has been successfully completed.
     * 
     * <p>This method is invoked after a successful STARTTLS command response
     * and the completion of the TLS handshake. The connection is now secure
     * and all subsequent communication will be encrypted.
     * 
     * <p>The {@link TLSInfo} parameter provides details about the TLS session:
     * <ul>
     * <li>Protocol version (TLSv1.2, TLSv1.3)</li>
     * <li>Cipher suite</li>
     * <li>Peer and local certificates</li>
     * <li>Whether session resumption was used</li>
     * <li>ALPN negotiated protocol</li>
     * </ul>
     * 
     * <p>Protocol implementations can use this event to:
     * <ul>
     * <li>Reset protocol state (as required by some protocols like SMTP)</li>
     * <li>Re-issue capabilities or handshake commands</li>
     * <li>Update security-related state or configuration</li>
     * <li>Log security upgrade events</li>
     * </ul>
     * 
     * @param info details about the TLS session
     */
    void onTLSStarted(TLSInfo info);
}
