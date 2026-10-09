/*
 * OtlpChannelAndLoggingTest.java
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

package org.bluezoo.gumdrop.telemetry.otlp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers {@link HttpRequestChannel} (including back-pressure), the
 * channel-writing {@link MetricSerializer} overloads, and the
 * log-enabled arms of the OTLP response handlers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpChannelAndLoggingTest {

    /** Request stub that refuses the first write (back-pressure) then accepts in 3-byte steps. */
    private static final class StubRequest implements HttpRequest {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int calls;
        boolean ended;

        @Override
        public void header(String name, ByteBuffer rawValue) {
        String value = java.nio.charset.StandardCharsets.ISO_8859_1.decode(rawValue.duplicate()).toString();
        }

        @Override
        public void longHeader(String name, long value) {
        }

        @Override
        public void dateHeader(String name, Instant value) {
        }

        @Override
        public void contentType(ContentType contentType) {
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
        }

        @Override
        public void endHeaders() {
        }

        @Override
        public void priority(int weight) {
        }

        @Override
        public void dependency(HttpRequest parent) {
        }

        @Override
        public void exclusive(boolean exclusive) {
        }

        @Override
        public int bodyContent(ByteBuffer data) {
            calls++;
            if (calls == 1) {
                return 0;
            }
            int n = Math.min(3, data.remaining());
            for (int i = 0; i < n; i++) {
                body.write(data.get());
            }
            return n;
        }

        @Override
        public void endMessage() {
            ended = true;
        }

        @Override
        public void cancel() {
        }
    }

    private static final class Capture extends Handler {
        final List<String> messages = new ArrayList<String>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getLevel() + ":" + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private final Capture capture = new Capture();
    private Logger httpLogger;
    private Logger grpcLogger;

    @Before
    public void setUp() {
        httpLogger = Logger.getLogger(OtlpResponseHandler.class.getName());
        grpcLogger = Logger.getLogger(OtlpGrpcResponseHandler.class.getName());
        httpLogger.setLevel(Level.ALL);
        grpcLogger.setLevel(Level.ALL);
        httpLogger.setUseParentHandlers(false);
        grpcLogger.setUseParentHandlers(false);
        httpLogger.addHandler(capture);
        grpcLogger.addHandler(capture);
    }

    @After
    public void tearDown() {
        httpLogger.removeHandler(capture);
        grpcLogger.removeHandler(capture);
        httpLogger.setLevel(null);
        grpcLogger.setLevel(null);
        httpLogger.setUseParentHandlers(true);
        grpcLogger.setUseParentHandlers(true);
    }

    private static TelemetryConfig config() {
        TelemetryConfig config = new TelemetryConfig();
        config.serviceName("svc");
        return config;
    }

    @Test
    public void channelRetriesOnBackPressureAndEndsBodyOnClose() throws IOException {
        StubRequest request = new StubRequest();
        HttpRequestChannel channel = new HttpRequestChannel(request);
        assertTrue(channel.isOpen());
        assertEquals(0, channel.write(ByteBuffer.allocate(0)));
        byte[] data = new byte[] {1, 2, 3, 4, 5, 6, 7};
        int written = channel.write(ByteBuffer.wrap(data));
        assertEquals(7, written);
        assertEquals(7, request.body.size());
        assertTrue(request.calls > 3);
        assertFalse(request.ended);
        channel.close();
        assertTrue(request.ended);
        assertFalse(channel.isOpen());
        request.ended = false;
        channel.close();
        assertFalse(request.ended);
        try {
            channel.write(ByteBuffer.wrap(data));
            fail("expected ClosedChannelException");
        } catch (ClosedChannelException expected) {
            assertEquals(7, request.body.size());
        }
    }

    @Test
    public void metricSerializerChannelOverloads() throws IOException {
        MetricSerializer s = new MetricSerializer("svc", null, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        s.serialize(new ArrayList<MetricData>(), "m", "1", channel);
        s.serialize((List<MetricData>) null, "m", "1", channel);
        assertEquals(0, out.size());

        Meter empty = new Meter("e");
        s.serialize(empty, AggregationTemporality.DELTA, channel);
        assertEquals(0, out.size());

        Meter meter = new Meter("m", "1");
        LongCounter c = meter.counterBuilder("c").build();
        c.add(2L);
        s.serialize(meter, AggregationTemporality.CUMULATIVE, channel);
        assertTrue(out.size() > 0);
    }

    @Test
    public void httpHandlerLogsAtEveryLevel() {
        OtlpExporter e = new OtlpExporter();
        e.timeoutMs(500);
        e.start(config(), false);
        try {
            OtlpResponseHandler h = new OtlpResponseHandler("traces", e);
            h.status(HttpStatus.OK.code);
            h.status(HttpStatus.SERVICE_UNAVAILABLE.code);
            h.status(HttpStatus.BAD_REQUEST.code);
            h.failed(new RuntimeException("x"));
            assertTrue(capture.messages.toString(),
                    capture.messages.size() >= 4);
        } finally {
            e.shutdown();
        }
    }

    @Test
    public void grpcHandlerLogsAtEveryLevel() {
        OtlpGrpcExporter e = new OtlpGrpcExporter();
        e.timeoutMs(500);
        e.start(config(), false);
        try {
            OtlpGrpcResponseHandler h = new OtlpGrpcResponseHandler("logs", e);
            h.status(HttpStatus.OK.code);
            h.status(HttpStatus.BAD_GATEWAY.code);
            h.failed(new RuntimeException("x"));
            assertTrue(capture.messages.toString(),
                    capture.messages.size() >= 3);
        } finally {
            e.shutdown();
        }
    }
}
