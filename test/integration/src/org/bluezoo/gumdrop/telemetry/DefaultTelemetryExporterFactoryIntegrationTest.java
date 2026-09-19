/*
 * DefaultTelemetryExporterFactoryIntegrationTest.java
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

package org.bluezoo.gumdrop.telemetry;

import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.telemetry.export.DefaultTelemetryExporterFactory;
import org.bluezoo.gumdrop.telemetry.otlp.OtlpExporter;
import org.bluezoo.gumdrop.telemetry.otlp.OtlpGrpcExporter;
import org.junit.Test;

/**
 * Exporter selection for the network exporters. Creating an OTLP exporter
 * boots a Gumdrop runtime, so this lives with the integration tests; no
 * data is exported.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultTelemetryExporterFactoryIntegrationTest {

    @Test
    public void endpointSelectsHttpExporterByDefault() {
        TelemetryConfig config = new TelemetryConfig();
        config.setEndpoint("http://collector.invalid:4318");
        config.setMetricsEndpoint("http://collector.invalid:4318/v1/metrics");
        TelemetryExporter e =
                new DefaultTelemetryExporterFactory().createExporter(config);
        try {
            assertTrue(e instanceof OtlpExporter);
        } finally {
            e.shutdown();
        }
    }

    @Test
    public void grpcProtocolSelectsGrpcExporter() {
        TelemetryConfig config = new TelemetryConfig();
        config.setMetricsEndpoint("http://collector.invalid:4317");
        config.setProtocol("GRPC");
        TelemetryExporter e =
                new DefaultTelemetryExporterFactory().createExporter(config);
        try {
            assertTrue(e instanceof OtlpGrpcExporter);
        } finally {
            e.shutdown();
        }
    }
}
