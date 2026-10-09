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

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.ConnectUdpTarget;
import org.bluezoo.gumdrop.http.HttpMethod;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.SelectorLoop;

/**
 * A ready-to-use {@link HttpRequestHandler} implementing RFC 9298
 * (Proxying UDP in HTTP): accepts a CONNECT-UDP request whose target is
 * approved by a {@link ConnectUdpPolicy}, and relays UDP datagrams
 * between the client and that target for the life of the request.
 *
 * <p>An {@link HttpRequestHandler}
 * delegates the events of a request to an instance of this class (constructed
 * with the stream's response and a policy) for any request it
 * wants handled as CONNECT-UDP -- typically after checking {@code
 * :method}/{@code :protocol} itself, though this class also re-validates
 * those and the request path (RFC 9298 section 3's URI Template) before doing
 * anything with a UDP socket.
 *
 * <p>Works identically over HTTP/1.1, HTTP/2, and HTTP/3: {@link
 * HttpResponse#acceptConnectUdp} and {@link
 * HttpRequestHandler#datagramReceived} are the only per-transport
 * mechanics this class relies on, both already implemented per
 * transport.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
public class ConnectUdpRequestHandler extends DefaultHttpRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(ConnectUdpRequestHandler.class.getName());

    private EventLogger events() {
        // a response with no loop, as in tests, reports through a configuration of its own
        SelectorLoop loop = response.getSelectorLoop();
        TelemetryConfig telemetry = loop != null ? loop.getTelemetryConfig() : new TelemetryConfig();
        return telemetry.getLogger(ConnectUdpRequestHandler.class, L10N);
    }
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    private final HttpResponse response;
    private final ConnectUdpPolicy policy;
    private final long idleTimeoutMs;

    private ConnectUdpRelay relay;

    /**
     * Creates a handler using {@link ConnectUdpRelay#DEFAULT_IDLE_TIMEOUT_MS}.
     *
     * @param response the response of the stream carrying the request
     * @param policy decides which resolved targets may be relayed to;
     *        must not be null (see {@link ConnectUdpPolicy}'s own
     *        documentation for why there is no permissive default)
     */
    public ConnectUdpRequestHandler(HttpResponse response, ConnectUdpPolicy policy) {
        this(response, policy, ConnectUdpRelay.DEFAULT_IDLE_TIMEOUT_MS);
    }

    /**
     * @param response the response of the stream carrying the request
     * @param policy decides which resolved targets may be relayed to;
     *        must not be null
     * @param idleTimeoutMs closes the relay after this long with no
     *        datagrams relayed in either direction; 0 disables the timeout
     */
    public ConnectUdpRequestHandler(HttpResponse response, ConnectUdpPolicy policy,
            long idleTimeoutMs) {
        if (policy == null) {
            throw new IllegalArgumentException(L10N.getString("warn.connect_udp_missing_policy"));
        }
        this.response = response;
        this.policy = policy;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    private HttpMethod requestMethod;
    private String protocol;
    private String path;
    private String upgrade;
    private String capsuleProtocol;

    @Override
    public void method(HttpMethod method) {
        requestMethod = method;
    }

    @Override
    public void protocol(ByteBuffer protocol) {
        this.protocol = octetString(protocol);
    }

    @Override
    public void target(ByteBuffer target) {
        path = octetString(target);
    }

    @Override
    public void header(String name, ByteBuffer value) {
        if ("upgrade".equalsIgnoreCase(name)) {
            upgrade = octetString(value);
        } else if ("capsule-protocol".equalsIgnoreCase(name)) {
            capsuleProtocol = octetString(value);
        }
    }

    private static String octetString(ByteBuffer b) {
        byte[] octets = new byte[b.remaining()];
        b.duplicate().get(octets);
        return new String(octets, StandardCharsets.ISO_8859_1);
    }

    @Override
    public void endHeaders() {
        if (!isConnectUdpRequest()) {
            rejectRequest(400);
            return;
        }
        if (!Capsule.capsuleProtocolEnabled(capsuleProtocol)) {
            events().warn("warn.connect_udp_not_capsule").emit();
            rejectRequest(400);
            return;
        }
        final ConnectUdpTarget target = ConnectUdpTarget.parse(path);
        if (target == null) {
            events().warn("warn.connect_udp_bad_target").attr("path", path).emit();
            rejectRequest(400);
            return;
        }

        DnsResolver resolver = DnsResolver.forLoop(response.getSelectorLoop());
        resolver.resolve(target.getHost(), new ResolveCallback() {
            @Override
            public void onResolved(List<InetAddress> addresses) {
                for (InetAddress address : addresses) {
                    if (policy.isTargetAllowed(address, target.getPort())) {
                        accept(new InetSocketAddress(address, target.getPort()));
                        return;
                    }
                }
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(L10N.getString("log.connect_udp_target_denied"),
                            target.getHost(), Integer.valueOf(target.getPort())));
                }
                rejectRequest(403);
            }

            @Override
            public void onError(String error) {
                events().warn("log.connect_udp_dns_failed")
                        .attr("host", target.getHost())
                        .attr("error", error).emit();
                rejectRequest(502);
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
     * this class's caller ({@link HttpResponse#acceptConnectUdp})
     * re-validates independently.
     */
    private boolean isConnectUdpRequest() {
        if (response.getVersion().supportsMultiplexing()) {
            return HttpMethod.CONNECT.equals(requestMethod)
                    && "connect-udp".equalsIgnoreCase(protocol);
        }
        return "connect-udp".equalsIgnoreCase(upgrade);
    }

    private void accept(InetSocketAddress resolvedTarget) {
        relay = new ConnectUdpRelay(response, idleTimeoutMs);
        try {
            relay.start(resolvedTarget);
        } catch (java.io.IOException e) {
            events().warn("warn.connect_udp_upstream_open_failed").thrown(e).emit();
            relay = null;
            rejectRequest(502);
            return;
        }
        if (!response.acceptConnectUdp()) {
            relay.close();
            relay = null;
        }
    }

    private void rejectRequest(int statusCode) {
        response.status(statusCode);
        response.endMessage();
    }

    @Override
    public boolean wantsDatagrams() {
        return true;
    }

    @Override
    public void datagramReceived(HttpResponse response, ByteBuffer data) {
        if (relay != null) {
            relay.receiveDatagram(data);
        }
    }

    @Override
    public void failed(Exception cause) {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }
}
