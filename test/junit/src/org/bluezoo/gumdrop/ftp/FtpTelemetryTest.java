/*
 * FtpTelemetryTest.java
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


package org.bluezoo.gumdrop.ftp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ftp.FtpProtocolHandlerResultsTest.ScriptedFs;
import org.bluezoo.gumdrop.ftp.FtpProtocolHandlerResultsTest.ScriptedHandler;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks the trace spans and events that {@link FtpProtocolHandler} records
 * for a control session: connection attributes, authentication success and
 * failure, file operations on the authenticated span, a clean QUIT, an abrupt
 * disconnect and transport errors. The trace is captured by a {@link
 * TelemetryConfig} subclass so no exporter or network is involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpTelemetryTest {

    /** Config that remembers the traces it created. */
    private static final class CapturingConfig extends TelemetryConfig {
        final List<Trace> traces = new ArrayList<Trace>();

        CapturingConfig() {
            exporter(new RecordingExporter());
        }

        @Override
        public Trace createTrace(String rootSpanName) {
            Trace trace = super.createTrace(rootSpanName);
            if (trace != null) {
                traces.add(trace);
            }
            return trace;
        }
    }

    /** Endpoint that reports telemetry as enabled. */
    private static final class TelemetryEndpoint extends FTPProtocolHandlerTest.StubEndpoint {
        private final TelemetryConfig config;

        TelemetryEndpoint(TelemetryConfig config) {
            this.config = config;
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return config;
        }
    }

    private CapturingConfig config;
    private ScriptedHandler scripted;
    private TelemetryEndpoint endpoint;
    private FtpProtocolHandler handler;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        Path root = mem.getPath("/srv/ftp");
        Files.createDirectories(root);
        Files.write(root.resolve("f.txt"), "data".getBytes(StandardCharsets.UTF_8));
        ScriptedFs fs = new ScriptedFs(new BasicFTPFileSystem(root, false));
        scripted = new ScriptedHandler(fs);
        config = new CapturingConfig();
        endpoint = new TelemetryEndpoint(config);
        handler = new FtpProtocolHandler(new FtpListener(), scripted);
    }

    private void cmd(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private void login() {
        handler.connected(endpoint);
        cmd("USER alice");
        cmd("PASS secret");
    }

    private static void collect(Span span, List<String> events, List<String> attrs) {
        for (SpanEvent e : span.getEvents()) {
            events.add(e.getName());
        }
        for (Attribute a : span.getAttributes()) {
            attrs.add(a.getKey());
        }
        for (Span child : span.getChildren()) {
            collect(child, events, attrs);
        }
    }

    private List<String> events() {
        List<String> events = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        collect(config.traces.get(0).getRootSpan(), events, attrs);
        return events;
    }

    private List<String> attributes() {
        List<String> events = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        collect(config.traces.get(0).getRootSpan(), events, attrs);
        return attrs;
    }

    @Test
    public void connectionRecordsRootAttributesAndSessionStart() {
        handler.connected(endpoint);
        assertEquals(1, config.traces.size());
        List<String> attrs = attributes();
        assertTrue(attrs.toString(), attrs.contains("net.transport"));
        assertTrue(attrs.toString(), attrs.contains("net.peer.ip"));
        assertTrue(attrs.toString(), attrs.contains("rpc.system"));
        assertTrue(events().toString(), events().contains("SESSION_START"));
    }

    @Test
    public void successfulLoginIsRecordedOnTheSessionAndAuthenticatedSpans() {
        login();
        List<String> events = events();
        assertTrue(events.toString(), events.contains("AUTH_SUCCESS"));
        assertTrue(events.toString(), events.contains("AUTHENTICATED"));
        assertTrue(attributes().toString(), attributes().contains("ftp.auth.mechanism"));
    }

    @Test
    public void failedLoginIsRecordedWithTheAttemptedUser() {
        scripted.authResult = FtpAuthenticationResult.INVALID_PASSWORD;
        handler.connected(endpoint);
        cmd("USER alice");
        cmd("PASS wrong");
        List<String> events = events();
        assertTrue(events.toString(), events.contains("AUTH_FAILURE"));
        assertFalse(events.toString(), events.contains("AUTH_SUCCESS"));
        assertTrue(attributes().toString(), attributes().contains("ftp.auth.attempted_user"));
    }

    @Test
    public void fileOperationsAreRecordedOnTheAuthenticatedSpan() {
        login();
        cmd("DELE f.txt");
        List<String> events = events();
        assertTrue(events.toString(), events.contains("DELE"));
        assertTrue(attributes().toString(), attributes().contains("ftp.file.path"));
    }

    @Test
    public void quitCompletesTheSession() {
        login();
        cmd("QUIT");
        List<String> events = events();
        assertTrue(events.toString(), events.contains("QUIT"));
        assertTrue(events.toString(), events.contains("SESSION_END"));
    }

    @Test
    public void abruptDisconnectIsASessionError() {
        login();
        handler.disconnected();
        List<String> events = events();
        assertTrue(events.toString(), events.contains("SESSION_ERROR"));
        assertFalse(events.toString(), events.contains("SESSION_END"));
    }

    @Test
    public void disconnectBeforeLoginIsASessionError() {
        handler.connected(endpoint);
        handler.disconnected();
        assertTrue(events().toString(), events().contains("SESSION_ERROR"));
    }

    @Test
    public void transportErrorClosesTheConnection() {
        login();
        handler.error(new IOException("reset"));
        assertFalse(endpoint.open);
    }

    @Test
    public void disabledTracesLeaveSessionWorking() {
        TelemetryConfig off = new TelemetryConfig();
        TelemetryEndpoint plain = new TelemetryEndpoint(off);
        FtpProtocolHandler h = new FtpProtocolHandler(new FtpListener(), scripted);
        h.connected(plain);
        h.receive(ByteBuffer.wrap("NOOP\r\n".getBytes(StandardCharsets.US_ASCII)));
        List<String> responses = plain.getResponses();
        assertTrue(responses.toString(), responses.get(responses.size() - 1).startsWith("200"));
    }

    @Test
    public void noConfigMeansNoTrace() {
        TelemetryEndpoint none = new TelemetryEndpoint(null);
        FtpProtocolHandler h = new FtpProtocolHandler(new FtpListener(), scripted);
        h.connected(none);
        assertTrue(config.traces.isEmpty());
        assertNull(none.getTelemetryConfig());
    }
}
