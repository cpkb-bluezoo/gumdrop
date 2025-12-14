/*
 * ServerEhloReplyHandler.java
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

import java.util.List;

/**
 * Handler for EHLO command response.
 * 
 * <p>This handler receives the server's response to an EHLO command, including
 * the advertised SMTP extensions. On success, the handler receives detailed
 * capability information and a {@link ClientSession} interface for continuing
 * the session.
 * 
 * <p>If EHLO is not supported (502), the handler should fall back to HELO
 * using the provided {@link ClientHelloState} interface.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientSession
 * @see ClientHelloState
 */
public interface ServerEhloReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server accepts EHLO (250).
     * 
     * <p>The handler receives the server's advertised capabilities and can
     * proceed with the session. Based on the capabilities, the handler may
     * choose to:
     * <ul>
     * <li>Upgrade to TLS via {@code session.starttls()} if {@code starttls} is true</li>
     * <li>Authenticate via {@code session.auth()} if {@code authMethods} is not empty</li>
     * <li>Begin sending mail via {@code session.mailFrom()}</li>
     * </ul>
     * 
     * @param starttls true if STARTTLS extension is available
     * @param maxSize maximum message size in bytes (0 if SIZE not advertised)
     * @param authMethods list of available SASL mechanisms (empty if AUTH not advertised)
     * @param pipelining true if PIPELINING extension is available
     * @param session operations available in the established session
     */
    void handleEhlo(boolean starttls, long maxSize, List<String> authMethods,
                    boolean pipelining, ClientSession session);

    /**
     * Called when EHLO is not supported (502).
     * 
     * <p>The handler should fall back to using HELO with the provided
     * {@link ClientHelloState} interface.
     * 
     * @param hello operations to retry with HELO
     */
    void handleEhloNotSupported(ClientHelloState hello);

    /**
     * Called when the server permanently rejects the client (5xx other than 502).
     * 
     * <p>This indicates the server will not accept connections from this client.
     * The connection will be closed.
     * 
     * @param message the server's rejection message
     */
    void handlePermanentFailure(String message);

}

