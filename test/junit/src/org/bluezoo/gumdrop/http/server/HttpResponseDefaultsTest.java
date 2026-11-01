/*
 * HttpResponseDefaultsTest.java
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

package org.bluezoo.gumdrop.http.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.SocketAddress;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import static org.junit.Assert.*;

/**
 * The default methods of {@link HttpResponse}, against an implementation
 * that supplies only the abstract ones.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // uses value classes that are deprecated for removal
public class HttpResponseDefaultsTest {

    /** An {@link HttpResponse} supplying only the abstract methods; subclasses override what they observe. */
    static class Minimal implements HttpResponse {

        final List<String> headers = new ArrayList<String>();
        WebSocketEventHandler upgraded;
        String upgradedSubprotocol;

        @Override
        public SocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public HttpVersion getVersion() {
            return HttpVersion.HTTP_1_1;
        }

        @Override
        public String getScheme() {
            return "http";
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public void status(int code) {
        }

        @Override
        public void header(String name, ByteBuffer value) {
            byte[] bytes = new byte[value.remaining()];
            value.duplicate().get(bytes);
            headers.add(name + "=" + new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void endHeaders() {
        }

        @Override
        public void bodyContent(ByteBuffer data) {
        }

        @Override
        public void endMessage() {
        }

        @Override
        public void execute(Runnable task) {
        }

        @Override
        public void onWritable(Runnable callback) {
        }

        @Override
        public void pauseRequestBody() {
        }

        @Override
        public void resumeRequestBody() {
        }

        @Override
        public void startPushPromise(HttpMethod method, String target) {
        }

        @Override
        public boolean endPushPromise() {
            return false;
        }

        @Override
        public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) {
            upgradedSubprotocol = subprotocol;
            upgraded = handler;
        }

        @Override
        public void cancel() {
        }
    }

    @Test
    public void optionalCapabilitiesAreAbsentByDefault() {
        Minimal response = new Minimal();
        assertNull(response.scheduleTimer(10L, new Runnable() {
            @Override
            public void run() {
            }
        }));
        assertNull(response.getTrace());
        assertNull(response.getApplicationPrincipal());
        assertFalse(response.sendDatagram(ByteBuffer.allocate(1)));
        assertFalse(response.acceptConnectUdp());
        assertFalse(response.acceptConnectIp());
        assertFalse(response.sendCapsule(0L, ByteBuffer.allocate(1)));
    }

    @Test
    public void contentTypeAndDispositionBecomeHeaders() {
        Minimal response = new Minimal();
        response.contentType(new ContentType("text", "plain", null));
        response.contentDisposition(new ContentDisposition("inline", null));
        assertEquals(2, response.headers.size());
        assertEquals("Content-Type=text/plain", response.headers.get(0));
        assertEquals("Content-Disposition=inline", response.headers.get(1));
    }

    @Test
    public void webSocketUpgradeWithExtensionsFallsBackToPlainUpgrade() {
        Minimal response = new Minimal();
        WebSocketEventHandler handler = null;
        response.upgradeToWebSocket("chat", null, handler);
        assertEquals("chat", response.upgradedSubprotocol);
        assertSame(handler, response.upgraded);
    }
}
