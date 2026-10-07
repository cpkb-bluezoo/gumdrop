/*
 * LogRecordChannelTest.java
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
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The explicit-time constructor and channel tag of {@link LogRecord}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogRecordChannelTest {

    @Test
    public void recordKeepsTheTimeItIsGiven() {
        LogRecord r = new LogRecord(1_700_000_000_123_456_789L, LogRecord.SEVERITY_DEBUG, "body");
        assertEquals(1_700_000_000_123_456_789L, r.getTimeUnixNano());
        assertEquals("body", r.getBody());
        assertEquals(LogRecord.SEVERITY_DEBUG, r.getSeverityNumber());
    }

    @Test
    public void channelIsTheTaggedAttribute() {
        LogRecord r = new LogRecord(LogRecord.SEVERITY_INFO, "x");
        assertNull(r.getChannel());
        r.addAttribute(LogRecord.CHANNEL_ATTRIBUTE, "qlog");
        assertEquals("qlog", r.getChannel());
    }
}
