/*
 * MqttServerMetricsTest.java
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

package org.bluezoo.gumdrop.mqtt;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.junit.Test;

/**
 * Exercises every recording method of {@link MqttServerMetrics} against a
 * default {@link TelemetryConfig}; the instruments must accept updates
 * without throwing.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttServerMetricsTest {

    @Test
    public void allRecordingMethodsAcceptUpdates() {
        TelemetryConfig config = new TelemetryConfig();
        MqttServerMetrics m = new MqttServerMetrics(config);
        m.connectionOpened();
        m.publishReceived(128, 1);
        m.subscribeReceived();
        m.unsubscribeReceived();
        m.authAttempt();
        m.authSuccess();
        m.authFailure();
        m.connectionClosed(250.0);
    }
}
