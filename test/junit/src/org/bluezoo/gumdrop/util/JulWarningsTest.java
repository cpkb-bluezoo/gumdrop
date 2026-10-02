/*
 * JulWarningsTest.java
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

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JulWarnings}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JulWarningsTest {

    private static final class Capture extends Handler {
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

    private Logger logger;
    private Capture capture;

    @Before
    public void setUp() {
        logger = Logger.getLogger("org.bluezoo.gumdrop.util.JulWarningsTest");
        logger.setUseParentHandlers(false);
        capture = new Capture();
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
    }

    @After
    public void tearDown() {
        logger.removeHandler(capture);
    }

    @Test
    public void warnWithCauseLogsFineStackAndWarningLine() {
        JulWarnings.warn(logger, "boom", new IOException("bad"));
        assertEquals(2, capture.records.size());
        assertEquals(Level.FINE, capture.records.get(0).getLevel());
        assertNotNull(capture.records.get(0).getThrown());
        assertEquals(Level.WARNING, capture.records.get(1).getLevel());
        assertEquals("boom: bad", capture.records.get(1).getMessage());
    }

    @Test
    public void warnWithoutCauseLogsOnlyMessage() {
        JulWarnings.warn(logger, "plain", null);
        assertEquals(1, capture.records.size());
        assertEquals("plain", capture.records.get(0).getMessage());
        assertNull(capture.records.get(0).getThrown());
    }

    @Test
    public void severeUsesSevereLevel() {
        JulWarnings.severe(logger, "bad", null);
        assertEquals(1, capture.records.size());
        assertEquals(Level.SEVERE, capture.records.get(0).getLevel());
    }

    @Test
    public void warnWithCauseAtInfoLevelSkipsFineStack() {
        logger.setLevel(Level.INFO);
        JulWarnings.warn(logger, "quiet", new IOException("x"));
        assertEquals(1, capture.records.size());
        assertEquals("quiet: x", capture.records.get(0).getMessage());
    }

    @Test
    public void disabledLevelLogsNothing() {
        logger.setLevel(Level.OFF);
        JulWarnings.warn(logger, "nothing", new IOException("x"));
        JulWarnings.severe(logger, "nothing", null);
        assertTrue(capture.records.isEmpty());
    }

    @Test
    public void transportErrorBenignGoesToFine() {
        JulWarnings.transportError(logger, "gone", new ClosedChannelException());
        assertEquals(1, capture.records.size());
        assertEquals(Level.FINE, capture.records.get(0).getLevel());
    }

    @Test
    public void transportErrorBenignSilentWhenFineDisabled() {
        logger.setLevel(Level.INFO);
        JulWarnings.transportError(logger, "gone", new IOException("Connection reset by peer"));
        assertTrue(capture.records.isEmpty());
    }

    @Test
    public void transportErrorOtherWarns() {
        JulWarnings.transportError(logger, "real", new IOException("disk on fire"));
        assertEquals(Level.WARNING, capture.records.get(1).getLevel());
    }

    @Test
    public void benignClassification() {
        assertTrue(JulWarnings.isBenignTransportFailure(new ClosedByInterruptException()));
        assertTrue(JulWarnings.isBenignTransportFailure(new InterruptedException()));
        assertFalse(JulWarnings.isBenignTransportFailure(null));
        assertFalse(JulWarnings.isBenignTransportFailure(new IOException()));
        assertFalse(JulWarnings.isBenignTransportFailure(new IOException("other")));
        String[] msgs = new String[] {"Connection reset", "connection CLOSED", "Broken pipe",
            "Connection refused", "Socket closed", "Stream closed"};
        for (int i = 0; i < msgs.length; i++) {
            assertTrue(msgs[i], JulWarnings.isBenignTransportFailure(new IOException(msgs[i])));
        }
    }

    @Test
    public void benignClassificationWalksCauseChain() {
        Throwable inner = new ClosedChannelException();
        Throwable mid = new IOException("wrapper", inner);
        Throwable outer = new RuntimeException(mid);
        assertTrue(JulWarnings.isBenignTransportFailure(outer));
    }
}
