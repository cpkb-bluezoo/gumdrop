/*
 * ConnectUdpRequestHandler.java
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;

/**
 * A ready-to-use {@link HTTPRequestHandler} implementing RFC 9298
 * (Proxying UDP in HTTP): accepts a CONNECT-UDP request whose target is
 * approved by a {@link ConnectUdpPolicy}, and relays UDP datagrams
 * between the client and that target for the life of the request.
 *
 * <p>An {@link HTTPRequestHandlerFactory} returns an instance of this
 * class (constructed with a policy) for any request it wants handled as
 * CONNECT-UDP -- typically after checking {@code :method}/{@code
 * :protocol} itself, though this class also re-validates those and the
 * request path (RFC 9298 section 3's URI Template) before doing
 * anything with a UDP socket.
 *
 * <p>Works identically over HTTP/1.1, HTTP/2, and HTTP/3: {@link
 * HTTPResponseState#acceptConnectUdp} and {@link
 * HTTPRequestHandler#datagramReceived} are the only per-transport
 * mechanics this class relies on, both already implemented per
 * transport.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
public class ConnectUdpRequestHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(ConnectUdpRequestHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    private final ConnectUdpPolicy policy;
    private final long idleTimeoutMs;

    private ConnectUdpRelay relay;

    /**
     * Creates a handler using {@link ConnectUdpRelay#DEFAULT_IDLE_TIMEOUT_MS}.
     *
     * @param policy decides which resolved targets may be relayed to;
     *        must not be null (see {@link ConnectUdpPolicy}'s own
     *        documentation for why there is no permissive default)
     */
    public ConnectUdpRequestHandler(ConnectUdpPolicy policy) {
        this(policy, ConnectUdpRelay.DEFAULT_IDLE_TIMEOUT_MS);
    }

    /**
     * @param policy decides which resolved targets may be relayed to;
     *        must not be null
     * @param idleTimeoutMs closes the relay after this long with no
     *        datagrams relayed in either direction; 0 disables the timeout
     */
    public ConnectUdpRequestHandler(ConnectUdpPolicy policy, long idleTimeoutMs) {
        if (policy == null) {
            throw new IllegalArgumentException(L10N.getString("warn.connect_udp_missing_policy"));
        }
        this.policy = policy;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    @Override
    public void headers(final HTTPResponseState state, Headers headers) {
        if (!isConnectUdpRequest(state, headers)) {
            rejectRequest(state, 400);
            return;
        }
        if (!Capsule.capsuleProtocolEnabled(headers)) {
            LOGGER.warning(L10N.getString("warn.connect_udp_not_capsule"));
            rejectRequest(state, 400);
            return;
        }
        final ConnectUdpTarget target = ConnectUdpTarget.parse(headers.getValue(":path"));
        if (target == null) {
            LOGGER.warning(MessageFormat.format(
                    L10N.getString("warn.connect_udp_bad_target"), headers.getValue(":path")));
            rejectRequest(state, 400);
            return;
        }

        DNSResolver resolver = DNSResolver.forLoop(state.getSelectorLoop());
        resolver.resolve(target.getHost(), new ResolveCallback() {
            @Override
            public void onResolved(List<InetAddress> addresses) {
                for (InetAddress address : addresses) {
                    if (policy.isTargetAllowed(address, target.getPort())) {
                        accept(state, new InetSocketAddress(address, target.getPort()));
                        return;
                    }
                }
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(L10N.getString("log.connect_udp_target_denied"),
                            target.getHost(), Integer.valueOf(target.getPort())));
                }
                rejectRequest(state, 403);
            }

            @Override
            public void onError(String error) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("log.connect_udp_dns_failed"), target.getHost(), error));
                rejectRequest(state, 502);
            }
        });
    }

    /**
     * RFC 9298 section 3: HTTP/2 and HTTP/3 both send Extended CONNECT
     * ({@code :method: CONNECT}, {@code :protocol: connect-udp}), the
     * same shape RFC 8441 WebSocket uses. HTTP/1.1 has no {@code
     * :protocol} pseudo-header; RFC 9110 section 7.8 forbids Upgrade
     * over HTTP/2 or later, so HTTP/1.1 instead sends a literal {@code
     * Upgrade: connect-udp} request (typically {@code GET}, not {@code
     * CONNECT}) -- mirroring {@code Stream#isConnectUdpRequest}, which
     * this class's caller ({@link HTTPResponseState#acceptConnectUdp})
     * re-validates independently.
     */
    private static boolean isConnectUdpRequest(HTTPResponseState state, Headers headers) {
        if (state.getVersion().supportsMultiplexing()) {
            return "CONNECT".equals(headers.getMethod())
                    && "connect-udp".equalsIgnoreCase(headers.getValue(":protocol"));
        }
        return "connect-udp".equalsIgnoreCase(headers.getValue("upgrade"));
    }

    private void accept(HTTPResponseState state, InetSocketAddress resolvedTarget) {
        relay = new ConnectUdpRelay(state, idleTimeoutMs);
        try {
            relay.start(resolvedTarget);
        } catch (java.io.IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open CONNECT-UDP upstream socket", e);
            relay = null;
            rejectRequest(state, 502);
            return;
        }
        if (!state.acceptConnectUdp()) {
            relay.close();
            relay = null;
        }
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
        if (relay != null) {
            relay.receiveDatagram(data);
        }
    }

    @Override
    public void failed(HTTPResponseState state, Exception cause) {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }
}
