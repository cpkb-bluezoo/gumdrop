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

    /**
     * Registers a one-shot callback invoked when the transport is ready
     * for more data on the given stream.
     *
     * <p>For HTTP/1.1 this registers on the TCP endpoint's write-complete
     * callback.  For HTTP/2 this is tracked per-stream; the callback fires
     * when the stream's flow-control send window opens (WINDOW_UPDATE
     * received) or when the TCP write buffer drains.  For HTTP/3 the
     * callback fires when the QUIC congestion/flow-control window opens
     * after a previous {@code QUICHE_ERR_DONE} from
     * {@code quiche_h3_send_body}.
     *
     * @param streamId the stream requesting write-readiness notification
     * @param callback the callback, or null to clear
     */
    void onWritable(int streamId, Runnable callback);

    /**
     * Pauses delivery of request body data for the given stream.
     *
     * <p>For HTTP/1.1, this removes {@code OP_READ} from the
     * connection's {@code SelectionKey}, causing TCP backpressure.
     *
     * <p>For HTTP/2, this withholds WINDOW_UPDATE frames for the
     * stream.  The peer's send window will eventually fill and it
     * will stop sending DATA on this stream, without affecting other
     * streams on the same connection.
     *
     * <p>For HTTP/3, this stops consuming body data from the QUIC
     * stream via {@code quiche_h3_recv_body}.  The peer's
     * flow-control window fills naturally and it stops sending,
     * without affecting other streams.
     *
     * @param streamId the stream to pause
     */
    void pauseRead(int streamId);

    /**
     * Resumes delivery of request body data for the given stream
     * after a previous {@link #pauseRead(int)}.
     *
     * <p>For HTTP/1.1, this restores {@code OP_READ} on the
     * connection's {@code SelectionKey}.
     *
     * <p>For HTTP/2, this sends the accumulated WINDOW_UPDATE
     * increment that was withheld while the stream was paused,
     * allowing the peer to resume sending DATA.
     *
     * <p>For HTTP/3, this resumes draining body data from the QUIC
     * stream.  quiche will automatically send MAX_STREAM_DATA as
     * data is consumed, re-opening the peer's flow-control window.
     *
     * @param streamId the stream to resume
     */
    void resumeRead(int streamId);
}
