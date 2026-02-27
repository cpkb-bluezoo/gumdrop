/*
 * ServerCapaReplyHandler.java
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

import java.util.List;

/**
 * Handler for CAPA command response.
 *
 * <p>This handler receives the server's capability listing (RFC 2449).
 * Capabilities are parsed into structured fields so the handler can
 * make decisions about STLS, authentication mechanisms, and available
 * commands.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientAuthorizationState#capa
 * @see ClientPostStls#capa
 */
public interface ServerCapaReplyHandler extends ServerReplyHandler {

    /**
     * Called when the server responds with its capabilities.
     *
     * @param auth operations to continue in the AUTHORIZATION state
     * @param stls true if the STLS capability is advertised
     * @param saslMechanisms list of SASL mechanism names, empty if
     *                       SASL is not advertised
     * @param top true if the TOP capability is advertised
     * @param uidl true if the UIDL capability is advertised
     * @param user true if the USER capability is advertised
     * @param pipelining true if the PIPELINING capability is advertised
     * @param implementation the IMPLEMENTATION string, or null
     */
    void handleCapabilities(ClientAuthorizationState auth,
                            boolean stls, List<String> saslMechanisms,
                            boolean top, boolean uidl, boolean user,
                            boolean pipelining, String implementation);

    /**
     * Called when the CAPA command fails.
     *
     * <p>Some older servers may not support the CAPA command. The handler
     * can proceed with authentication without capability information.
     *
     * @param auth operations to continue in the AUTHORIZATION state
     * @param message the server's error message
     */
    void handleError(ClientAuthorizationState auth, String message);

}
