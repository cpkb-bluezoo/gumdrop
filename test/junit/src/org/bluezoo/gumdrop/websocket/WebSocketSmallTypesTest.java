/*
 * WebSocketSmallTypesTest.java
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

package org.bluezoo.gumdrop.websocket;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WebSocketServerMetrics}, the no-op
 * {@link DefaultWebSocketEventHandler} and {@link WebSocketProtocolException}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketSmallTypesTest {

    @Test
    public void serverMetricsRecordEveryEvent() {
        TelemetryConfig config = new TelemetryConfig();
        WebSocketServerMetrics metrics = new WebSocketServerMetrics(config);
        metrics.connectionOpened();
        metrics.textMessageReceived();
        metrics.binaryMessageReceived();
        metrics.textMessageSent();
        metrics.binaryMessageSent();
        metrics.frameReceived("text");
        metrics.frameSent("pong");
        metrics.error();
        metrics.connectionClosed(12.5d, 1000);

        Set<String> names = new HashSet<String>();
        for (Meter meter : config.getMeters().values()) {
            List<MetricData> data = meter.collect(AggregationTemporality.CUMULATIVE);
            for (MetricData d : data) {
                names.add(d.getName());
            }
        }
        assertTrue("metrics recorded: " + names, names.size() >= 5);
    }

    @Test
    public void defaultHandlerCallbacksAreNoOps() {
        DefaultWebSocketEventHandler handler = new DefaultWebSocketEventHandler() {
        };
        handler.opened(null);
        handler.textMessageReceived(null, "x");
        handler.binaryMessageReceived(null, ByteBuffer.allocate(1));
        handler.closed(1000, "bye");
        handler.error(new RuntimeException());
    }

    @Test
    public void protocolExceptionConstructors() {
        RuntimeException cause = new RuntimeException("c");
        assertEquals("m", new WebSocketProtocolException("m").getMessage());
        WebSocketProtocolException withCause = new WebSocketProtocolException("m", cause);
        assertEquals("m", withCause.getMessage());
        assertSame(cause, withCause.getCause());
    }
}
