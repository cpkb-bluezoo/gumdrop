/*
 * NntpTelemetryTest.java
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

package org.bluezoo.gumdrop.nntp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubEndpoint;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubFactory;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The traces and metrics of an NNTP server connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpTelemetryTest {

    private TelemetryConfig config;
    private RecordingExporter exporter;
    private Listener listener;
    private StubEndpoint endpoint;
    private NntpProtocolHandler handler;

    /** A listener whose metrics are on, as start() would arrange. */
    private final class Listener extends NntpListener {
        private NntpServerMetrics metrics;

        @Override
        public NntpServerMetrics getMetrics() {
            if (metrics == null) {
                metrics = new NntpServerMetrics(config);
            }
            return metrics;
        }

        boolean tls;

        @Override
        protected boolean isSTARTTLSAvailable() {
            return tls;
        }
    }

    private static final class Realm1 implements Realm {
        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.emptySet();
        }

        @Override
        public void passwordMatch(String user, String password, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.valueOf(user.equals("alice") && password.equals("secret")));
        }

        @Override
        public void getDigestHA1(String username, String realmName, RealmCallback<String> callback) {
            callback.completed(null);
        }

        @Override
        public void isUserInRole(String username, String role, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.FALSE);
        }
    }

    @Before
    public void setUp() {
        config = new TelemetryConfig();
        exporter = new RecordingExporter();
        config.exporter(exporter);
        StubFactory factory = new StubFactory();
        factory.add("test.group", "Subject: One\r\nFrom: x@example.com\r\nMessage-ID: <1@x>\r\n\r\nbody\r\n");
        listener = new Listener();
        listener.mailboxFactory(factory);
    }

    private void connect() {
        endpoint = new StubEndpoint() {
            @Override
            public TelemetryConfig getTelemetryConfig() {
                return config;
            }
        };
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
    }

    private void send(String line) {
        handler.receive(ByteBuffer.wrap((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1)));
    }

    private Span sessionSpan() {
        assertEquals(1, exporter.traces.size());
        Trace trace = exporter.traces.get(0);
        List<Span> children = trace.getRootSpan().getChildren();
        assertEquals(1, children.size());
        return children.get(0);
    }

    private static boolean hasEvent(Span span, String name) {
        for (SpanEvent e : span.getEvents()) {
            if (e.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String attribute(Span span, String key) {
        for (Attribute a : span.getAttributes()) {
            if (a.getKey().equals(key)) {
                return String.valueOf(a.getValue());
            }
        }
        return null;
    }

    /** The sum of a counter's data points, those with the attribute if one is given. */
    private long counter(String name, String attrKey, String attrValue) {
        long total = 0;
        List<MetricData> data = config.getMeter("org.bluezoo.gumdrop.nntp", Gumdrop.VERSION)
                .collect(AggregationTemporality.CUMULATIVE);
        for (MetricData m : data) {
            if (!m.getName().equals(name)) {
                continue;
            }
            for (NumberDataPoint p : m.getNumberDataPoints()) {
                if (attrKey == null || matches(p.getAttributes(), attrKey, attrValue)) {
                    total += p.getLongValue();
                }
            }
        }
        return total;
    }

    private static boolean matches(Attributes attributes, String key, String value) {
        for (Attribute a : attributes.asList()) {
            if (a.getKey().equals(key) && value.equals(String.valueOf(a.getValue()))) {
                return true;
            }
        }
        return false;
    }

    // ── traces ──

    @Test
    public void testSessionSpanForACleanSession() {
        connect();
        send("GROUP test.group");
        send("QUIT");
        handler.disconnected();
        Span session = sessionSpan();
        assertEquals("NNTP session", session.getName());
        assertTrue(hasEvent(session, "SESSION_START"));
        assertTrue(hasEvent(session, "SESSION_END"));
        assertTrue(session.getStatus().isOk());
        assertEquals("test.group", attribute(session, "nntp.group"));
        assertEquals("nntp", attribute(exporter.traces.get(0).getRootSpan(), "rpc.system"));
    }

    @Test
    public void testLostConnectionIsAnErrorSpan() {
        connect();
        handler.disconnected();
        Span session = sessionSpan();
        assertTrue(hasEvent(session, "SESSION_ERROR"));
        assertTrue(session.getStatus().isError());
    }

    @Test
    public void testAuthenticationSpansAndMetrics() {
        listener.realm(new Realm1());
        connect();
        send("AUTHINFO USER alice");
        send("AUTHINFO PASS wrong");
        send("AUTHINFO USER alice");
        send("AUTHINFO PASS secret");
        send("QUIT");
        handler.disconnected();
        Span session = sessionSpan();
        assertTrue(hasEvent(session, "AUTH_FAILURE"));
        assertTrue(hasEvent(session, "AUTH_SUCCESS"));
        assertEquals("USER/PASS", attribute(session, "nntp.auth.mechanism"));
        assertEquals("alice", attribute(session, "enduser.id"));
        List<Span> authenticated = session.getChildren();
        assertEquals(1, authenticated.size());
        assertEquals("NNTP authenticated", authenticated.get(0).getName());
        assertEquals("alice", attribute(authenticated.get(0), "enduser.id"));

        assertEquals(2, counter("nntp.server.authentications", "nntp.auth.mechanism", "USER/PASS"));
        assertEquals(1, counter("nntp.server.authentications.success", null, null));
        assertEquals(1, counter("nntp.server.authentications.failure", null, null));
    }

    // ── metrics ──

    @Test
    public void testConnectionCommandAndArticleMetrics() {
        connect();
        assertEquals(1, counter("nntp.server.connections", null, null));
        assertEquals(1, counter("nntp.server.active_connections", null, null));
        send("GROUP test.group");
        send("ARTICLE 1");
        send("STAT 1");
        send("FROBNICATE");
        send("QUIT");
        handler.disconnected();
        assertEquals(1, counter("nntp.server.commands", "nntp.command", "GROUP"));
        assertEquals(2, counter("nntp.server.commands", "nntp.command", "ARTICLE")
                + counter("nntp.server.commands", "nntp.command", "STAT"));
        assertEquals(1, counter("nntp.server.commands", "nntp.command", "UNKNOWN"));
        assertEquals(1, counter("nntp.server.commands", "nntp.command", "QUIT"));
        assertEquals(1, counter("nntp.server.articles.retrieved", null, null));
        long bytes = counter("nntp.server.bytes.sent", null, null);
        // the article as sent: headers, blank line, body and the terminating dot
        assertEquals(("Subject: One\r\nFrom: x@example.com\r\nMessage-ID: <1@x>\r\n\r\nbody\r\n.\r\n").length(), bytes);
        assertEquals(0, counter("nntp.server.active_connections", null, null));
        boolean recorded = false;
        for (MetricData m : config.getMeter("org.bluezoo.gumdrop.nntp", Gumdrop.VERSION)
                .collect(AggregationTemporality.CUMULATIVE)) {
            if (m.getName().equals("nntp.server.session.duration")) {
                for (HistogramDataPoint p : m.getHistogramDataPoints()) {
                    assertEquals(1, p.getCount());
                    recorded = true;
                }
            }
        }
        assertTrue("session duration recorded", recorded);
    }

    @Test
    public void testPostMetrics() {
        listener.postingAllowed(true);
        connect();
        send("POST");
        handler.receive(ByteBuffer.wrap(("From: a@x\r\nSubject: s\r\nNewsgroups: test.group\r\n\r\nx\r\n.\r\n")
                .getBytes(StandardCharsets.ISO_8859_1)));
        send("POST");
        handler.receive(ByteBuffer.wrap("Subject: no from\r\n\r\nx\r\n.\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        send("QUIT");
        handler.disconnected();
        assertEquals(1, counter("nntp.server.articles.posted", null, null));
        assertEquals(1, counter("nntp.server.articles.rejected", null, null));
        Span session = sessionSpan();
        assertTrue(hasEvent(session, "ARTICLE_POSTED"));
        assertTrue(hasEvent(session, "POST_REJECTED"));
    }

    @Test
    public void testPostingRefusedIsCountedAsRejected() {
        connect();
        send("POST");
        assertEquals(1, counter("nntp.server.articles.rejected", null, null));
    }

    @Test
    public void testStartTlsIsCountedWhenTheHandshakeCompletes() {
        listener.tls = true;
        connect();
        send("STARTTLS");
        assertEquals(0, counter("nntp.server.starttls", null, null));
        handler.securityEstablished(null);
        assertEquals(1, counter("nntp.server.starttls", null, null));
        send("QUIT");
        handler.disconnected();
        assertTrue(hasEvent(sessionSpan(), "STARTTLS_SUCCESS"));
    }

    @Test
    public void testStartTlsFailureIsRecorded() {
        listener.tls = true;
        endpoint = new StubEndpoint() {
            @Override
            public TelemetryConfig getTelemetryConfig() {
                return config;
            }

            @Override
            public void startTLS() throws java.io.IOException {
                throw new java.io.IOException("no handshake");
            }
        };
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
        send("STARTTLS");
        handler.disconnected();
        Span session = sessionSpan();
        assertTrue(hasEvent(session, "STARTTLS_FAILURE"));
    }

    @Test
    public void testNoMetricsWhenTheListenerHasNone() {
        NntpListener plain = new NntpListener();
        StubEndpoint e = new StubEndpoint();
        NntpProtocolHandler h = new NntpProtocolHandler(plain);
        h.connected(e);
        h.receive(ByteBuffer.wrap("DATE\r\nQUIT\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        h.disconnected();
        assertTrue(exporter.traces.isEmpty());
    }
}
