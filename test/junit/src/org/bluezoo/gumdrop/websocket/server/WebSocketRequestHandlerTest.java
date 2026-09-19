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
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.websocket.DefaultWebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketMetricsSource;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the HTTP upgrade decisions of {@link WebSocketRequestHandler}
 * against a stub {@link HttpResponseState}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketRequestHandlerTest {

    private static final class StubState implements HttpResponseState {
        Headers responseHeaders;
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
        @Override public void headers(Headers headers) {
            responseHeaders = headers;
        }
        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) { }
        @Override public void endResponseBody() { }
        @Override public void complete() { completed = true; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) {
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
            return responseHeaders == null ? null
                    : responseHeaders.getValue(":status");
        }
    }

    private final WebSocketEventHandler appHandler =
            new DefaultWebSocketEventHandler() {
            };
    private String seenPath;
    private StubState state;

    @Before
    public void setUp() {
        state = new StubState();
        seenPath = null;
    }

    private WebSocketRequestHandler.Builder builder(
            final WebSocketEventHandler result) {
        return WebSocketRequestHandler.builder().onConnect(
                new WebSocketRequestHandler.ConnectionHandlerFactory() {
                    @Override
                    public WebSocketEventHandler create(String requestPath,
                            Headers upgradeHeaders) {
                        seenPath = requestPath;
                        return result;
                    }
                });
    }

    private static Headers upgradeRequest() {
        Headers h = new Headers();
        h.add(":method", "GET");
        h.add(":path", "/chat");
        h.add("Upgrade", "websocket");
        h.add("Connection", "Upgrade");
        h.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        h.add("Sec-WebSocket-Version", "13");
        return h;
    }

    private void open(WebSocketRequestHandler h, Headers headers) {
        HttpRequestHandler rh = h.openStream(state);
        rh.headers(state, headers);
    }

    @Test
    public void validUpgradeInvokesFactoryAndUpgrades() {
        open(builder(appHandler).build(), upgradeRequest());
        assertEquals("/chat", seenPath);
        assertSame(appHandler, state.handler);
        assertNull(state.subprotocol);
        assertNull(state.responseHeaders);
    }

    @Test
    public void invalidUpgradeGets400() {
        Headers h = upgradeRequest();
        h.removeAll("Sec-WebSocket-Key");
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
                            public String select(Headers upgradeHeaders) {
                                return "mqtt";
                            }
                        }).build();
        open(h, upgradeRequest());
        assertEquals("mqtt", state.subprotocol);
    }

    @Test
    public void deflateNegotiatedWhenOffered() {
        Headers h = upgradeRequest();
        h.add("Sec-WebSocket-Extensions", "permessage-deflate");
        open(builder(appHandler).build(), h);
        assertNotNull(state.extensions);
        assertEquals(1, state.extensions.size());
    }

    @Test
    public void deflateNotNegotiatedWhenDisabled() {
        Headers h = upgradeRequest();
        h.add("Sec-WebSocket-Extensions", "permessage-deflate");
        open(builder(appHandler).deflateEnabled(false).build(), h);
        assertTrue(state.extensions == null || state.extensions.isEmpty());
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
        Headers h = new Headers();
        h.add(":method", "CONNECT");
        h.add(":protocol", "websocket");
        h.add(":path", "/h2ws");
        h.add("sec-websocket-extensions", "permessage-deflate");
        open(builder(appHandler).build(), h);
        assertEquals("/h2ws", seenPath);
        assertSame(appHandler, state.handler);
        assertEquals(1, state.extensions.size());
    }

    @Test
    public void extendedConnectFallsBackToAuthorityWithoutPath() {
        Headers h = new Headers();
        h.add(":method", "CONNECT");
        h.add(":protocol", "WebSocket");
        h.add(":authority", "example.org");
        open(builder(appHandler).build(), h);
        assertEquals("example.org", seenPath);
    }

    @Test
    public void plainConnectWithoutProtocolIsRejected() {
        Headers h = new Headers();
        h.add(":method", "CONNECT");
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
        config.setMetricsEnabled(true);
        WebSocketRequestHandler h = builder(appHandler).metrics(config)
                .build();
        HttpRequestHandler rh = h.openStream(state);
        assertNotNull(((WebSocketMetricsSource) rh).getWebSocketMetrics());
    }

    @Test
    public void metricsDisabledConfigYieldsNoMetrics() {
        TelemetryConfig config = new TelemetryConfig();
        config.setMetricsEnabled(false);
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
