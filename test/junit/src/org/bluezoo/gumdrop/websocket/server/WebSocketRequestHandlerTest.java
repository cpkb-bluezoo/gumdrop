/*
 * WebSocketRequestHandlerTest.java
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

package org.bluezoo.gumdrop.websocket.server;

import java.util.ArrayList;
import org.bluezoo.gumdrop.http.HeaderFields;
import org.bluezoo.gumdrop.http.Header;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.List;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.testsupport.ResponseRecorder;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketMetricsSource;
import org.junit.Before;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

/**
 * Tests the HTTP upgrade decisions of {@link WebSocketRequestHandler}
 * against a stub {@link HttpResponse}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketRequestHandlerTest {

    private static final class StubState implements HttpResponse {
        final ResponseRecorder recorder = new ResponseRecorder();
        boolean completed;
        String subprotocol;
        List<WebSocketExtension> extensions;
        WebSocketEventHandler handler;
        boolean failUpgrade;

        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HttpVersion getVersion() {
            return HttpVersion.HTTP_1_1;
        }
        @Override public String getScheme() { return "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void status(int code) { recorder.status(code); }
        @Override public void header(String name, ByteBuffer rawValue) { String value = java.nio.charset.StandardCharsets.ISO_8859_1.decode(rawValue.duplicate()).toString(); recorder.header(name, value); }
        @Override public void endHeaders() { recorder.endHeaders(); }
        @Override public void bodyContent(ByteBuffer data) { recorder.bodyContent(); }
        @Override public void endMessage() {
            recorder.endMessage();
            completed = true;
        }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public void startPushPromise(org.bluezoo.gumdrop.http.HttpMethod method, String target) { }
        @Override public boolean endPushPromise() {
            return false;
        }
        @Override public void upgradeToWebSocket(String subprotocol,
                WebSocketEventHandler handler) {
            upgradeToWebSocket(subprotocol, null, handler);
        }
        @Override public void upgradeToWebSocket(String subprotocol,
                List<WebSocketExtension> extensions,
                WebSocketEventHandler handler) {
            if (failUpgrade) {
                throw new IllegalStateException("not upgradeable");
            }
            this.subprotocol = subprotocol;
            this.extensions = extensions;
            this.handler = handler;
        }
        @Override public void cancel() { }

        String status() {
            return recorder.getValue(":status");
        }
    }

    private final WebSocketEventHandler appHandler =
            new DefaultWebSocketEventHandler() {
            };
    private String seenPath;
    private WebSocketRequestHandler.UpgradeRequest seenRequest;
    private StubState state;

    @Before
    public void setUp() {
        state = new StubState();
        seenPath = null;
        seenRequest = null;
    }

    private WebSocketRequestHandler.Builder builder(
            final WebSocketEventHandler result) {
        return WebSocketRequestHandler.builder().onConnect(
                new WebSocketRequestHandler.ConnectionHandlerFactory() {
                    @Override
                    public WebSocketEventHandler create(String requestPath,
                            WebSocketRequestHandler.UpgradeRequest request) {
                        seenPath = requestPath;
                        seenRequest = request;
                        return result;
                    }
                });
    }

    private static List<Header> upgradeRequest() {
        List<Header> h = new ArrayList<Header>();
        HeaderFields.add(h, ":method", "GET");
        HeaderFields.add(h, ":path", "/chat");
        HeaderFields.add(h, "Upgrade", "websocket");
        HeaderFields.add(h, "Connection", "Upgrade");
        HeaderFields.add(h, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        HeaderFields.add(h, "Sec-WebSocket-Version", "13");
        return h;
    }

    private void open(WebSocketRequestHandler h, List<Header> headers) {
        HttpRequestHandler rh = h.openStream(state);
        MessageEvents.headers(rh, headers);
    }

    @Test
    public void validUpgradeInvokesFactoryAndUpgrades() {
        open(builder(appHandler).build(), upgradeRequest());
        assertEquals("/chat", seenPath);
        assertSame(appHandler, state.handler);
        assertNull(state.subprotocol);
        assertFalse(state.recorder.isStarted());
    }

    @Test
    public void invalidUpgradeGets400() {
        List<Header> h = upgradeRequest();
        HeaderFields.removeAll(h, "Sec-WebSocket-Key");
        open(builder(appHandler).build(), h);
        assertEquals("400", state.status());
        assertTrue(state.completed);
        assertNull(state.handler);
        assertNull(seenPath);
    }

    @Test
    public void nullHandlerFromFactoryGets403() {
        open(builder(null).build(), upgradeRequest());
        assertEquals("403", state.status());
        assertTrue(state.completed);
    }

    @Test
    public void subprotocolSelectorResultPassedToUpgrade() {
        WebSocketRequestHandler h = builder(appHandler)
                .subprotocolSelector(
                        new WebSocketRequestHandler.SubprotocolSelector() {
                            @Override
                            public String select(List<String> offeredSubprotocols) {
                                return "mqtt";
                            }
                        }).build();
        open(h, upgradeRequest());
        assertEquals("mqtt", state.subprotocol);
    }

    /** Opens an upgrade offering the given Sec-WebSocket-Protocol field lines; returns what the selector saw. */
    private List<String> offered(String... fieldLines) {
        final List<List<String>> seen = new ArrayList<List<String>>();
        WebSocketRequestHandler h = builder(appHandler)
                .subprotocolSelector(
                        new WebSocketRequestHandler.SubprotocolSelector() {
                            @Override
                            public String select(List<String> offeredSubprotocols) {
                                seen.add(offeredSubprotocols);
                                return null;
                            }
                        }).build();
        List<Header> req = upgradeRequest();
        for (String line : fieldLines) {
            HeaderFields.add(req, "Sec-WebSocket-Protocol", line);
        }
        open(h, req);
        assertEquals("the selector is asked once", 1, seen.size());
        assertSame(appHandler, state.handler);
        return seen.get(0);
    }

    @Test
    public void selectorGetsEmptyListWhenNoSubprotocolOffered() {
        List<String> offered = offered();
        assertNotNull(offered);
        assertTrue(offered.toString(), offered.isEmpty());
    }

    @Test
    public void selectorGetsASingleOfferedToken() {
        assertEquals(java.util.Arrays.asList("chat"), offered("chat"));
    }

    @Test
    public void selectorGetsTrimmedTokensInOrder() {
        assertEquals(java.util.Arrays.asList("graphql-ws", "mqtt", "chat"),
                offered("  graphql-ws ,mqtt,   chat  "));
    }

    @Test
    public void selectorGetsTokensFromRepeatedFieldLinesInOrder() {
        assertEquals(java.util.Arrays.asList("a", "b", "c"), offered("a, b", "c"));
    }

    @Test
    public void selectorIgnoresEmptyTokens() {
        assertEquals(java.util.Arrays.asList("a", "b"), offered("a,, ,b,"));
    }

    @Test
    public void selectorIsNotAskedForARejectedUpgrade() {
        final int[] asked = new int[1];
        WebSocketRequestHandler h = builder(null)
                .subprotocolSelector(
                        new WebSocketRequestHandler.SubprotocolSelector() {
                            @Override
                            public String select(List<String> offeredSubprotocols) {
                                asked[0]++;
                                return null;
                            }
                        }).build();
        open(h, upgradeRequest());
        assertEquals("403", state.status());
        assertEquals(0, asked[0]);
    }

    /** Records the events it is given, as short strings. */
    private static final class EventLog extends org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler {
        final List<String> events = new ArrayList<String>();

        private static String text(ByteBuffer b) {
            return java.nio.charset.StandardCharsets.ISO_8859_1.decode(b.duplicate()).toString();
        }

        @Override public void method(org.bluezoo.gumdrop.http.HttpMethod method) { events.add("method:" + method.name()); }
        @Override public void target(ByteBuffer target) { events.add("target:" + text(target)); }
        @Override public void scheme(ByteBuffer scheme) { events.add("scheme:" + text(scheme)); }
        @Override public void authority(ByteBuffer authority) { events.add("authority:" + text(authority)); }
        @Override public void protocol(ByteBuffer protocol) { events.add("protocol:" + text(protocol)); }
        @Override public void header(String name, ByteBuffer value) { events.add("header:" + name.toLowerCase() + "=" + text(value)); }
        @Override public void endHeaders() { events.add("endHeaders"); }
    }

    @Test
    public void replayDeliversTheUpgradeRequestEventsInOrder() {
        List<Header> req = new ArrayList<Header>();
        HeaderFields.add(req, ":method", "GET");
        HeaderFields.add(req, ":path", "/chat?room=1");
        HeaderFields.add(req, ":authority", "example.org");
        HeaderFields.add(req, "Upgrade", "websocket");
        HeaderFields.add(req, "Connection", "Upgrade");
        HeaderFields.add(req, "Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        HeaderFields.add(req, "Sec-WebSocket-Version", "13");
        HeaderFields.add(req, "Authorization", "Bearer abc");
        open(builder(appHandler).build(), req);
        assertNotNull(seenRequest);

        EventLog log = new EventLog();
        seenRequest.replay(log);

        int method = log.events.indexOf("method:GET");
        int target = log.events.indexOf("target:/chat?room=1");
        int upgrade = log.events.indexOf("header:upgrade=websocket");
        int key = log.events.indexOf("header:sec-websocket-key=dGhlIHNhbXBsZSBub25jZQ==");
        int auth = log.events.indexOf("header:authorization=Bearer abc");
        assertTrue(log.events.toString(), method >= 0);
        assertTrue(log.events.toString(), method < target);
        assertTrue(log.events.toString(), target < upgrade);
        assertTrue(log.events.toString(), upgrade < key);
        assertTrue(log.events.toString(), key < auth);
        assertEquals("endHeaders is the last event", log.events.size() - 1,
                log.events.indexOf("endHeaders"));
        assertEquals("endHeaders is delivered once", log.events.indexOf("endHeaders"),
                log.events.lastIndexOf("endHeaders"));
    }

    @Test
    public void replayCarriesSchemeAuthorityAndProtocolOfAnExtendedConnect() {
        List<Header> req = new ArrayList<Header>();
        HeaderFields.add(req, ":method", "CONNECT");
        HeaderFields.add(req, ":protocol", "websocket");
        HeaderFields.add(req, ":scheme", "https");
        HeaderFields.add(req, ":authority", "example.org");
        HeaderFields.add(req, ":path", "/h2ws");
        HeaderFields.add(req, "x-token", "t1");
        open(builder(appHandler).build(), req);

        EventLog log = new EventLog();
        seenRequest.replay(log);

        assertTrue(log.events.toString(), log.events.contains("method:CONNECT"));
        assertTrue(log.events.toString(), log.events.contains("scheme:https"));
        assertTrue(log.events.toString(), log.events.contains("authority:example.org"));
        assertTrue(log.events.toString(), log.events.contains("protocol:websocket"));
        assertTrue(log.events.toString(), log.events.contains("target:/h2ws"));
        assertTrue(log.events.toString(), log.events.contains("header:x-token=t1"));
        assertEquals("endHeaders", log.events.get(log.events.size() - 1));
    }

    @Test
    public void replayCanBeRepeatedAndOutlivesTheFactoryCall() {
        open(builder(appHandler).build(), upgradeRequest());
        EventLog first = new EventLog();
        EventLog second = new EventLog();
        seenRequest.replay(first);
        seenRequest.replay(second);
        assertFalse(first.events.isEmpty());
        assertEquals(first.events, second.events);
    }

    @Test
    public void requestIsNotReplayedForARejectedUpgradeBeforeTheFactory() {
        List<Header> h = upgradeRequest();
        HeaderFields.removeAll(h, "Sec-WebSocket-Key");
        open(builder(appHandler).build(), h);
        assertNull("the factory is not called for an invalid upgrade", seenRequest);
    }

    @Test
    public void deflateNegotiatedWhenOffered() {
        List<Header> h = upgradeRequest();
        HeaderFields.add(h, "Sec-WebSocket-Extensions", "permessage-deflate");
        open(builder(appHandler).build(), h);
        assertNotNull(state.extensions);
        assertEquals(1, state.extensions.size());
    }

    @Test
    public void deflateNotNegotiatedWhenDisabled() {
        List<Header> h = upgradeRequest();
        HeaderFields.add(h, "Sec-WebSocket-Extensions", "permessage-deflate");
        open(builder(appHandler).deflateEnabled(false).build(), h);
        assertTrue(state.extensions == null || state.extensions.isEmpty());
    }

    @Test
    public void withoutAMessageSizeTheApplicationHandlerIsUsedAsIs() {
        open(builder(appHandler).build(), upgradeRequest());
        assertSame(appHandler, state.handler);
    }

    /** A connection that is also its own session. */
    private static final class SessionConnection
            extends org.bluezoo.gumdrop.websocket.WebSocketConnection
            implements org.bluezoo.gumdrop.websocket.WebSocketSession {
        @Override protected void opened() { }
        @Override protected void textMessageReceived(String m) { }
        @Override protected void binaryMessageReceived(ByteBuffer d) { }
        @Override protected void closed(int c, String r) { }
        @Override protected void error(Throwable t) { }
        @Override public Principal getPrincipal() { return null; }
    }

    @Test
    public void messageSizeIsAppliedWhenTheConnectionOpens() {
        open(builder(appHandler).maxMessageSize(1234L).build(), upgradeRequest());
        assertNotNull(state.handler);
        org.junit.Assert.assertNotSame(appHandler, state.handler);
        SessionConnection connection = new SessionConnection();
        state.handler.opened(connection);
        assertEquals(1234L, connection.getMaxMessageSize());
    }

    @Test
    public void negativeMessageSizeIsRejected() {
        try {
            builder(appHandler).maxMessageSize(-1L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void failedUpgradeGets400() {
        state.failUpgrade = true;
        open(builder(appHandler).build(), upgradeRequest());
        assertEquals("400", state.status());
        assertTrue(state.completed);
    }

    @Test
    public void extendedConnectUsesPathAndExtensionsHeader() {
        List<Header> h = new ArrayList<Header>();
        HeaderFields.add(h, ":method", "CONNECT");
        HeaderFields.add(h, ":protocol", "websocket");
        HeaderFields.add(h, ":path", "/h2ws");
        HeaderFields.add(h, "sec-websocket-extensions", "permessage-deflate");
        open(builder(appHandler).build(), h);
        assertEquals("/h2ws", seenPath);
        assertSame(appHandler, state.handler);
        assertEquals(1, state.extensions.size());
    }

    @Test
    public void plainConnectWithoutProtocolIsRejected() {
        List<Header> h = new ArrayList<Header>();
        HeaderFields.add(h, ":method", "CONNECT");
        open(builder(appHandler).build(), h);
        assertEquals("400", state.status());
    }

    @Test
    public void metricsSourceNullWithoutTelemetry() {
        WebSocketRequestHandler h = builder(appHandler).build();
        HttpRequestHandler rh = h.openStream(state);
        assertNull(((WebSocketMetricsSource) rh).getWebSocketMetrics());
    }

    @Test
    public void metricsSourceCreatedWhenMetricsEnabled() {
        TelemetryConfig config = new TelemetryConfig();
        config.metricsEnabled(true);
        WebSocketRequestHandler h = builder(appHandler).metrics(config)
                .build();
        HttpRequestHandler rh = h.openStream(state);
        assertNotNull(((WebSocketMetricsSource) rh).getWebSocketMetrics());
    }

    @Test
    public void metricsDisabledConfigYieldsNoMetrics() {
        TelemetryConfig config = new TelemetryConfig();
        config.metricsEnabled(false);
        WebSocketRequestHandler h = builder(appHandler).metrics(config)
                .build();
        HttpRequestHandler rh = h.openStream(state);
        assertFalse(((WebSocketMetricsSource) rh).getWebSocketMetrics()
                != null);
    }

    @Test
    public void builderRequiresOnConnect() {
        try {
            WebSocketRequestHandler.builder().build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // onConnect is required
        }
    }

    @Test
    public void builderRejectsNullFactory() {
        try {
            WebSocketRequestHandler.builder().onConnect(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // null factory
        }
    }
}
