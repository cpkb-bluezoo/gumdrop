/*
 * ConnectIpRequestHandler.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A ready-to-use {@link HTTPRequestHandler} implementing RFC 9484
 * (Proxying IP in HTTP): accepts a CONNECT-IP request whose target scope
 * is approved by a {@link ConnectIpPolicy}, then hands the accepted
 * tunnel to an {@link IpPacketHandler} for the life of the request --
 * unlike {@link ConnectUdpRequestHandler}, this class does no forwarding
 * of its own (see {@link IpPacketHandler}'s own documentation for why).
 *
 * <p>An {@link HTTPRequestHandlerFactory} returns an instance of this
 * class (constructed with a policy and a packet handler) for any request
 * it wants handled as CONNECT-IP -- typically after checking {@code
 * :method}/{@code :protocol} itself, though this class also re-validates
 * those and the request path (RFC 9484 section 3's URI Template) before
 * doing anything else.
 *
 * <p>Works identically over HTTP/1.1, HTTP/2, and HTTP/3: {@link
 * HTTPResponseState#acceptConnectIp} and {@link
 * HTTPRequestHandler#datagramReceived}/{@link
 * HTTPRequestHandler#capsuleReceived} are the only per-transport
 * mechanics this class relies on, both already implemented per
 * transport.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
public class ConnectIpRequestHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(ConnectIpRequestHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    private final ConnectIpPolicy policy;
    private final IpPacketHandler packetHandler;

    private ConnectIpSession session;

    /**
     * @param policy decides which request target scopes may be
     *        accepted; must not be null (see {@link ConnectIpPolicy}'s
     *        own documentation for why there is no permissive default)
     * @param packetHandler the forwarding backend for accepted tunnels;
     *        must not be null
     */
    public ConnectIpRequestHandler(ConnectIpPolicy policy, IpPacketHandler packetHandler) {
        if (policy == null) {
            throw new IllegalArgumentException(L10N.getString("warn.connect_ip_missing_policy"));
        }
        if (packetHandler == null) {
            throw new IllegalArgumentException(L10N.getString("warn.connect_ip_missing_handler"));
        }
        this.policy = policy;
        this.packetHandler = packetHandler;
    }

    @Override
    public void headers(HTTPResponseState state, Headers headers) {
        if (!isConnectIpRequest(state, headers)) {
            rejectRequest(state, 400);
            return;
        }
        if (!Capsule.capsuleProtocolEnabled(headers)) {
            LOGGER.warning(L10N.getString("warn.connect_ip_not_capsule"));
            rejectRequest(state, 400);
            return;
        }
        ConnectIpTarget target = ConnectIpTarget.parse(headers.getValue(":path"));
        if (target == null) {
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.connect_ip_bad_target"), headers.getValue(":path")));
            rejectRequest(state, 400);
            return;
        }
        if (!policy.isRequestAllowed(target)) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(L10N.getString("log.connect_ip_target_denied"),
                        target.getTarget(), target.getIpProto()));
            }
            rejectRequest(state, 403);
            return;
        }
        if (!state.acceptConnectIp()) {
            return;
        }
        session = new ConnectIpSession(state);
        packetHandler.opened(session);
    }

    /**
     * RFC 9484 section 4.4/4.2: HTTP/2 and HTTP/3 send Extended CONNECT
     * ({@code :method: CONNECT}, {@code :protocol: connect-ip}); HTTP/1.1
     * has no {@code :protocol} pseudo-header and instead sends a literal
     * {@code Upgrade: connect-ip} request -- the same per-version split
     * {@link ConnectUdpRequestHandler} uses for RFC 9298.
     */
    private static boolean isConnectIpRequest(HTTPResponseState state, Headers headers) {
        if (state.getVersion().supportsMultiplexing()) {
            return "CONNECT".equals(headers.getMethod())
                    && "connect-ip".equalsIgnoreCase(headers.getValue(":protocol"));
        }
        return "connect-ip".equalsIgnoreCase(headers.getValue("upgrade"));
    }

    private void rejectRequest(HTTPResponseState state, int statusCode) {
        Headers response = new Headers();
        response.status(HTTPStatus.fromCode(statusCode));
        state.headers(response);
        state.complete();
    }

    @Override
    public boolean wantsDatagrams() {
        return true;
    }

    @Override
    public void datagramReceived(HTTPResponseState state, ByteBuffer data) {
        if (session == null) {
            return;
        }
        HttpDatagramContext decoded = HttpDatagramContext.decode(data);
        if (decoded == null || decoded.getContextId() != HttpDatagramContext.REGISTERED_CONTEXT_ID) {
            return;
        }
        packetHandler.packetReceived(session, decoded.getPayload());
    }

    @Override
    public void capsuleReceived(HTTPResponseState state, long type, ByteBuffer value) {
        if (session == null || type != ConnectIpAddress.TYPE_ADDRESS_REQUEST) {
            return;
        }
        List<ConnectIpAddress> requested = ConnectIpAddress.decodeList(value);
        if (requested == null) {
            LOGGER.warning(L10N.getString("log.connect_ip_malformed_address_capsule"));
            return;
        }
        packetHandler.addressRequested(session, requested);
    }

    @Override
    public void requestComplete(HTTPResponseState state) {
        if (session != null) {
            ConnectIpSession closed = session;
            session = null;
            packetHandler.closed(closed);
        }
    }

    @Override
    public void failed(HTTPResponseState state, Exception cause) {
        if (session != null) {
            ConnectIpSession failed = session;
            session = null;
            packetHandler.failed(failed, cause);
        }
    }
}
