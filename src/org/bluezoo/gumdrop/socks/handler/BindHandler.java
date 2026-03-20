/*
 * BindHandler.java
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
 * Handler for authorizing incoming SOCKS BIND requests.
 *
 * <p>Called after successful SOCKS method negotiation (RFC 1928 §3) and
 * authentication (RFC 1929 or RFC 1961), when a BIND request
 * (RFC 1928 §4: CMD=0x02) is received.
 *
 * <p>Implementations can perform custom authorization logic — for
 * example, restricting which users may request BIND, limiting the
 * expected peer addresses, or applying rate limits.
 *
 * <p>The handler receives a {@link BindState} callback and must call
 * exactly one of {@link BindState#allow()} or
 * {@link BindState#deny(int)} when the decision is ready. The
 * decision may be made asynchronously.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see BindState
 * @see org.bluezoo.gumdrop.socks.SOCKSService#createBindHandler
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928#section-4">
 *      RFC 1928 §4</a>
 */
public interface BindHandler {

    /**
     * Called when a SOCKS BIND request has been received and
     * (if applicable) authenticated.
     *
     * <p>The request's DST.ADDR and DST.PORT indicate the expected
     * connecting peer (RFC 1928 §4). The implementation must call
     * {@link BindState#allow()} to permit the bind, or
     * {@link BindState#deny(int)} to reject it with a SOCKS reply
     * code.
     *
     * @param state the callback for communicating the decision
     * @param request the parsed SOCKS request (includes expected
     *                peer destination, version, userid, authenticated
     *                user)
     * @param clientEndpoint the client's endpoint (for address info)
     */
    void handleBind(BindState state, SOCKSRequest request,
                    Endpoint clientEndpoint);

}
