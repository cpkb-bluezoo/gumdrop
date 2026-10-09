/*
 * GumdropTelemetryTest.java
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

package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.bluezoo.gumdrop.telemetry.DefaultExporter;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Test;

/**
 * The runtime owns the telemetry configuration: listeners reach it
 * through the runtime, and shutdown flushes and shuts its exporters down.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GumdropTelemetryTest {

    private static final class Plain extends Listener {
        @Override
        public String getDescription() {
            return "plain";
        }
    }

    @Test
    public void aRuntimeAlwaysHasAConfiguration() {
        Gumdrop gumdrop = TestGumdrop.create();
        TelemetryConfig telemetry = gumdrop.getTelemetryConfig();
        assertNotNull(telemetry);
        assertTrue(telemetry.getExporter() instanceof DefaultExporter);
        try {
            gumdrop.telemetryConfig(null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        assertSame(telemetry, gumdrop.getTelemetryConfig());
    }

    @Test
    public void aListenerReachesTheConfigurationThroughItsRuntime() {
        Gumdrop gumdrop = TestGumdrop.create();
        TelemetryConfig telemetry = new TelemetryConfig();
        gumdrop.telemetryConfig(telemetry);
        Plain listener = new Plain();
        assertNull(listener.getGumdrop());
        assertNull(listener.getTelemetryConfig());
        listener.start(gumdrop);
        assertSame(gumdrop, listener.getGumdrop());
        assertSame(telemetry, listener.getTelemetryConfig());
        assertSame(telemetry, listener.getTransportFactory().getTelemetryConfig());
    }

    @Test
    public void shutdownFlushesAndShutsTheExportersDown() throws Exception {
        Gumdrop gumdrop = TestGumdrop.create();
        TelemetryConfig telemetry = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        telemetry.exporter(exporter);
        gumdrop.telemetryConfig(telemetry);
        gumdrop.start();
        assertEquals(0, exporter.shutdowns);
        gumdrop.shutdown();
        assertEquals(1, exporter.forceFlushes);
        assertEquals(1, exporter.shutdowns);
        assertTrue(telemetry.isShuttingDown());
    }

}
