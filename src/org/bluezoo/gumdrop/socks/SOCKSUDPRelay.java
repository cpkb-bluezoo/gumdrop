/*
 * SOCKSUDPRelay.java
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

package org.bluezoo.gumdrop.socks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UDPEndpoint;
import org.bluezoo.gumdrop.UDPTransportFactory;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;

import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Manages a single SOCKS5 UDP ASSOCIATE session (RFC 1928 §7).
 *
 * <p>Each association owns two UDP endpoints:
 * <ul>
 *   <li><strong>Client-facing</strong> — bound to an ephemeral port
 *       (BND.PORT in the SOCKS5 reply). Receives datagrams from the
 *       client, validates the source IP, parses the RFC 1928 §7
 *       header, and forwards the payload upstream.</li>
 *   <li><strong>Upstream</strong> — bound to an ephemeral port.
 *       Sends datagrams to arbitrary remote destinations via
 *       {@code sendTo()} and receives responses, encapsulating
 *       them with the §7 header before forwarding to the client.</li>
 * </ul>
 *
 * <p>Both endpoints are registered on the same {@link SelectorLoop}
 * as the TCP control connection (affinity), ensuring all state access
 * is single-threaded.
 *
 * <p>The association terminates when the TCP control connection
 * closes (RFC 1928 §7: "A UDP association terminates when the TCP
 * connection that the UDP ASSOCIATE request arrived on terminates").
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928#section-7">
 *      RFC 1928 §7</a>
 */
class SOCKSUDPRelay {

