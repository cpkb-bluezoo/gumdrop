/*
 * HttpAccessLogWriterTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.telemetry.access;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

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
