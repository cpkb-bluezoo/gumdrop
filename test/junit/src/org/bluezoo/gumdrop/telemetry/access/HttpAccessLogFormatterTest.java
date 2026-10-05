/*
 * HttpAccessLogFormatterTest.java
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

package org.bluezoo.gumdrop.telemetry.access;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpAccessLogFormatterTest {

    private static final long TIME = Instant.parse("2026-01-15T12:00:00Z")
            .toEpochMilli();

    @Test
    public void clfProtocolFirstPrefersProtocolUser() {
        HttpAccessLogFormatter formatter = new HttpAccessLogFormatter(
                AccessLogFormat.CLF, AccessLogUserSelection.PROTOCOL_FIRST);
        HttpAccessRecord record = new HttpAccessRecord(
                TIME, "10.0.0.1", "mtls-user", "app-user",
                "GET", "/hello", "HTTP/1.1", 200, 42L);
        String line = formatter.formatLine(record);
        String date = DateTimeFormatter.ofPattern("'['dd/MMM/yyyy:HH:mm:ss Z']'")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(TIME));
        assertTrue(line.contains("10.0.0.1"));
        assertTrue(line.contains("mtls-user"));
        assertTrue(line.contains(date));
        assertTrue(line.contains("\"GET /hello HTTP/1.1\""));
        assertTrue(line.endsWith(" 200 42"));
    }

    @Test
    public void clfApplicationFirstUsesServletWhenProtocolAbsent() {
        HttpAccessLogFormatter formatter = new HttpAccessLogFormatter(
                AccessLogFormat.CLF, AccessLogUserSelection.APPLICATION_FIRST);
        HttpAccessRecord record = new HttpAccessRecord(
                TIME, "10.0.0.1", null, "app-user",
                "GET", "/", "HTTP/1.1", 404, 0L);
        String line = formatter.formatLine(record);
        assertTrue(line.contains(" app-user "));
    }

    @Test
    public void elffBothIncludesApplicationColumn() {
        HttpAccessLogFormatter formatter = new HttpAccessLogFormatter(
                AccessLogFormat.ELFF, AccessLogUserSelection.BOTH);
        HttpAccessRecord record = new HttpAccessRecord(
                TIME, "10.0.0.1", "proto", "app",
                "POST", "/api", "HTTP/2.0", 201, 9L);
        String line = formatter.formatLine(record);
        assertTrue(line.contains("\tproto\tapp\t"));
    }
}
