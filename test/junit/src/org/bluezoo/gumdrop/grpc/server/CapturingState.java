/*
 * CapturingState.java
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

package org.bluezoo.gumdrop.grpc.server;

import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.testsupport.ResponseRecorder;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

/**
 * An {@link HttpResponse} that records what is sent through it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class CapturingState implements HttpResponse {
    final ResponseRecorder headers = new ResponseRecorder();
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    final java.util.List<String> trailers = new java.util.ArrayList<String>();
    boolean bodySeen;
    int completeCount;
    Runnable writable;
    int pending;
    int pauseCount;
    int resumeCount;
    int cancelCount;
    SocketAddress remoteAddress;
    SecurityInfo securityInfo;
    Principal principal;
    Runnable timerCallback;
    long timerDelayMs = -1;
    boolean timerCancelled;

    @Override
    public void status(int code) {
        headers.status(code);
    }

    @Override
    public void header(String name, ByteBuffer rawValue) {
        String value = java.nio.charset.StandardCharsets.ISO_8859_1.decode(rawValue.duplicate()).toString();
        headers.header(name, value);
        if (bodySeen) {
            trailers.add(name + ": " + value);
        }
    }

    @Override
    public void endHeaders() {
        headers.endHeaders();
    }

    @Override
    public void bodyContent(ByteBuffer data) {
        bodySeen = true;
        headers.bodyContent();
        if (data.hasRemaining()) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            body.write(chunk, 0, chunk.length);
        }
    }

    @Override
    public void endMessage() {
        headers.endMessage();
        completeCount++;
    }

    @Override public SocketAddress getRemoteAddress() { return remoteAddress; }
    @Override public SocketAddress getLocalAddress() { return null; }
    @Override public boolean isSecure() { return false; }
    @Override public SecurityInfo getSecurityInfo() { return securityInfo; }
    @Override public HttpVersion getVersion() { return HttpVersion.HTTP_2_0; }
    @Override public String getScheme() { return "http"; }
    @Override public SelectorLoop getSelectorLoop() { return null; }
    @Override public Principal getPrincipal() { return principal; }
@Override
public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) {
    timerDelayMs = delayMs;
    timerCallback = callback;
    return new org.bluezoo.gumdrop.TimerHandle() {
        public void cancel() { timerCancelled = true; }
        public boolean isCancelled() { return timerCancelled; }
    };
}
    @Override public void execute(Runnable task) { task.run(); }
    @Override public void onWritable(Runnable callback) { writable = callback; }
    @Override public int pendingResponseBytes() { return pending; }
    @Override public void pauseRequestBody() { pauseCount++; }
    @Override public void resumeRequestBody() { resumeCount++; }
    @Override public void startPushPromise(org.bluezoo.gumdrop.http.HttpMethod method, String target) { }
    @Override public boolean endPushPromise() { return false; }
    @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
    @Override public void cancel() { cancelCount++; }
    @Override public boolean sendDatagram(ByteBuffer data) { return false; }
    @Override public boolean sendCapsule(long type, ByteBuffer value) { return false; }
    @Override public boolean acceptConnectIp() { return false; }
}
