/*
 * SOCKSRelay.java
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

import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * Bidirectional byte relay between a SOCKS client endpoint and an
 * upstream destination endpoint.
 *
 * <p>This relay implements the data forwarding phase after a successful
 * SOCKS CONNECT: once the server replies with success (RFC 1928 §4),
 * the proxy begins relaying data between client and upstream.
 *
 * <p>Both endpoints must be on the same SelectorLoop thread
 * (SelectorLoop affinity). This ensures the relay operates as a
 * simple in-thread data shuttle with no cross-thread synchronization.
 *
 * <p>The relay propagates transport-level backpressure: when one side
 * is slow to consume data, reading from the other side is paused via
 * {@link Endpoint#pauseRead()}, causing TCP flow control to propagate
 * backpressure to the sender.
 *
 * <p>An idle timeout closes the relay if no data flows in either
 * direction within the configured duration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928 §4</a>
 */
class SOCKSRelay {

    private static final Logger LOGGER =
            Logger.getLogger(SOCKSRelay.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    private final Endpoint clientEndpoint;
    private final SOCKSService service;
    private final SOCKSServerMetrics metrics;
    private final long idleTimeoutMs;

    private Endpoint upstreamEndpoint;
    private boolean clientDisconnected;
    private boolean upstreamDisconnected;
    private boolean closed;
    private long relayStartTimeMillis;

    private TimerHandle idleTimer;

    // Backpressure: track whether each direction is paused
    private boolean clientReadPaused;
    private boolean upstreamReadPaused;

    SOCKSRelay(Endpoint clientEndpoint, SOCKSService service,
               SOCKSServerMetrics metrics, long idleTimeoutMs) {
        this.clientEndpoint = clientEndpoint;
        this.service = service;
        this.metrics = metrics;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /**
     * Called when the upstream connection is established.
     */
    void upstreamConnected(Endpoint upstream) {
        this.upstreamEndpoint = upstream;
        this.relayStartTimeMillis = System.currentTimeMillis();
        if (metrics != null) {
            metrics.relayOpened();
        }
        setupBackpressure();
        resetIdleTimer();
    }

    /**
     * Called when data is received from the client.
     */
    void clientData(ByteBuffer data) {
        if (closed || upstreamEndpoint == null || !upstreamEndpoint.isOpen()) {
            return;
        }
        int bytes = data.remaining();
        resetIdleTimer();
        upstreamEndpoint.send(data);
        if (metrics != null && bytes > 0) {
            metrics.bytesRelayed(bytes, "upstream");
        }
    }

    /**
     * Called when data is received from the upstream.
     */
    void upstreamData(ByteBuffer data) {
        if (closed || !clientEndpoint.isOpen()) {
            return;
        }
        int bytes = data.remaining();
        resetIdleTimer();
        clientEndpoint.send(data);
        if (metrics != null && bytes > 0) {
            metrics.bytesRelayed(bytes, "downstream");
        }
    }

    /**
     * Called when the client disconnects.
     */
    void clientDisconnected() {
        clientDisconnected = true;
        closeRelay();
    }

    /**
     * Called when the upstream disconnects.
     */
    void upstreamDisconnected() {
        upstreamDisconnected = true;
        closeRelay();
    }

    private void setupBackpressure() {
        clientEndpoint.onWriteReady(new Runnable() {
            @Override
            public void run() {
                if (upstreamReadPaused && !closed) {
                    upstreamReadPaused = false;
                    upstreamEndpoint.resumeRead();
                }
            }
        });

        upstreamEndpoint.onWriteReady(new Runnable() {
            @Override
            public void run() {
                if (clientReadPaused && !closed) {
                    clientReadPaused = false;
                    clientEndpoint.resumeRead();
                }
            }
        });
    }

    private void resetIdleTimer() {
        if (idleTimeoutMs <= 0) {
            return;
        }
        if (idleTimer != null) {
            idleTimer.cancel();
        }
        idleTimer = clientEndpoint.scheduleTimer(idleTimeoutMs,
                new Runnable() {
                    @Override
                    public void run() {
                        if (!closed) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.fine(
                                        L10N.getString("log.relay_idle_timeout"));
                            }
                            closeRelay();
                        }
                    }
                });
    }

    private void closeRelay() {
        if (closed) {
            return;
        }
        closed = true;
        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }
        if (metrics != null && relayStartTimeMillis > 0) {
            double durationMs = (double) (System.currentTimeMillis()
                    - relayStartTimeMillis);
            metrics.relayClosed(durationMs);
        }
        if (!clientDisconnected && clientEndpoint.isOpen()) {
            clientEndpoint.close();
        }
        if (!upstreamDisconnected && upstreamEndpoint != null
                && upstreamEndpoint.isOpen()) {
            upstreamEndpoint.close();
        }
        service.releaseRelay();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("log.relay_closed"));
        }
    }

}
