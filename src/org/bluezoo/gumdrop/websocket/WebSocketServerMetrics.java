/*
 * WebSocketServerMetrics.java
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

package org.bluezoo.gumdrop.websocket;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.LongUpDownCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;

/**
 * OpenTelemetry metrics for WebSocket servers.
 *
 * <p>This class provides standardized WebSocket server metrics for
 * monitoring connection and message activity.
 *
 * <p>Metrics provided:
 * <ul>
 *   <li>{@code websocket.server.connections} - Total WebSocket connections</li>
 *   <li>{@code websocket.server.active_connections} - Active connections</li>
 *   <li>{@code websocket.server.session.duration} - Session duration</li>
 *   <li>{@code websocket.server.messages.received} - Messages received (by type)</li>
 *   <li>{@code websocket.server.messages.sent} - Messages sent (by type)</li>
 *   <li>{@code websocket.server.frames.received} - Frames received</li>
 *   <li>{@code websocket.server.frames.sent} - Frames sent</li>
 *   <li>{@code websocket.server.errors} - Connection errors</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketServerMetrics {

    private static final String METER_NAME =
            "org.bluezoo.gumdrop.websocket";
    private static final String ATTR_MESSAGE_TYPE =
            "websocket.message.type";

    private final LongCounter connectionCounter;
    private final LongUpDownCounter activeConnections;
    private final DoubleHistogram sessionDuration;

    private final LongCounter messagesReceived;
    private final LongCounter messagesSent;

    private final LongCounter framesReceived;
    private final LongCounter framesSent;

    private final LongCounter errors;

    /**
     * Creates WebSocket server metrics using the given telemetry
     * configuration.
     *
     * @param config the telemetry configuration
     */
    public WebSocketServerMetrics(TelemetryConfig config) {
        Meter meter = config.getMeter(METER_NAME, Gumdrop.VERSION);

        this.connectionCounter = meter.counterBuilder(
                        "websocket.server.connections")
                .setDescription("Total WebSocket connections")
                .setUnit("connections")
                .build();

        this.activeConnections = meter.upDownCounterBuilder(
                        "websocket.server.active_connections")
                .setDescription("Active WebSocket connections")
                .setUnit("connections")
                .build();

        this.sessionDuration = meter.histogramBuilder(
                        "websocket.server.session.duration")
                .setDescription("WebSocket session duration")
                .setUnit("ms")
                .setExplicitBuckets(1000, 10000, 60000, 300000,
                        600000, 1800000, 3600000)
                .build();

        this.messagesReceived = meter.counterBuilder(
                        "websocket.server.messages.received")
                .setDescription("WebSocket messages received")
                .setUnit("messages")
                .build();

        this.messagesSent = meter.counterBuilder(
                        "websocket.server.messages.sent")
                .setDescription("WebSocket messages sent")
                .setUnit("messages")
                .build();

        this.framesReceived = meter.counterBuilder(
                        "websocket.server.frames.received")
                .setDescription("WebSocket frames received")
                .setUnit("frames")
                .build();

        this.framesSent = meter.counterBuilder(
                        "websocket.server.frames.sent")
                .setDescription("WebSocket frames sent")
                .setUnit("frames")
                .build();

        this.errors = meter.counterBuilder(
                        "websocket.server.errors")
                .setDescription("WebSocket connection errors")
                .setUnit("errors")
                .build();
    }

    /**
     * Records a new WebSocket connection opened.
     */
    public void connectionOpened() {
        connectionCounter.add(1);
        activeConnections.add(1);
    }

    /**
     * Records a WebSocket connection closed.
     *
     * @param durationMs the session duration in milliseconds
     * @param closeCode the WebSocket close code (e.g. 1000, 1001)
     */
    public void connectionClosed(double durationMs, int closeCode) {
        activeConnections.add(-1);
        sessionDuration.record(durationMs);
    }

    /**
     * Records a text message received.
     */
    public void textMessageReceived() {
        messagesReceived.add(1,
                Attributes.of(ATTR_MESSAGE_TYPE, "text"));
    }

    /**
     * Records a binary message received.
     */
    public void binaryMessageReceived() {
        messagesReceived.add(1,
                Attributes.of(ATTR_MESSAGE_TYPE, "binary"));
    }

    /**
     * Records a text message sent.
     */
    public void textMessageSent() {
        messagesSent.add(1,
                Attributes.of(ATTR_MESSAGE_TYPE, "text"));
    }

    /**
     * Records a binary message sent.
     */
    public void binaryMessageSent() {
        messagesSent.add(1,
                Attributes.of(ATTR_MESSAGE_TYPE, "binary"));
    }

    /**
     * Records a frame received.
     *
     * @param opcode the frame opcode name (e.g. "text", "binary",
     *               "ping", "pong", "close")
     */
    public void frameReceived(String opcode) {
        framesReceived.add(1,
                Attributes.of("websocket.frame.opcode", opcode));
    }

    /**
     * Records a frame sent.
     *
     * @param opcode the frame opcode name
     */
    public void frameSent(String opcode) {
        framesSent.add(1,
                Attributes.of("websocket.frame.opcode", opcode));
    }

    /**
     * Records a WebSocket error.
     */
    public void error() {
        errors.add(1);
    }
}
