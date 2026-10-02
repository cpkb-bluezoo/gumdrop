/*
 * SocksServerMetricsTest.java
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

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.junit.Test;

/**
 * Exercises every recording method of {@link SocksServerMetrics} against a
 * default {@link TelemetryConfig}; the instruments must accept updates
 * without throwing.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SocksServerMetricsTest {

    @Test
    public void allRecordingMethodsAcceptUpdates() {
        TelemetryConfig config = new TelemetryConfig();
        SocksServerMetrics m = new SocksServerMetrics(config);
        m.connectionOpened();
        m.connectRequest("5");
        m.connectRequest("4a");
        m.bindRequest("4");
        m.relayOpened();
        m.bytesRelayed(512L, "upstream");
        m.bytesRelayed(256L, "downstream");
        m.relayClosed(12.5);
        m.udpAssociationOpened();
        m.udpAssociationClosed(40.0);
        m.authAttempt("username_password");
        m.authSuccess();
        m.authFailure();
        m.destinationBlocked();
        m.connectionClosed(250.0);
    }
}
