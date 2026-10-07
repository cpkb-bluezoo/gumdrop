/*
 * TelemetryConfigQlogTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.junit.Test;

/**
 * The qlog directory setting of {@link TelemetryConfig}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TelemetryConfigQlogTest {

    @Test
    public void qlogIsOffUntilADirectoryIsSet() {
        TelemetryConfig config = new TelemetryConfig();
        assertNull(config.getQlogDirectory());
        assertFalse(config.isQlogConfigured());
        Path dir = Path.of("qlog-out");
        config.setQlogDirectory(dir);
        assertEquals(dir, config.getQlogDirectory());
        assertTrue(config.isQlogConfigured());
    }

    @Test
    public void qlogAloneDoesNotCountAsOtlpExport() {
        TelemetryConfig config = new TelemetryConfig();
        config.setQlogDirectory(Path.of("qlog-out"));
        assertFalse(config.isExportConfigured());
    }
}
