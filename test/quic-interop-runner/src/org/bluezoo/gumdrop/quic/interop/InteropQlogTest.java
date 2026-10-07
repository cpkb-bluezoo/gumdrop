/*
 * InteropQlogTest.java
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

package org.bluezoo.gumdrop.quic.interop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.junit.Test;

/**
 * The runner's {@code QLOGDIR} turns qlog files on.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class InteropQlogTest {

    @Test
    public void noDirectoryMeansNoQlog() {
        assertNull(InteropEnvironment.qlogTelemetry(null));
        assertNull(InteropEnvironment.qlogTelemetry(""));
    }

    @Test
    public void directoryGivesAConfigurationThatWritesQlogFiles() throws Exception {
        Path dir = Files.createTempDirectory("interop-qlog");
        try {
            TelemetryConfig telemetry = InteropEnvironment.qlogTelemetry(dir.toString());
            assertTrue(telemetry.isQlogConfigured());
            assertEquals(dir, telemetry.getQlogDirectory());
            LogRecord event = new LogRecord(1_700_000_000_000_000_000L, LogRecord.SEVERITY_DEBUG, "{}");
            event.addAttribute(LogRecord.CHANNEL_ATTRIBUTE, QlogAttributes.CHANNEL);
            event.addAttribute(QlogAttributes.NAME, "quic:connection_started");
            event.addAttribute(QlogAttributes.GROUP_ID, "0a0b");
            event.addAttribute(QlogAttributes.VANTAGE_POINT, "server");
            telemetry.getExporter().export(event);
            telemetry.shutdown();
            Path file = dir.resolve("0a0b_server.sqlog");
            assertTrue(Files.exists(file));
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertTrue(text, text.contains("quic:connection_started"));
            assertTrue(text, text.contains("urn:ietf:params:qlog:file:sequential"));
            Files.delete(file);
        } finally {
            Files.deleteIfExists(dir);
        }
    }
}