    private static final Logger LOGGER =
            Logger.getLogger(SOCKSUDPRelay.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    private final Endpoint tcpControlEndpoint;
    private final SOCKSService service;
    private final SOCKSServerMetrics metrics;
    private final long idleTimeoutMs;
    private final InetAddress expectedClientAddress;
    private final SelectorLoop selectorLoop;

    private UDPEndpoint clientFacingEndpoint;
    private UDPEndpoint upstreamEndpoint;
    private InetSocketAddress clientDatagramAddress;

    private boolean closed;
    private long startTimeMillis;
    private TimerHandle idleTimer;

    /**
     * Creates a new UDP ASSOCIATE relay.
     *
     * @param tcpEndpoint the TCP control connection endpoint
     * @param service the SOCKS service
     * @param metrics the server metrics, or null
     * @param idleTimeoutMs idle timeout in milliseconds
     * @param expectedClientAddress expected source IP for client
     *        datagrams (from the UDP ASSOCIATE request DST.ADDR, or
     *        the TCP connection's remote address if DST.ADDR was
     *        0.0.0.0)
     */
    SOCKSUDPRelay(Endpoint tcpEndpoint, SOCKSService service,
                  SOCKSServerMetrics metrics, long idleTimeoutMs,
                  InetAddress expectedClientAddress) {
        this.tcpControlEndpoint = tcpEndpoint;
        this.service = service;
        this.metrics = metrics;
        this.idleTimeoutMs = idleTimeoutMs;
        this.expectedClientAddress = expectedClientAddress;
        this.selectorLoop = tcpEndpoint.getSelectorLoop();
    }

    /**
     * Binds the two UDP endpoints and starts the relay.
     *
     * @return the client-facing bound address (BND.ADDR:BND.PORT for
     *         the SOCKS5 reply)
     * @throws IOException if the UDP ports cannot be bound
     */
    InetSocketAddress start() throws IOException {
        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();

        clientFacingEndpoint = factory.createServerEndpoint(
                null, 0, new ClientFacingHandler(), selectorLoop);

        upstreamEndpoint = factory.createServerEndpoint(
                null, 0, new UpstreamHandler(), selectorLoop);

        startTimeMillis = System.currentTimeMillis();
        if (metrics != null) {
            metrics.udpAssociationOpened();
        }

        resetIdleTimer();

        InetSocketAddress boundAddress =
                (InetSocketAddress) clientFacingEndpoint.getLocalAddress();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.udp_relay_opened"),
                    boundAddress.getPort()));
        }

        return boundAddress;
    }

    /**
     * Closes both UDP endpoints and releases the relay slot.
     * Called when the TCP control connection closes.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }

        if (clientFacingEndpoint != null
                && clientFacingEndpoint.isOpen()) {
            clientFacingEndpoint.close();
        }
        if (upstreamEndpoint != null && upstreamEndpoint.isOpen()) {
            upstreamEndpoint.close();
        }

        if (metrics != null && startTimeMillis > 0) {
            double durationMs = (double) (System.currentTimeMillis()
                    - startTimeMillis);
            metrics.udpAssociationClosed(durationMs);
        }

        service.releaseRelay();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("log.udp_relay_closed"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Idle timeout
    // ═══════════════════════════════════════════════════════════════════

    private void resetIdleTimer() {
        if (idleTimeoutMs <= 0) {
            return;
        }
        if (idleTimer != null) {
            idleTimer.cancel();
        }
        idleTimer = tcpControlEndpoint.scheduleTimer(idleTimeoutMs,
                new Runnable() {
                    @Override
                    public void run() {
                        if (!closed) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine(L10N.getString(
                                        "log.relay_idle_timeout"));
                            }
                            close();
                        }
                    }
                });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Client-facing UDP handler
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Receives datagrams from the SOCKS client, validates the source
     * IP (RFC 1928 §7), parses the UDP request header, and forwards
     * the payload to the upstream endpoint.
     */
    private class ClientFacingHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint endpoint) {
            // No action needed — relay is managed by SOCKSUDPRelay
        }

        @Override
        public void receive(ByteBuffer data) {
            if (closed) {
                return;
            }

            // UDPEndpoint sets remoteAddress before calling receive()
            InetSocketAddress source =
                    (InetSocketAddress) clientFacingEndpoint
                            .getRemoteAddress();

            // RFC 1928 §7: validate client source IP
            if (!source.getAddress().equals(expectedClientAddress)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("log.udp_invalid_source"),
                            source.getAddress(),
                            expectedClientAddress));
                }
                return;
            }

            SOCKSUDPHeader.Parsed parsed = SOCKSUDPHeader.parse(data);
            if (parsed == null) {
                return;
            }

            // RFC 1928 §7: drop fragmented datagrams
            if (parsed.frag != SOCKS5_UDP_FRAG_STANDALONE) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("log.udp_fragment_dropped"),
                            parsed.frag & 0xFF));
                }
                return;
            }

            clientDatagramAddress = source;
            resetIdleTimer();

            // Extract payload from after the header
            int payloadLen = data.limit() - parsed.dataOffset;
            if (payloadLen <= 0) {
                return;
            }
            ByteBuffer payload = data.duplicate();
            payload.position(parsed.dataOffset);

            if (parsed.hostname != null) {
                resolveAndForward(parsed, payload);
            } else if (parsed.address != null) {
                InetSocketAddress dest = new InetSocketAddress(
                        parsed.address, parsed.port);
                forwardUpstream(dest, payload);
            }
        }

        @Override
        public void disconnected() {
            if (!closed) {
                close();
            }
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // UDP relay does not use TLS at the datagram layer
        }

        @Override
        public void error(Exception cause) {
            if (!closed) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("log.connection_error"), cause);
                close();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Upstream UDP handler
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Receives response datagrams from remote hosts, encapsulates
     * them with the RFC 1928 §7 header, and forwards to the client.
     */
    private class UpstreamHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint endpoint) {
            // No action needed — relay is managed by SOCKSUDPRelay
        }

        @Override
        public void receive(ByteBuffer data) {
            if (closed || clientDatagramAddress == null) {
                return;
            }

            // UDPEndpoint sets remoteAddress before calling receive()
            InetSocketAddress source =
                    (InetSocketAddress) upstreamEndpoint
                            .getRemoteAddress();

            resetIdleTimer();

            int bytes = data.remaining();

            // RFC 1928 §7: encapsulate with header
            ByteBuffer encapsulated =
                    SOCKSUDPHeader.encode(source, data);

            clientFacingEndpoint.sendTo(
                    encapsulated, clientDatagramAddress);

            if (metrics != null && bytes > 0) {
                metrics.bytesRelayed(bytes, "downstream");
            }
        }

        @Override
        public void disconnected() {
            if (!closed) {
                close();
            }
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // UDP relay does not use TLS at the datagram layer
        }

        @Override
        public void error(Exception cause) {
            if (!closed) {
                LOGGER.log(Level.WARNING,
                        L10N.getString("log.connection_error"), cause);
                close();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DNS resolution for DOMAINNAME destinations
    // ═══════════════════════════════════════════════════════════════════

    private void resolveAndForward(final SOCKSUDPHeader.Parsed parsed,
                                   final ByteBuffer payload) {
        DNSResolver resolver = DNSResolver.forLoop(selectorLoop);
        resolver.resolve(parsed.hostname, new ResolveCallback() {
            @Override
            public void onResolved(List<InetAddress> addresses) {
                InetAddress resolved = addresses.get(0);
                InetSocketAddress dest = new InetSocketAddress(
                        resolved, parsed.port);
                forwardUpstream(dest, payload);
            }

            @Override
            public void onError(String error) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(MessageFormat.format(
                            L10N.getString("log.dns_resolution_failed"),
                            parsed.hostname, error));
                }
                // RFC 1928 §7: silently drop datagrams we cannot relay
            }
        });
    }

    private void forwardUpstream(InetSocketAddress dest,
                                 ByteBuffer payload) {
        if (closed) {
            return;
        }

        // RFC 1928 §7: destination filtering — silently drop
        if (!service.isDestinationAllowed(dest.getAddress())) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("log.destination_blocked"),
                        dest.getAddress()));
            }
            if (metrics != null) {
                metrics.destinationBlocked();
            }
            return;
        }

        int bytes = payload.remaining();
        upstreamEndpoint.sendTo(payload, dest);

        if (metrics != null && bytes > 0) {
            metrics.bytesRelayed(bytes, "upstream");
        }
    }

}
