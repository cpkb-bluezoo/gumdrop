/*
 * DefaultTelemetryExporterFactoryTest.java
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

package org.bluezoo.gumdrop.telemetry.export;

import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.telemetry.json.OtlpFileExporter;
import org.junit.Test;

/**
 * Tests exporter selection in {@link DefaultTelemetryExporterFactory} for
 * the branches that need no runtime: nothing configured, and the file
 * exporter (created without paths, so it touches no files). The OTLP
 * network exporters are covered by
 * {@code DefaultTelemetryExporterFactoryIntegrationTest}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultTelemetryExporterFactoryTest {

    @Test
    public void noExporterWhenExportNotConfigured() {
        TelemetryConfig config = new TelemetryConfig();
        assertNull(new DefaultTelemetryExporterFactory().createExporter(config));
    }

    @Test
    public void fileTypeSelectsFileExporter() {
        TelemetryConfig config = new TelemetryConfig();
        config.setExporterType("FILE");
        TelemetryExporter e =
                new DefaultTelemetryExporterFactory().createExporter(config);
        assertTrue(e instanceof OtlpFileExporter);
    }
}
