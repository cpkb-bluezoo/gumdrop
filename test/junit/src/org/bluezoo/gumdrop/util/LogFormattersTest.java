/*
 * LogFormattersTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LaconicFormatter}, {@link MessageFormatter} and
 * {@link JulWarnings}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogFormattersTest {

    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Logger newLogger(String name, Level level, CapturingHandler handler) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        logger.setLevel(level);
        logger.addHandler(handler);
        return logger;
    }

    @Test
    public void laconicFormatterOmitsThrowableWhenAbsent() {
        LaconicFormatter formatter = new LaconicFormatter();
        LogRecord record = new LogRecord(Level.WARNING, "disk low");
        String text = formatter.format(record);
        assertTrue(text.startsWith(Level.WARNING.getLocalizedName() + ": disk low"));
        assertFalse(text.contains("Exception"));
    }

    @Test
    public void laconicFormatterAppendsStackTrace() {
        LaconicFormatter formatter = new LaconicFormatter();
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setThrown(new IllegalStateException("bad state"));
        String text = formatter.format(record);
        assertTrue(text.contains("IllegalStateException: bad state"));
    }

    @Test
    public void laconicFormatterToleratesNullMessage() {
        LaconicFormatter formatter = new LaconicFormatter();
        LogRecord record = new LogRecord(Level.INFO, null);
        String text = formatter.format(record);
        assertTrue(text.startsWith(Level.INFO.getLocalizedName() + ": "));
    }

    @Test
    public void messageFormatterEmitsMessageAndEol() {
        MessageFormatter formatter = new MessageFormatter();
        LogRecord record = new LogRecord(Level.INFO, "hello");
        assertEquals("hello" + MessageFormatter.EOL, formatter.format(record));
    }

    @Test
    public void julWarningsWithoutCauseLogsMessageOnly() {
        CapturingHandler handler = new CapturingHandler();
        Logger logger = newLogger("test.julwarnings.nocause", Level.INFO, handler);
        JulWarnings.warn(logger, "plain", null);
        assertEquals(1, handler.records.size());
        assertEquals(Level.WARNING, handler.records.get(0).getLevel());
        assertEquals("plain", handler.records.get(0).getMessage());
    }

    @Test
    public void julWarningsWithCauseSummarisesAtWarningLevel() {
        CapturingHandler handler = new CapturingHandler();
        Logger logger = newLogger("test.julwarnings.cause", Level.INFO, handler);
        JulWarnings.severe(logger, "failed", new RuntimeException("why"));
        assertEquals(1, handler.records.size());
        LogRecord record = handler.records.get(0);
        assertEquals(Level.SEVERE, record.getLevel());
        assertEquals("failed: why", record.getMessage());
        assertNull(record.getThrown());
    }

    @Test
    public void julWarningsAtFineAlsoLogsStackTrace() {
        CapturingHandler handler = new CapturingHandler();
        Logger logger = newLogger("test.julwarnings.fine", Level.ALL, handler);
        RuntimeException cause = new RuntimeException("why");
        JulWarnings.warn(logger, "failed", cause);
        assertEquals(2, handler.records.size());
        assertSame(cause, handler.records.get(0).getThrown());
        assertEquals(Level.FINE, handler.records.get(0).getLevel());
    }

    @Test
    public void julWarningsSuppressedWhenLevelDisabled() {
        CapturingHandler handler = new CapturingHandler();
        Logger logger = newLogger("test.julwarnings.off", Level.OFF, handler);
        JulWarnings.warn(logger, "quiet", new RuntimeException("x"));
        assertTrue(handler.records.isEmpty());
    }
}
