/*
 * QlogExporterFactoryTest.java
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.TelemetryExporter;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import org.junit.Test;

/**
 * The qlog exporter is created from the qlog directory alone, and only then.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QlogExporterFactoryTest {

    @Test
    public void noExporterWithoutAQlogDirectory() {
        assertNull(new QlogExporterFactory().createExporter(new TelemetryConfig()));
    }

    @Test
    public void exporterIsCreatedFromTheDirectoryAlone() throws IOException {
        Path dir = MemoryTemp.createTempDirectory("qlogf");
        try {
            TelemetryConfig config = new TelemetryConfig();
            config.setQlogDirectory(dir);
            TelemetryExporter e = new QlogExporterFactory().createExporter(config);
            try {
                assertTrue(e instanceof QlogExporter);
                assertTrue(e.claimsChannel("qlog"));
            } finally {
                e.shutdown();
            }
        } finally {
            Files.deleteIfExists(dir);
        }
    }
}
