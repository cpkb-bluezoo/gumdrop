/*
 * DelegatingResponseState.java
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

package org.bluezoo.gumdrop.testsupport;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.List;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;

/**
 * An {@link HttpResponse} that passes everything to another one, which
 * may be set after a handler has been given this one. A handler takes its
 * response when it is created, so a test that builds the handler first and
 * decides later what records the response sets the target here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DelegatingResponseState implements HttpResponse {

    private HttpResponse target;

    /** Sets the state that receives the calls. */
    public void setTarget(HttpResponse target) {
        this.target = target;
    }

    @Override public SocketAddress getRemoteAddress() { return target.getRemoteAddress(); }
    @Override public SocketAddress getLocalAddress() { return target.getLocalAddress(); }
    @Override public boolean isSecure() { return target.isSecure(); }
    @Override public SecurityInfo getSecurityInfo() { return target.getSecurityInfo(); }
    @Override public HttpVersion getVersion() { return target.getVersion(); }
    @Override public String getScheme() { return target.getScheme(); }
    @Override public String getConnectionId() { return target.getConnectionId(); }
    @Override public String getProtocolConnectionId() { return target.getProtocolConnectionId(); }
    @Override public SelectorLoop getSelectorLoop() { return target.getSelectorLoop(); }
    @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return target.scheduleTimer(delayMs, callback);
    }
    @Override public Trace getTrace() { return target.getTrace(); }
    @Override public Principal getPrincipal() { return target.getPrincipal(); }
    @Override public void status(int code) { target.status(code); }
    @Override public void header(String name, java.nio.ByteBuffer value) { target.header(name, value); }
    @Override public void endHeaders() { target.endHeaders(); }
    @Override public void bodyContent(ByteBuffer data) { target.bodyContent(data); }
    @Override public void endMessage() { target.endMessage(); }
    @Override public void execute(Runnable task) { target.execute(task); }
    @Override public void onWritable(Runnable callback) { target.onWritable(callback); }
    @Override public int pendingResponseBytes() { return target.pendingResponseBytes(); }
    @Override public void pauseRequestBody() { target.pauseRequestBody(); }
    @Override public void resumeRequestBody() { target.resumeRequestBody(); }
    @Override public void startPushPromise(HttpMethod method, String requestTarget) {
        target.startPushPromise(method, requestTarget);
    }
    @Override public boolean endPushPromise() { return target.endPushPromise(); }
    @Override public boolean sendDatagram(ByteBuffer data) { return target.sendDatagram(data); }
    @Override public boolean sendDatagram(long contextId, ByteBuffer payload) {
        return target.sendDatagram(contextId, payload);
    }
    @Override public boolean acceptConnectUdp() { return target.acceptConnectUdp(); }
    @Override public boolean acceptConnectIp() { return target.acceptConnectIp(); }
    @Override public boolean sendCapsule(long type, ByteBuffer value) {
        return target.sendCapsule(type, value);
    }
    @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) {
        target.upgradeToWebSocket(subprotocol, handler);
    }
    @Override public void upgradeToWebSocket(String subprotocol,
            List<WebSocketExtension> extensions, WebSocketEventHandler handler) {
        target.upgradeToWebSocket(subprotocol, extensions, handler);
    }
    @Override public void cancel() { target.cancel(); }
}
