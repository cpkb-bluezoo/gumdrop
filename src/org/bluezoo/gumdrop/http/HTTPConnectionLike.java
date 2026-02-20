/*
 * HTTPConnectionLike.java
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

package org.bluezoo.gumdrop.http;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Interface for HTTP connection abstractions used by {@link Stream}.
 *
 * <p>{@link HTTPProtocolHandler} implements this interface so that
 * {@link Stream} can interact with the HTTP protocol handler uniformly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface HTTPConnectionLike {

    String getScheme();
    HTTPVersion getVersion();
    SocketAddress getRemoteSocketAddress();
    SocketAddress getLocalSocketAddress();
    SecurityInfo getSecurityInfoForStream();
    HTTPRequestHandlerFactory getHandlerFactory();
    void sendResponseHeaders(int streamId, int statusCode, Headers headers, boolean endStream);
    void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream);
    void send(ByteBuffer buf);
    void sendRstStream(int streamId, int errorCode);
    void sendGoaway(int errorCode);
    void switchToWebSocketMode(int streamId);
    Decoder getHpackDecoder();
    boolean isSecure();
    TelemetryConfig getTelemetryConfig();
    Trace getTrace();
    void setTrace(Trace trace);
    boolean isTelemetryEnabled();
    HTTPServerMetrics getServerMetrics();
    boolean isEnablePush();
    Stream newStream(HTTPConnectionLike connection, int streamId);
    int getNextServerStreamId();
    byte[] encodeHeaders(Headers headers);
    void sendPushPromise(int streamId, int promisedStreamId, ByteBuffer headerBlock, boolean endHeaders);
    Stream createPushedStream(int streamId, String method, String uri, Headers headers);
    SelectorLoop getSelectorLoop();
}
