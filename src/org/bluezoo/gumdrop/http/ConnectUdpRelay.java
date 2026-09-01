/*
 * ConnectUdpRelay.java
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.UDPEndpoint;
import org.bluezoo.gumdrop.UDPTransportFactory;

/**
 * Relays RFC 9298 CONNECT-UDP datagrams between one HTTP request/response
 * exchange and one fixed UDP target -- the version-agnostic core {@link
 * ConnectUdpRequestHandler} drives, shared by HTTP/1.1, HTTP/2, and
 * HTTP/3 alike, since all three ultimately deliver Context ID-prefixed
 * HTTP Datagram payloads to {@link HTTPRequestHandler#datagramReceived}
 * the same way (RFC 9297; RFC 9298 section 5's Context ID layer is
 * {@link HttpDatagramContext}).
 *
 * <p>Unlike {@code SOCKSUDPRelay} (RFC 1928 section 7's UDP ASSOCIATE,
 * the closest existing pattern in this codebase), CONNECT-UDP names
 * exactly one target in the request path itself -- there is no
 * per-datagram destination to track, and no separate client-facing UDP
 * endpoint: the "client-facing" side of this relay is the HTTP
 * request/response exchange itself. Only one UDP endpoint is needed,
 * connected directly to the target (RFC 9298 section 1 assumes a 1:1
 * relationship between a CONNECT-UDP request and a UDP target), which
 * also means the kernel discards any reply not actually from that target
 * for free.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
final class ConnectUdpRelay {

    private static final Logger LOGGER = Logger.getLogger(ConnectUdpRelay.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    /**
     * Idle timeout: no datagrams relayed in either direction for this
     * long closes the relay. RFC 9298 sets no specific value; bounding
     * relay lifetime this way (rather than relying solely on the HTTP
     * request/response exchange's own close/error notification, which a
     * long-lived, otherwise-silent tunnel may not reliably deliver)
     * mirrors {@code SOCKSUDPRelay}'s identical use of an idle timer as
     * its primary bound on relay lifetime.
     */
    static final long DEFAULT_IDLE_TIMEOUT_MS = 5L * 60L * 1000L;

    private final HTTPResponseState state;
    private final long idleTimeoutMs;

    private UDPEndpoint upstream;
    private boolean closed;
    private TimerHandle idleTimer;

    ConnectUdpRelay(HTTPResponseState state) {
        this(state, DEFAULT_IDLE_TIMEOUT_MS);
    }

    ConnectUdpRelay(HTTPResponseState state, long idleTimeoutMs) {
        this.state = state;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /**
     * Opens the upstream UDP endpoint connected to {@code target}.
     *
     * @param target the resolved target address
     * @throws IOException if the UDP socket cannot be opened
     */
    void start(InetSocketAddress target) throws IOException {
        UDPTransportFactory factory = new UDPTransportFactory();
        factory.start();
        upstream = factory.connect(target.getAddress(), target.getPort(),
                new UpstreamHandler(), state.getSelectorLoop());
        resetIdleTimer();
    }

    /**
     * Delivers an HTTP Datagram payload received on the request (already
     * stripped of framing -- H3 Datagram, or Capsule Protocol -- by the
     * caller, but not yet of its RFC 9298 section 5 Context ID). Context
     * ID 0 (the registered payload for this request) is relayed to the
     * UDP target; any other Context ID is ignored (RFC 9298 section 5:
     * an unrecognised Context ID must not be treated as an error) since
     * this relay never registers any other.
     *
     * @param data the HTTP Datagram payload
     */
    void receiveDatagram(ByteBuffer data) {
        if (closed || upstream == null) {
            return;
        }
        HttpDatagramContext decoded = HttpDatagramContext.decode(data);
        if (decoded == null || decoded.getContextId() != HttpDatagramContext.REGISTERED_CONTEXT_ID) {
            return;
        }
        resetIdleTimer();
        upstream.send(decoded.getPayload());
    }

    /**
     * Closes the upstream UDP endpoint. Idempotent.
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
        if (upstream != null && upstream.isOpen()) {
            upstream.close();
        }
    }

    private void resetIdleTimer() {
        if (idleTimeoutMs <= 0) {
            return;
        }
        if (idleTimer != null) {
            idleTimer.cancel();
        }
        idleTimer = state.scheduleTimer(idleTimeoutMs, new Runnable() {
            @Override
            public void run() {
                if (!closed) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine(L10N.getString("log.connect_udp_idle_timeout"));
                    }
                    close();
                    state.cancel();
                }
            }
        });
    }

    /**
     * Receives reply datagrams from the UDP target and encapsulates them
     * as Context ID 0 HTTP Datagrams on the request/response exchange.
     */
    private class UpstreamHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void receive(ByteBuffer data) {
            if (closed) {
                return;
            }
            resetIdleTimer();
            ByteBuffer encoded = HttpDatagramContext.encode(
                    HttpDatagramContext.REGISTERED_CONTEXT_ID, data);
            state.sendDatagram(encoded);
        }

        @Override
        public void disconnected() {
            if (!closed) {
                close();
            }
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            if (!closed) {
                LOGGER.log(Level.WARNING,
                        MessageFormat.format(L10N.getString("log.connect_udp_upstream_error"), cause),
                        cause);
                close();
            }
        }
    }
}
