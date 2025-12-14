/*
 * ClusterMetrics.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet.session;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for the session cluster.
 *
 * <p>This class provides metrics for monitoring distributed session
 * replication across cluster nodes.
 *
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code cluster.nodes.active} - Number of active nodes in the cluster</li>
 *   <li>{@code cluster.sessions.replicated} - Sessions replicated to other nodes</li>
 *   <li>{@code cluster.sessions.received} - Sessions received from other nodes</li>
 *   <li>{@code cluster.sessions.passivated} - Sessions passivated (removed) via cluster</li>
 *   <li>{@code cluster.messages.sent} - Total messages sent to cluster</li>
 *   <li>{@code cluster.messages.received} - Total messages received from cluster</li>
 *   <li>{@code cluster.bytes.sent} - Total bytes sent to cluster</li>
 *   <li>{@code cluster.bytes.received} - Total bytes received from cluster</li>
 *   <li>{@code cluster.deltas.sent} - Delta updates sent (incremental replication)</li>
 *   <li>{@code cluster.deltas.received} - Delta updates received</li>
 *   <li>{@code cluster.fragments.sent} - Message fragments sent</li>
 *   <li>{@code cluster.fragments.received} - Message fragments received</li>
 *   <li>{@code cluster.errors.decrypt} - Decryption errors</li>
 *   <li>{@code cluster.errors.replay} - Replay attacks detected</li>
 *   <li>{@code cluster.errors.timestamp} - Timestamp validation failures</li>
 *   <li>{@code cluster.replication.duration} - Session replication duration in ms</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterMetrics {

    private static final String METER_NAME = "org.bluezoo.gumdrop.cluster";
    private static final String METER_VERSION = "0.4";

    // Node tracking
    private final LongUpDownCounter activeNodes;

    // Session counters
    private final LongCounter sessionsReplicated;
    private final LongCounter sessionsReceived;
    private final LongCounter sessionsPassivated;

    // Message counters
    private final LongCounter messagesSent;
    private final LongCounter messagesReceived;
    private final LongCounter bytesSent;
    private final LongCounter bytesReceived;

    // Delta replication counters
    private final LongCounter deltasSent;
    private final LongCounter deltasReceived;

    // Fragment counters
    private final LongCounter fragmentsSent;
    private final LongCounter fragmentsReceived;
    private final LongCounter fragmentsReassembled;
    private final LongCounter fragmentsTimedOut;

    // Error counters
    private final LongCounter decryptErrors;
    private final LongCounter replayErrors;
    private final LongCounter timestampErrors;

    // Histograms
    private final DoubleHistogram replicationDuration;

    /**
     * Creates cluster metrics using the given telemetry configuration.
     *
     * @param config the telemetry configuration
     */
    public ClusterMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, METER_VERSION);

        // Active nodes gauge
        this.activeNodes = meter.upDownCounterBuilder("cluster.nodes.active")
                .setDescription("Number of active nodes in the cluster")
                .setUnit("nodes")
                .build();

        // Session counters
        this.sessionsReplicated = meter.counterBuilder("cluster.sessions.replicated")
                .setDescription("Total sessions replicated to other nodes")
                .setUnit("sessions")
                .build();

        this.sessionsReceived = meter.counterBuilder("cluster.sessions.received")
                .setDescription("Total sessions received from other nodes")
                .setUnit("sessions")
                .build();

        this.sessionsPassivated = meter.counterBuilder("cluster.sessions.passivated")
                .setDescription("Total sessions passivated via cluster")
                .setUnit("sessions")
                .build();

        // Message counters
        this.messagesSent = meter.counterBuilder("cluster.messages.sent")
                .setDescription("Total messages sent to cluster")
                .setUnit("messages")
                .build();

        this.messagesReceived = meter.counterBuilder("cluster.messages.received")
                .setDescription("Total messages received from cluster")
                .setUnit("messages")
                .build();

        this.bytesSent = meter.counterBuilder("cluster.bytes.sent")
                .setDescription("Total bytes sent to cluster")
                .setUnit("bytes")
                .build();

        this.bytesReceived = meter.counterBuilder("cluster.bytes.received")
                .setDescription("Total bytes received from cluster")
                .setUnit("bytes")
                .build();

        // Delta replication counters
        this.deltasSent = meter.counterBuilder("cluster.deltas.sent")
                .setDescription("Total delta updates sent")
                .setUnit("deltas")
                .build();

        this.deltasReceived = meter.counterBuilder("cluster.deltas.received")
                .setDescription("Total delta updates received")
                .setUnit("deltas")
                .build();

        // Fragment counters
        this.fragmentsSent = meter.counterBuilder("cluster.fragments.sent")
                .setDescription("Total message fragments sent")
                .setUnit("fragments")
                .build();

        this.fragmentsReceived = meter.counterBuilder("cluster.fragments.received")
                .setDescription("Total message fragments received")
                .setUnit("fragments")
                .build();

        this.fragmentsReassembled = meter.counterBuilder("cluster.fragments.reassembled")
                .setDescription("Total fragmented messages successfully reassembled")
                .setUnit("messages")
                .build();

        this.fragmentsTimedOut = meter.counterBuilder("cluster.fragments.timed_out")
                .setDescription("Total fragment sets that timed out before completion")
                .setUnit("messages")
                .build();

        // Error counters
        this.decryptErrors = meter.counterBuilder("cluster.errors.decrypt")
                .setDescription("Total decryption errors")
                .setUnit("errors")
                .build();

        this.replayErrors = meter.counterBuilder("cluster.errors.replay")
                .setDescription("Total replay attacks detected")
                .setUnit("errors")
                .build();

        this.timestampErrors = meter.counterBuilder("cluster.errors.timestamp")
                .setDescription("Total timestamp validation failures")
                .setUnit("errors")
                .build();

        // Replication duration histogram
        this.replicationDuration = meter.histogramBuilder("cluster.replication.duration")
                .setDescription("Duration of session replication operations")
                .setUnit("ms")
                .setExplicitBuckets(0.5, 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000)
                .build();
    }

    // -- Node tracking --

    /**
     * Records a node joining the cluster.
     */
    public void recordNodeJoined() {
        activeNodes.add(1);
    }

    /**
     * Records a node leaving/expiring from the cluster.
     */
    public void recordNodeLeft() {
        activeNodes.add(-1);
    }

    // -- Session metrics --

    /**
     * Records a session replicated to the cluster.
     *
     * @param contextName the context name for attribution
     */
    public void recordSessionReplicated(String contextName) {
        Attributes attrs = Attributes.of(
                "context", contextName != null ? contextName : "unknown");
        sessionsReplicated.add(1, attrs);
    }

    /**
     * Records a session received from the cluster.
     *
     * @param contextName the context name for attribution
     */
    public void recordSessionReceived(String contextName) {
        Attributes attrs = Attributes.of(
                "context", contextName != null ? contextName : "unknown");
        sessionsReceived.add(1, attrs);
    }

    /**
     * Records a session passivated via cluster.
     *
     * @param contextName the context name for attribution
     */
    public void recordSessionPassivated(String contextName) {
        Attributes attrs = Attributes.of(
                "context", contextName != null ? contextName : "unknown");
        sessionsPassivated.add(1, attrs);
    }

    // -- Message metrics --

    /**
     * Records a message sent to the cluster.
     *
     * @param bytes the size of the message in bytes
     * @param messageType the type of message (replicate, delta, passivate, ping, fragment)
     */
    public void recordMessageSent(int bytes, String messageType) {
        Attributes attrs = Attributes.of("type", messageType);
        messagesSent.add(1, attrs);
        bytesSent.add(bytes, attrs);
    }

    /**
     * Records a message received from the cluster.
     *
     * @param bytes the size of the message in bytes
     * @param messageType the type of message
     */
    public void recordMessageReceived(int bytes, String messageType) {
        Attributes attrs = Attributes.of("type", messageType);
        messagesReceived.add(1, attrs);
        bytesReceived.add(bytes, attrs);
    }

    // -- Delta metrics --

    /**
     * Records a delta update sent.
     */
    public void recordDeltaSent() {
        deltasSent.add(1);
    }

    /**
     * Records a delta update received.
     */
    public void recordDeltaReceived() {
        deltasReceived.add(1);
    }

    // -- Fragment metrics --

    /**
     * Records a fragment sent.
     */
    public void recordFragmentSent() {
        fragmentsSent.add(1);
    }

    /**
     * Records a fragment received.
     */
    public void recordFragmentReceived() {
        fragmentsReceived.add(1);
    }

    /**
     * Records a fragmented message successfully reassembled.
     */
    public void recordFragmentReassembled() {
        fragmentsReassembled.add(1);
    }

    /**
     * Records a fragment set that timed out.
     */
    public void recordFragmentTimedOut() {
        fragmentsTimedOut.add(1);
    }

    // -- Error metrics --

    /**
     * Records a decryption error.
     */
    public void recordDecryptError() {
        decryptErrors.add(1);
    }

    /**
     * Records a replay attack detected.
     */
    public void recordReplayError() {
        replayErrors.add(1);
    }

    /**
     * Records a timestamp validation failure.
     */
    public void recordTimestampError() {
        timestampErrors.add(1);
    }

    // -- Duration metrics --

    /**
     * Records the duration of a replication operation.
     *
     * @param durationMs the duration in milliseconds
     * @param replicationType "full" or "delta"
     */
    public void recordReplicationDuration(double durationMs, String replicationType) {
        Attributes attrs = Attributes.of("type", replicationType);
        replicationDuration.record(durationMs, attrs);
    }

}

