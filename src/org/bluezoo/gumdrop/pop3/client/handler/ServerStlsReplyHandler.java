/*
 * ServerStlsReplyHandler.java
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

package org.bluezoo.gumdrop.pop3.client.handler;

/**
 * Handler for STLS command response.
 *
 * <p>This handler receives the server's response to a STLS command.
 * If TLS is successfully established, the handler receives a
 * {@link ClientPostStls} interface and should re-issue CAPA to learn
 * the server's post-TLS capabilities.
 *
 * <p>If TLS is not available, the handler receives a
 * {@link ClientAuthorizationState} interface and can decide whether to
 * continue without TLS or abort the connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthorizationState#stls
 * @see ClientPostStls
 */
public interface ServerStlsReplyHandler extends ServerReplyHandler {

    /**
     * Called when TLS is successfully established.
     *
     * <p>This callback is invoked after the server sends +OK and the
     * TLS handshake completes successfully. The handler should re-issue
     * CAPA to discover post-TLS capabilities.
     *
     * @param postStls operations available after TLS
     */
    void handleTlsEstablished(ClientPostStls postStls);

    /**
     * Called when TLS is not available.
     *
     * <p>This is invoked when the server responds with -ERR or when the
     * TLS handshake fails. The handler can continue without TLS or abort.
     *
     * @param auth operations to continue without TLS
     */
    void handleTlsUnavailable(ClientAuthorizationState auth);

    /**
     * Called when TLS permanently fails and the connection must close.
     *
     * @param message the error message
     */
    void handlePermanentFailure(String message);

}
