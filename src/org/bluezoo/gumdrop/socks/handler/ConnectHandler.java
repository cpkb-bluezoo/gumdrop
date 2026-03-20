/*
 * ConnectHandler.java
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

package org.bluezoo.gumdrop.socks.handler;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.socks.SOCKSRequest;

/**
 * Handler for authorizing incoming SOCKS CONNECT requests.
 *
 * <p>Called after successful SOCKS method negotiation (RFC 1928 §3) and
 * authentication (RFC 1929 or RFC 1961), when a CONNECT request
 * (RFC 1928 §4: CMD=0x01) is received.
 *
 * <p>Implementations can perform custom authorization logic — for
 * example, checking whether a user is allowed to connect to a
 * particular destination, applying rate limits, or logging access.
 *
 * <p>The handler receives a {@link ConnectState} callback and must
 * call exactly one of {@link ConnectState#allow()} or
 * {@link ConnectState#deny(int)} when the decision is ready. The
 * decision may be made asynchronously.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectState
 * @see org.bluezoo.gumdrop.socks.SOCKSService#createConnectHandler
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928 §4</a>
 */
public interface ConnectHandler {

    /**
     * Called when a SOCKS CONNECT request has been received and
     * (if applicable) authenticated.
     *
     * <p>The implementation must call {@link ConnectState#allow()} to
     * permit the connection, or {@link ConnectState#deny(int)} to
     * reject it with a SOCKS reply code.
     *
     * @param state the callback for communicating the decision
     * @param request the parsed SOCKS request (includes destination,
     *                version, userid, authenticated user)
     * @param clientEndpoint the client's endpoint (for address info)
     */
    void handleConnect(ConnectState state, SOCKSRequest request,
                       Endpoint clientEndpoint);

}
