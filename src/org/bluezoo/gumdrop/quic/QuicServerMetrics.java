/*
 * QuicServerMetrics.java
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

package org.bluezoo.gumdrop.quic;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * Aggregate OpenTelemetry metrics of a QUIC server: connections, packets
 * and bytes in each direction, losses, and the packets the engine sends
 * without a connection (Retry, Version Negotiation, stateless reset),
 * with handshake duration and round-trip time distributions.
 *
 * <p>These carry no channel tag, so they reach every exporter and the JMX
 * bridge as the other servers' metrics do. The per-packet qlog events do
 * not: they go only to the qlog exporter.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicServerMetrics {

    /** The instrumentation scope name. */
    public static final String METER_NAME = "org.bluezoo.gumdrop.quic";

    private final LongCounter connections;
    private final LongUpDownCounter activeConnections;
    private final LongCounter packetsSent;
    private final LongCounter packetsReceived;
    private final LongCounter bytesSent;
    private final LongCounter bytesReceived;
    private final LongCounter packetsLost;
    private final LongCounter retryPackets;
    private final LongCounter versionNegotiationPackets;
    private final LongCounter statelessResets;
    private final DoubleHistogram handshakeDuration;
    private final DoubleHistogram rtt;

    /**
     * Creates the instruments on the telemetry configuration's meter.
     *
     * @param config the telemetry configuration
     */
    public QuicServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);
        connections = meter.counterBuilder("quic.server.connections")
                .setDescription("Total number of QUIC connections accepted")
                .setUnit("connections").build();
        activeConnections = meter.upDownCounterBuilder("quic.server.active_connections")
                .setDescription("Number of active QUIC connections")
                .setUnit("connections").build();
        packetsSent = meter.counterBuilder("quic.server.packets.sent")
                .setDescription("QUIC packets sent")
                .setUnit("packets").build();
        packetsReceived = meter.counterBuilder("quic.server.packets.received")
                .setDescription("QUIC packets received and authenticated")
                .setUnit("packets").build();
        bytesSent = meter.counterBuilder("quic.server.bytes.sent")
                .setDescription("Bytes of QUIC packets sent")
                .setUnit("By").build();
        bytesReceived = meter.counterBuilder("quic.server.bytes.received")
                .setDescription("Bytes of QUIC packets received and authenticated")
                .setUnit("By").build();
        packetsLost = meter.counterBuilder("quic.server.packets.lost")
                .setDescription("QUIC packets declared lost")
                .setUnit("packets").build();
        retryPackets = meter.counterBuilder("quic.server.retry_packets")
                .setDescription("Retry packets sent")
                .setUnit("packets").build();
        versionNegotiationPackets = meter.counterBuilder("quic.server.version_negotiation_packets")
                .setDescription("Version Negotiation packets sent")
                .setUnit("packets").build();
        statelessResets = meter.counterBuilder("quic.server.stateless_resets")
                .setDescription("Stateless Reset packets sent")
                .setUnit("packets").build();
        handshakeDuration = meter.histogramBuilder("quic.server.handshake.duration")
                .setDescription("Time to complete the QUIC handshake")
                .setUnit("ms")
                .setExplicitBuckets(1, 5, 10, 25, 50, 100, 250, 500, 1000, 5000)
                .build();
        rtt = meter.histogramBuilder("quic.server.rtt")
                .setDescription("Round-trip time samples")
                .setUnit("ms")
                .setExplicitBuckets(0.1, 0.5, 1, 5, 10, 25, 50, 100, 250, 500, 1000)
                .build();
    }

    void connectionOpened() {
        connections.add(1);
        activeConnections.add(1);
    }

    void connectionClosed() {
        activeConnections.add(-1);
    }

    void packetSent(int bytes) {
        packetsSent.add(1);
        bytesSent.add(bytes);
    }

    void packetReceived(int bytes) {
        packetsReceived.add(1);
        bytesReceived.add(bytes);
    }

    void packetLost() {
        packetsLost.add(1);
    }

    void retrySent() {
        retryPackets.add(1);
    }

    void versionNegotiationSent() {
        versionNegotiationPackets.add(1);
    }

    void statelessResetSent() {
        statelessResets.add(1);
    }

    void handshakeCompleted(double millis) {
        handshakeDuration.record(millis);
    }

    /** Records a round-trip time sample given in microseconds. */
    void rttSample(long micros) {
        rtt.record(micros / 1000.0);
    }
}
