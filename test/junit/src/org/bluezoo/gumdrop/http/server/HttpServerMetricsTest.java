/*
 * HttpServerMetricsTest.java
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

import static org.junit.Assert.assertNotNull;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.junit.Test;

/**
 * Exercises the recording methods of {@link HttpServerMetrics}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpServerMetricsTest {

    @Test
    public void testRecordingDoesNotThrow() {
        TelemetryConfig config = new TelemetryConfig();
        config.setMetricsEnabled(true);
        HttpServerMetrics m = new HttpServerMetrics(config);
        assertNotNull(m);
        m.connectionOpened();
        m.requestStarted("GET");
        m.requestCompleted("GET", 200, 12.5, 0L, 0L);
        m.requestStarted("POST");
        m.requestCompleted("POST", 201, 3.0, 100L, 2048L);
        m.requestError("GET", 500);
        m.requestError(null, 400);
        m.connectionClosed();
    }

    @Test
    public void testWorksWithMetricsDisabled() {
        TelemetryConfig config = new TelemetryConfig();
        config.setMetricsEnabled(false);
        HttpServerMetrics m = new HttpServerMetrics(config);
        m.requestStarted("GET");
        m.requestCompleted("GET", 200, 1.0, 1L, 1L);
        m.connectionOpened();
        m.connectionClosed();
    }
}
