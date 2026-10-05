/*
 * HttpAccessLogWriterTest.java
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpAccessLogWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesClfLineToFile() throws Exception {
        Path log = tmp.newFile("access.log").toPath();
        HttpAccessLogWriter writer = new HttpAccessLogWriter(
                log, AccessLogFormat.CLF, AccessLogUserSelection.PROTOCOL_FIRST);
        writer.write(new HttpAccessRecord(
                System.currentTimeMillis(), "127.0.0.1", null, null,
                "GET", "/test", "HTTP/1.1", 200, 0L));
        writer.close();
        String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertTrue(text.contains("GET /test HTTP/1.1"));
        assertTrue(text.contains(" 200 0"));
    }
}
