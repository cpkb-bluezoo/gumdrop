/*
 * POP3TelemetryTest
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

package org.bluezoo.gumdrop.pop3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks the trace spans and events that {@link Pop3ProtocolHandler} records
 * for a session: connection attributes, authentication success and failure,
 * STLS outcome, message retrieval and deletion, a clean QUIT and an abrupt
 * disconnect. The trace is captured by a {@link TelemetryConfig} subclass so
 * no exporter or network is involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3TelemetryTest {

    /** Config that remembers the traces it created. */
    private static final class CapturingConfig extends TelemetryConfig {
        final List<Trace> traces = new ArrayList<Trace>();

        @Override
        public Trace createTrace(String rootSpanName, SpanKind kind) {
            Trace trace = super.createTrace(rootSpanName, kind);
            if (trace != null) {
                traces.add(trace);
            }
            return trace;
        }
    }

    /** Endpoint that reports telemetry as enabled. */
    private static final class TelemetryEndpoint extends POP3AuthFlowsTest.TimerEndpoint {
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
    private TelemetryEndpoint endpoint;
    private POP3ProtocolHandlerTest.TestPOP3Listener listener;
    private Pop3ProtocolHandler handler;

    @Before
    public void setUp() {
        config = new CapturingConfig();
        listener = new POP3ProtocolHandlerTest.TestPOP3Listener();
        listener.setRealm(new POP3ProtocolHandlerTest.StubRealm());
        listener.setMailboxFactory(new POP3ProtocolHandlerTest.StubMailboxFactory());
        listener.setEnableAPOP(false);
        endpoint = new TelemetryEndpoint(config);
        handler = new Pop3ProtocolHandler(listener);
    }

    private void send(String command) {
        handler.receive(ByteBuffer.wrap((command + "\r\n").getBytes(StandardCharsets.US_ASCII)));
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

    private void login() {
        handler.connected(endpoint);
        send("USER testuser");
        send("PASS testpass");
    }

    @Test
    public void connectionRecordsRootAttributes() {
        handler.connected(endpoint);
        assertEquals(1, config.traces.size());
        List<String> attrs = attributes();
        assertTrue(attrs.toString(), attrs.contains("net.transport"));
        assertTrue(attrs.toString(), attrs.contains("net.peer.ip"));
        assertTrue(attrs.toString(), attrs.contains("rpc.system"));
        assertTrue(events().toString(), events().contains("SESSION_START"));
    }

    @Test
    public void successfulLoginAndQuitCompleteTheSession() {
        login();
        List<String> events = events();
        assertTrue(events.toString(), events.contains("AUTH_SUCCESS"));
        assertTrue(events.toString(), events.contains("AUTHENTICATED"));
        assertTrue(attributes().toString(), attributes().contains("pop3.auth.mechanism"));
        send("QUIT");
        events = events();
        assertTrue(events.toString(), events.contains("SESSION_END"));
    }

    @Test
    public void failedLoginIsRecorded() {
        handler.connected(endpoint);
        send("USER testuser");
        send("PASS wrong");
        List<String> events = events();
        assertTrue(events.toString(), events.contains("AUTH_FAILURE"));
        assertFalse(events.toString(), events.contains("AUTH_SUCCESS"));
        assertTrue(attributes().toString(), attributes().contains("pop3.auth.attempted_user"));
    }

    @Test
    public void retrieveAndDeleteAreRecordedOnAuthenticatedSpan() {
        login();
        send("RETR 1");
        send("DELE 2");
        List<String> events = events();
        assertTrue(events.toString(), events.contains("RETR"));
        assertTrue(events.toString(), events.contains("DELE"));
        List<String> attrs = attributes();
        assertTrue(attrs.toString(), attrs.contains("pop3.message.number"));
        assertTrue(attrs.toString(), attrs.contains("pop3.message.size"));
    }

    @Test
    public void abruptDisconnectIsASessionError() {
        login();
        handler.disconnected();
        List<String> events = events();
        assertTrue(events.toString(), events.contains("SESSION_ERROR"));
        assertFalse(events.toString(), events.contains("SESSION_END"));
        Trace trace = config.traces.get(0);
        assertNotNull(trace);
    }

    @Test
    public void transportErrorEndsSession() {
        login();
        handler.error(new IOException("reset"));
        assertFalse(endpoint.isOpen());
        assertTrue(events().toString(), events().contains("SESSION_END"));
    }

    @Test
    public void stlsSuccessIsRecorded() {
        listener.starttlsAvailable = true;
        handler.connected(endpoint);
        send("STLS");
        List<String> events = events();
        assertTrue(events.toString(), events.contains("STARTTLS_SUCCESS"));
        assertTrue(attributes().toString(), attributes().contains("net.protocol.name"));
    }

    @Test
    public void stlsFailureIsRecorded() {
        listener.starttlsAvailable = true;
        endpoint.failStartTls = true;
        handler.connected(endpoint);
        send("STLS");
        List<String> events = events();
        assertTrue(events.toString(), events.contains("STARTTLS_FAILURE"));
    }

    @Test
    public void disabledTracesLeaveSessionWorking() {
        TelemetryConfig off = new TelemetryConfig();
        off.setTracesEnabled(false);
        TelemetryEndpoint plain = new TelemetryEndpoint(off);
        Pop3ProtocolHandler h = new Pop3ProtocolHandler(listener);
        h.connected(plain);
        h.receive(ByteBuffer.wrap("NOOP\r\n".getBytes(StandardCharsets.US_ASCII)));
        List<String> responses = plain.getResponses();
        assertTrue(responses.toString(), responses.get(responses.size() - 1).startsWith("+OK"));
    }

    @Test
    public void noConfigMeansNoTrace() {
        TelemetryEndpoint none = new TelemetryEndpoint(null);
        Pop3ProtocolHandler h = new Pop3ProtocolHandler(listener);
        h.connected(none);
        assertTrue(config.traces.isEmpty());
        assertNull(none.getTelemetryConfig());
    }
}
