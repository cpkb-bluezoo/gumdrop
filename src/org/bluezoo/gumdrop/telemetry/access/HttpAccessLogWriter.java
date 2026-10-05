/*
 * HttpAccessLogWriter.java
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends formatted HTTP access log lines to a file, flushing after each line.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HttpAccessLogWriter {

    private final HttpAccessLogFormatter formatter;
    private final BufferedWriter writer;
    private boolean preambleWritten;

    public HttpAccessLogWriter(Path path, AccessLogFormat format,
            AccessLogUserSelection userSelection) throws IOException {
        this.formatter = new HttpAccessLogFormatter(format, userSelection);
        boolean fileExists = Files.exists(path);
        this.writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND),
                StandardCharsets.UTF_8));
        this.preambleWritten = fileExists;
    }

    public synchronized void write(HttpAccessRecord record) throws IOException {
        if (!preambleWritten) {
            for (String line : formatter.preambleLines()) {
                writer.write(line);
                writer.newLine();
            }
            preambleWritten = true;
        }
        writer.write(formatter.formatLine(record));
        writer.newLine();
        writer.flush();
    }

    public synchronized void close() throws IOException {
        writer.close();
    }
}
