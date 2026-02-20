/*
 * ClientHandler.java
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

package org.bluezoo.gumdrop;

/**
 * Base interface for handling client-side connection lifecycle events.
 *
 * <p>Provides transport-agnostic callbacks for connection, disconnection,
 * security establishment, and errors, expressed in terms of
 * {@link Endpoint} and {@link SecurityInfo}.
 *
 * <p>Protocol-specific subinterfaces extend this base interface to add
 * protocol-specific events and behaviours (e.g., SMTP greeting handling,
 * HTTP session readiness).
 *
 * <p>All handler methods are called from the Endpoint's selector thread
 * and should not perform blocking operations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientEndpoint
 * @see Endpoint
 * @see SecurityInfo
 */
public interface ClientHandler {

    /**
     * Called when the transport connection has been successfully established.
     *
     * <p>This method is invoked after the connection completes and any
     * initial security handshake (if applicable) has finished. The endpoint
     * is ready for protocol-specific communication.
     *
     * <p>The {@link Endpoint} provides access to:
     * <ul>
     *   <li>Local and remote socket addresses</li>
     *   <li>Whether the connection is secure</li>
     *   <li>Security details via {@link Endpoint#getSecurityInfo()}</li>
     * </ul>
     *
     * @param endpoint the connected endpoint
     */
    void onConnected(Endpoint endpoint);

    /**
     * Called when a connection or protocol error occurs.
     *
     * <p>This method is invoked for various error conditions including:
     * <ul>
     *   <li>Connection failures (host unreachable, connection refused)</li>
     *   <li>TLS/QUIC handshake failures</li>
     *   <li>Network I/O errors during operation</li>
     * </ul>
     *
     * <p>After this method is called, the connection should be considered
     * unusable.
     *
     * @param cause the exception that caused the error
     */
    void onError(Exception cause);

    /**
     * Called when the remote peer has closed the connection.
     *
     * <p>This provides an opportunity for cleanup before connection
     * resources are released.
     */
    void onDisconnected();

    /**
     * Called when a TLS or QUIC security upgrade has completed.
     *
     * <p>For STARTTLS-style upgrades (SMTP, LDAP), this is called after
     * the in-band TLS handshake completes. For connections that are
     * secure from the start (implicit TLS, QUIC), this may be called
     * before or combined with {@link #onConnected}.
     *
     * <p>The {@link SecurityInfo} provides:
     * <ul>
     *   <li>Protocol version (TLSv1.2, TLSv1.3, QUICv1)</li>
     *   <li>Cipher suite</li>
     *   <li>Peer and local certificates</li>
     *   <li>ALPN negotiated protocol</li>
     *   <li>Session resumption status</li>
     * </ul>
     *
     * @param info the security session details
     */
    void onSecurityEstablished(SecurityInfo info);

}
