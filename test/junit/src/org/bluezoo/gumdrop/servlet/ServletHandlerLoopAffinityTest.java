/*
 * ServletHandlerLoopAffinityTest.java
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

package org.bluezoo.gumdrop.servlet;

import static org.junit.Assert.assertEquals;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

/**
 * All network I/O of a connection must happen on that connection's own
 * selector loop. Servlet code runs on worker threads, so anything the
 * servlet layer asks of the response state, including HTTP/2 server push,
 * must be handed to the state's {@code execute()} rather than performed
 * directly on the calling thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletHandlerLoopAffinityTest {

    @Test
    public void serverPushIsRescheduledOntoTheLoop() throws Exception {
        Container container = new Container() {
            @Override
            public void serviceRequest(ServletHandler servletHandler) {
            }
        };
        DeferringState state = new DeferringState();
        ServletHandler handler = new ServletHandler(container, state, 8192);
        ServletHeaders h = new ServletHeaders();
        h.add(":method", "GET");
        h.add(":path", "/index.html");
        MessageEvents.headers(handler, h);
        state.queued.clear();

        List<String[]> pushHeaders = new ArrayList<String[]>();
        handler.executePush("GET", "/style.css", pushHeaders);

        assertEquals("no I/O may happen on the calling (worker) thread",
                0, state.pushes);
        assertEquals("the push must be queued for the loop", 1, state.queued.size());
        state.queued.get(0).run();
        assertEquals(1, state.pushes);
        assertEquals("/style.css", state.lastPushTarget);
    }

    /** Response state whose loop only runs tasks when the test says so. */
    private static final class DeferringState implements HttpResponse {
        final List<Runnable> queued = new ArrayList<Runnable>();
        int pushes;
        String lastPushTarget;

        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HttpVersion getVersion() { return HttpVersion.HTTP_2_0; }
        @Override public String getScheme() { return "http"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void status(int code) { }
        @Override public void header(String name, ByteBuffer rawValue) { }
        @Override public void endHeaders() { }
        @Override public void bodyContent(ByteBuffer data) { }
        @Override public void endMessage() { }
        @Override public void execute(Runnable task) { queued.add(task); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public void startPushPromise(HttpMethod method, String target) {
            lastPushTarget = target;
        }
        @Override public boolean endPushPromise() {
            pushes++;
            return true;
        }
        @Override public void upgradeToWebSocket(String protocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }
}
