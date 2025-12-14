/*
 * ServerStarttlsReplyHandler.java
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

package org.bluezoo.gumdrop.smtp.client.handler;

/**
 * Handler for STARTTLS command response.
 * 
 * <p>This handler receives the server's response to a STARTTLS command.
 * If TLS is successfully established, the handler receives a
 * {@link ClientPostTls} interface and <em>must</em> re-issue EHLO as
 * required by RFC 3207.
 * 
 * <p>If TLS is not available (whether due to 454, 502, or handshake failure),
 * the handler receives a {@link ClientSession} interface and can decide
 * whether to continue without TLS or abort the connection.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientPostTls
 * @see ClientSession
 */
public interface ServerStarttlsReplyHandler extends ServerReplyHandler {

    /**
     * Called when TLS is successfully established.
     * 
     * <p>This callback is invoked after the server sends 220 and the TLS
     * handshake completes successfully. Per RFC 3207, the handler
     * <em>must</em> re-issue EHLO before proceeding with the session.
     * 
     * @param postTls operations available after TLS (must re-EHLO)
     */
    void handleTlsEstablished(ClientPostTls postTls);

    /**
     * Called when TLS is not available.
     * 
     * <p>This is invoked for any of:
     * <ul>
     * <li>454 - TLS temporarily unavailable</li>
     * <li>502 - STARTTLS not implemented</li>
     * <li>TLS handshake failure</li>
     * </ul>
     * 
     * <p>The handler can continue without TLS using the provided session
     * interface, or abort the connection if TLS is required.
     * 
     * @param session operations to continue without TLS
     */
    void handleTlsUnavailable(ClientSession session);

    /**
     * Called when STARTTLS permanently fails (554 or other 5xx).
     * 
     * <p>The connection will be closed.
     * 
     * @param message the server's error message
     */
    void handlePermanentFailure(String message);

}

