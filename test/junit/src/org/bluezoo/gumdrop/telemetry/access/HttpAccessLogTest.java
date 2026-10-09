/*
 * HttpAccessLogTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.junit.Test;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpAccessLogTest {

    static Principal named(final String name) {
        return new Principal() {
            @Override
            public String getName() {
                return name;
            }
        };
    }

    @Test
    public void recordCarriesTheAccessFieldsAndTheSpan() {
        Trace trace = new Trace("op", SpanKind.SERVER);
        LogRecord r = HttpAccessLog.toRecord(trace.getRootSpan(), 1_700_000_000_123L,
                new InetSocketAddress("10.0.0.1", 5555), "POST", "/api?x=1", "HTTP/2.0",
                named("proto"), named("app"), 201, 99L);
        assertEquals(LogLevel.ACCESS, r.getLevel());
        assertEquals(HttpAccessLog.EVENT_NAME, r.getKey());
        assertEquals(1_700_000_000_123_000_000L, r.getTimeUnixNano());
        assertEquals("10.0.0.1", r.getString(HttpAccessLog.CLIENT_ADDRESS));
        assertEquals("POST", r.getString(HttpAccessLog.METHOD));
        assertEquals("/api?x=1", r.getString(HttpAccessLog.TARGET));
        assertEquals("HTTP/2.0", r.getString(HttpAccessLog.PROTOCOL_VERSION));
        assertEquals(201L, r.getAttribute(HttpAccessLog.STATUS_CODE).getIntValue());
        assertEquals(99L, r.getAttribute(HttpAccessLog.RESPONSE_BYTES).getIntValue());
        assertEquals("proto", r.getString(HttpAccessLog.PROTOCOL_USER));
        assertEquals("app", r.getString(HttpAccessLog.APPLICATION_USER));
        assertTrue(r.hasSpanContext());
        assertEquals(trace.getTraceId(), r.getTraceId());
        assertEquals(trace.getRootSpan().getSpanId(), r.getSpanId());
    }

    @Test
    public void absentFieldsAreLeftOut() {
        LogRecord r = HttpAccessLog.toRecord(null, 1L, null, null, null, null, null, named(""), 200, 0L);
        assertEquals("-", r.getString(HttpAccessLog.CLIENT_ADDRESS));
        assertNull(r.getString(HttpAccessLog.METHOD));
        assertNull(r.getString(HttpAccessLog.TARGET));
        assertNull(r.getString(HttpAccessLog.PROTOCOL_VERSION));
        assertNull(r.getString(HttpAccessLog.PROTOCOL_USER));
        assertNull(r.getString(HttpAccessLog.APPLICATION_USER));
        assertFalse(r.hasSpanContext());
    }

    @Test
    public void recordGoesToTheExporterOnlyWhenItAcceptsAccessRecords() {
        TelemetryConfig config = new TelemetryConfig();
        RecordingExporter operational = new RecordingExporter(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR);
        config.exporter(operational);
        HttpAccessLog.record(config, null, 1L, null, "GET", "/", "HTTP/1.1", null, null, 200, 0L);
        assertTrue(operational.records.isEmpty());

        RecordingExporter access = new RecordingExporter(LogLevel.ACCESS);
        config.exporter(access);
        HttpAccessLog.record(config, null, 1L, null, "GET", "/", "HTTP/1.1", null, null, 200, 0L);
        assertEquals(1, access.records.size());
        assertEquals(HttpAccessLog.EVENT_NAME, access.records.get(0).getKey());

        HttpAccessLog.record(null, null, 1L, null, "GET", "/", "HTTP/1.1", null, null, 200, 0L);
        assertEquals(1, access.records.size());
    }

}
